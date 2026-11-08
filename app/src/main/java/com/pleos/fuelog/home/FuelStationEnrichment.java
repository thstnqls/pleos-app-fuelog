package com.pleos.fuelog.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.location.PleosLocationClient;
import java.io.IOException;
import org.json.JSONObject;

/** Foreground-only F01. No coordinate persistence, Opinet key, or client-side conversion. */
final class FuelStationEnrichment {
    private final Context context;
    private final HomeRecorder recorder;
    private final ConnectionSession connection;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PleosLocationClient location;
    private okhttp3.Call call;
    private JSONObject evidence;
    private String candidateId = "", owner = "";
    private boolean verifiedCoordinates;
    private String fuelType = "";
    private int generation, attempts;

    FuelStationEnrichment(Context context, HomeRecorder recorder, ConnectionSession connection) {
        this.context = context; this.recorder = recorder; this.connection = connection;
    }

    void configure(boolean verified, String type) {
        if (!verified || !fuelType.equals(type)) close();
        verifiedCoordinates = verified; fuelType = type;
    }

    void observe(JSONObject candidate, boolean firstObservation) {
        if (candidate == null) return;
        String id = candidate.optString("id");
        if (firstObservation) {
            close();
            if (!recorder.locationAssistConsent()
                    || !verifiedCoordinates || fuelType.isEmpty() || recorder.verifiedOwner().isEmpty()) return;
            candidateId = id; owner = recorder.verifiedOwner();
            int version = generation;
            long requested = System.currentTimeMillis();
            double distance = recorder.trip.previousKm();
            location = new PleosLocationClient(context);
            location.getCurrentLocation(new PleosLocationClient.Callback() {
                public void onSuccess(PleosLocationClient.Position position) {
                    if (version != generation || !owner.equals(recorder.verifiedOwner())) return;
                    // A fresh update received after observed movement cannot locate the earlier increase.
                    double current = recorder.trip.previousKm();
                    if (!Double.isFinite(distance) || !Double.isFinite(current) || current != distance) return;
                    try {
                        evidence = FuelLocationEvidence.body(id, position.getLatitude(), position.getLongitude(),
                                position.getTimestampMillis(), System.currentTimeMillis(), requested, fuelType, verifiedCoordinates);
                        handler.postDelayed(() -> { if (version == generation) close(); }, 24 * 60 * 60 * 1000L);
                    } catch (Exception invalid) { evidence = null; }
                }
                public void onError(PleosLocationClient.ErrorCode code, Exception cause) { evidence = null; }
            });
        }
        if (id.equals(candidateId) && evidence != null && candidate.optBoolean("stable") && candidate.optLong("checkedAt") > 0) synced(candidate);
        if (id.equals(candidateId) && evidence != null && candidate.optBoolean("stable") && !candidate.optBoolean("queued")) {
            try { recorder.queueFuel(id); } catch (Exception error) { recorder.storageError = "주유 후보 전송 보류"; }
        }
    }

    void synced(JSONObject candidate) {
        JSONObject latest = recorder.fuelCandidate();
        if (latest == null || !latest.optBoolean("stable")) return;
        if (evidence == null || !candidateId.equals(candidate.optString("id")) || call != null || attempts >= 3
                || !owner.equals(recorder.verifiedOwner()) || !candidate.optString("status").equals("CANDIDATE")) return;
        search(generation);
    }

    private void search(int version) {
        if (version != generation || evidence == null || call != null || attempts >= 3 || !owner.equals(recorder.verifiedOwner())) return;
        try {
            JSONObject current = recorder.fuelCandidate();
            if (current == null || !candidateId.equals(current.optString("id"))
                    || !current.optString("status").equals("CANDIDATE")
                    || System.currentTimeMillis() - java.time.Instant.parse(evidence.getString("receivedAt")).toEpochMilli() >= 24 * 60 * 60 * 1000L) {
                evidence = null; return;
            }
            attempts++;
            call = connection.request("POST", "fuel-candidates/" + candidateId + "/station-search", evidence, "", new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { handler.post(() -> {
                    if (version != generation) return;
                    call = null;
                    try {
                        JSONObject result = new JSONObject(response.getBody()), latest = recorder.fuelCandidate();
                        if (latest == null || !candidateId.equals(result.getString("candidateId"))
                                || !candidateId.equals(latest.optString("id")) || !owner.equals(recorder.verifiedOwner())
                                || !latest.optString("status").equals("CANDIDATE")
                                || result.getLong("revision") <= latest.optLong("revision")) { evidence = null; return; }
                        latest.put("revision", result.getLong("revision"));
                        latest.put("stations", result.getJSONArray("stations")).put("fuelType", evidence.getString("fuelType"));
                        recorder.updateFuel(latest, candidateId, owner);
                        evidence = null;
                    } catch (Exception invalid) { evidence = null; }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { failed(); }
                public void onFailure(IOException error) { failed(); }
                private void failed() { handler.post(() -> {
                    if (version != generation) return;
                    call = null;
                    if (attempts < 3) handler.postDelayed(() -> search(version), 5000);
                    else evidence = null;
                }); }
            });
        } catch (Exception error) { evidence = null; call = null; }
    }

    void close() {
        generation++; handler.removeCallbacksAndMessages(null);
        if (location != null) location.close();
        location = null;
        if (call != null) call.cancel();
        call = null; evidence = null; candidateId = ""; owner = ""; attempts = 0;
    }
}
