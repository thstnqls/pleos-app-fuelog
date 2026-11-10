package com.pleos.fuelog.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.location.PleosLocationClient;
import org.json.JSONObject;
import java.io.IOException;

/** One foreground location update per charge candidate; raw coordinates never enter the outbox. */
final class ChargeEnrichment {
    private final Context context;
    private final HomeRecorder recorder;
    private final ConnectionSession connection;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PleosLocationClient client;
    private okhttp3.Call call;
    private JSONObject evidence;
    private String id = "", owner = "";
    private int generation, attempts;
    ChargeEnrichment(Context context, HomeRecorder recorder, ConnectionSession connection) {
        this.context = context; this.recorder = recorder; this.connection = connection;
    }
    void observe(JSONObject candidate, boolean first) {
        if (first) {
            close();
            if (!recorder.locationAssistConsent() || recorder.verifiedOwner().isEmpty()) return;
            id = candidate.optString("id"); owner = recorder.verifiedOwner();
            int version = generation;
            long requested = System.currentTimeMillis();
            double distance = recorder.trip.previousKm();
            client = new PleosLocationClient(context);
            client.getCurrentLocation(new PleosLocationClient.Callback() {
                public void onSuccess(PleosLocationClient.Position position) {
                    if (version != generation || !owner.equals(recorder.verifiedOwner())) return;
                    double current = recorder.trip.previousKm();
                    if (!Double.isFinite(distance) || !Double.isFinite(current) || current != distance) return;
                    long now = System.currentTimeMillis(), measured = position.getTimestampMillis();
                    if (!position.isFromUpdate() || measured < requested || measured > now || now - requested > 10000) return;
                    try {
                        evidence = new JSONObject().put("candidateId", id).put("locationOperationId", "charge-estimate:" + id)
                                .put("latitude", position.getLatitude()).put("longitude", position.getLongitude())
                                .put("measuredAt", RecordingPayloads.iso(measured)).put("receivedAt", RecordingPayloads.iso(now))
                                .put("source", "PLEOS_CURRENT_UPDATE");
                        recorder.rememberLocation(id, owner, "CHARGE_DETECTION", position.getLatitude(), position.getLongitude(), measured);
                        handler.postDelayed(() -> { if (version == generation) close(); }, 120000);
                    } catch (Exception invalid) { evidence = null; }
                }
                public void onError(PleosLocationClient.ErrorCode code, Exception cause) { evidence = null; }
            });
        }
        if (candidate.optBoolean("stable") && candidate.optLong("serverRevision") > 0) synced(candidate);
    }
    void synced(JSONObject candidate) {
        if (evidence == null || !id.equals(candidate.optString("id")) || call != null || attempts >= 3
                || !owner.equals(recorder.verifiedOwner()) || !candidate.optString("status").equals("CANDIDATE")) return;
        JSONObject local = recorder.currentCharge(id);
        if (local == null || !local.optBoolean("stable")) return;
        send(generation);
    }
    private void send(int version) {
        if (version != generation || evidence == null || call != null || attempts >= 3 || !owner.equals(recorder.verifiedOwner())) return;
        JSONObject local = recorder.currentCharge(id);
        if (local == null || !local.optString("status").equals("CANDIDATE")) { close(); return; }
        attempts++;
        try {
            call = connection.request("POST", "charge-candidates/" + id + "/estimate", evidence, "", new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { handler.post(() -> {
                    if (version != generation) return;
                    call = null;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        if (!id.equals(result.getString("id")) || !owner.equals(result.getString("owner"))
                                || !owner.equals(recorder.verifiedOwner())) throw new IllegalArgumentException();
                        recorder.applyChargeResponse(result);
                    }
                    catch (Exception invalid) { recorder.storageError = "충전 추정 응답 저장 실패"; }
                    evidence = null;
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { failed(); }
                public void onFailure(IOException error) { failed(); }
                private void failed() { handler.post(() -> {
                    if (version != generation) return;
                    call = null;
                    if (attempts < 3) handler.postDelayed(() -> send(version), 5000);
                    else evidence = null;
                }); }
            });
        } catch (Exception failure) { call = null; evidence = null; }
    }
    void close() {
        generation++; handler.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        if (call != null) call.cancel();
        client = null; call = null; evidence = null; id = ""; owner = ""; attempts = 0;
    }
}
