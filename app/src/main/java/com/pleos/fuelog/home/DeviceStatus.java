package com.pleos.fuelog.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import org.json.JSONObject;
import java.util.UUID;

/** Telemetry failures never block the recording outbox. The raw sample retains its retry ID. */
final class DeviceStatus {
    private final ConnectionSession connection;
    private final SharedPreferences store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean reading, sendingOdometer, sendingFeatures;
    private long nextRead, nextTelemetry;
    private String fuel = "UNKNOWN", distance = "UNKNOWN";
    DeviceStatus(Context context, ConnectionSession connection) {
        this.connection = connection;
        store = context.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
    }
    void fuel(boolean available) { fuel = available ? "AVAILABLE" : "TEMPORARY_FAILURE"; }
    void odometer(Float km, long now) throws Exception {
        boolean valid = km != null && Float.isFinite(km) && km >= 0;
        distance = valid ? "AVAILABLE" : "TEMPORARY_FAILURE";
        if (store.contains("odometerBody")) return;
        JSONObject body = new JSONObject().put("km", valid ? km : JSONObject.NULL).put("observedAt", now)
                .put("unit", "km").put("source", "PLEOS_VEHICLE_API").put("quality", valid ? "VALID" : "ERROR");
        if (!store.edit().putString("odometerId", UUID.randomUUID().toString())
                .putString("odometerBody", body.toString()).commit()) throw new java.io.IOException();
    }
    void refresh() {
        if (!connection.linked) return;
        long now = System.currentTimeMillis();
        if (!reading && now >= nextRead) {
            nextRead = now + 15000; reading = true;
            String proof = connection.personalToken();
            connection.request("GET", "vehicle/home", null, "", true, new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                    reading = false;
                    try { if (proof.equals(connection.personalToken())) {
                        JSONObject home = new JSONObject(response.getBody());
                        java.time.Instant.parse(home.getString("generatedAt"));
                        home.getString("recordingStatus");
                        connection.home = home;
                    } }
                    catch (Exception error) { connection.apiStatus().invalid("GET", "vehicle/home"); }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> reading = false); }
                public void onFailure(java.io.IOException error) { main.post(() -> reading = false); }
            });
            connection.request("GET", "sync/status", null, "", false, snapshot("sync"));
            connection.request("GET", "installation/sync-status", null, "", false, snapshot("queue"));
        }
        if (now < nextTelemetry) return;
        nextTelemetry = now + 60000;
        try {
            if (!sendingOdometer && store.contains("odometerBody")) {
                sendingOdometer = true;
                String id = store.getString("odometerId", "");
                connection.request("PUT", "vehicle-odometer/samples/" + id,
                        new JSONObject(store.getString("odometerBody", "{}")), "", false, new ApiClient.ResultCallback() {
                    public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                        sendingOdometer = false;
                        try {
                            JSONObject result = new JSONObject(response.getBody());
                            if (!id.equals(result.getString("sampleId")) || !result.has("receivedAt")) throw new IllegalArgumentException();
                            store.edit().remove("odometerId").remove("odometerBody").commit();
                        } catch (Exception error) { connection.apiStatus().invalid("PUT", "vehicle-odometer/samples/{id}"); }
                    }); }
                    public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> sendingOdometer = false); }
                    public void onFailure(java.io.IOException error) { main.post(() -> sendingOdometer = false); }
                });
            }
            if (!sendingFeatures) {
                sendingFeatures = true;
                JSONObject features = new JSONObject().put("fuel", fuel).put("distance", distance).put("location", "UNKNOWN");
                connection.request("PUT", "installation/capabilities", new JSONObject().put("observedAt", now)
                        .put("features", features), "", false, new ApiClient.ResultCallback() {
                    public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> sendingFeatures = false); }
                    public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> sendingFeatures = false); }
                    public void onFailure(java.io.IOException error) { main.post(() -> sendingFeatures = false); }
                });
            }
        } catch (Exception error) { sendingFeatures = false; sendingOdometer = false; }
    }
    private ApiClient.ResultCallback snapshot(String key) {
        return new ApiClient.ResultCallback() {
            public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                try {
                    JSONObject value = new JSONObject(response.getBody());
                    if (key.equals("sync")) connection.syncStatus = value;
                    else connection.queueStatus = value;
                } catch (Exception ignored) { }
            }); }
            public void onHttpError(ApiClient.ApiResponse response) { }
            public void onFailure(java.io.IOException error) { }
        };
    }
}
