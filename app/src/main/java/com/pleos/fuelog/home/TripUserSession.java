package com.pleos.fuelog.home;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Only server-verified, trip-scoped candidates are exposed. No nearby-device discovery. */
public final class TripUserSession {
    public static final class Candidate {
        public final String id, name;
        public final long deadline;
        Candidate(JSONObject json) throws Exception {
            id = json.getString("candidateId"); name = json.getString("displayName");
            int ttl = json.getInt("remainingSeconds");
            if (!json.getBoolean("vehicleMember") || !json.getBoolean("mobileConsented")
                    || ttl <= 0 || ttl > 300 || id.isEmpty() || name.isEmpty()) throw new IllegalArgumentException();
            deadline = SystemClock.elapsedRealtime() + ttl * 1000L;
        }
        public boolean valid() { return deadline > SystemClock.elapsedRealtime(); }
    }
    public final List<Candidate> candidates = new ArrayList<>();
    public String selected = "", message = "휴대폰에서 이번 운행 사용에 동의해 주세요";
    public boolean busy;
    private final ConnectionSession connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private okhttp3.Call call;
    private int generation;
    public TripUserSession(ConnectionSession connection) { this.connection = connection; }
    public void cancel() { generation++; if (call != null) call.cancel(); busy = false; candidates.clear(); selected = ""; }
    public Candidate selection() {
        for (Candidate candidate : candidates) if (candidate.id.equals(selected) && candidate.valid()) return candidate;
        return null;
    }
    public void load(String intervalId, Runnable changed) {
        candidates.clear(); selected = "";
        try {
            send("trip-user/candidates", new JSONObject().put("intervalId", intervalId), json -> {
                if (!intervalId.equals(json.getString("intervalId"))) throw new IllegalArgumentException();
                JSONArray list = json.getJSONArray("candidates");
                List<Candidate> parsed = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) parsed.add(new Candidate(list.getJSONObject(i)));
                candidates.addAll(parsed);
                message = candidates.isEmpty() ? "인증된 후보가 없습니다. 휴대폰에서 동의해 주세요" : "이번 운행에 사용할 휴대폰을 선택하세요";
            }, changed);
        } catch (Exception e) { message = "후보를 조회하지 못했습니다"; changed.run(); }
    }
    public interface Verified { void accept(String subject, String name, long expiresAt) throws Exception; }
    public void confirm(String intervalId, String operationId, Verified verified, Runnable changed) {
        Candidate candidate = selection();
        if (candidate == null || busy) return;
        try {
            send("trip-user/verify", new JSONObject().put("intervalId", intervalId)
                    .put("candidateId", candidate.id).put("operationId", operationId), json -> {
                if (!intervalId.equals(json.getString("intervalId")) || !candidate.id.equals(json.getString("candidateId"))
                        || !json.getBoolean("vehicleMember") || !json.getBoolean("mobileConsented")) throw new IllegalArgumentException();
                String subject = json.getString("subjectId");
                long expiresAt = json.getLong("expiresAtEpochMillis");
                if (subject.isEmpty() || expiresAt <= System.currentTimeMillis() || !candidate.valid()) throw new IllegalArgumentException();
                verified.accept(subject, candidate.name, expiresAt);
            }, changed);
        } catch (Exception e) { message = "사용자 확인을 보류했습니다"; changed.run(); }
    }
    private interface Response { void accept(JSONObject json) throws Exception; }
    private void send(String path, JSONObject body, Response response, Runnable changed) {
        busy = true; int version = ++generation;
        try {
            call = connection.requestUser(path, body.toString(), new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse result) { main.post(() -> {
                    if (generation != version) return;
                    busy = false;
                    try { response.accept(new JSONObject(result.getBody())); }
                    catch (Exception e) { candidates.clear(); selected = ""; message = "인증이 만료되었거나 확인할 수 없습니다. 전환을 보류했습니다"; }
                    changed.run();
                }); }
                public void onHttpError(ApiClient.ApiResponse result) { fail(); }
                public void onFailure(java.io.IOException error) { fail(); }
                private void fail() { main.post(() -> {
                    if (generation != version) return;
                    busy = false; candidates.clear(); selected = "";
                    message = "서버 검증 불가 · 기존 기록 사용자를 유지합니다"; changed.run();
                }); }
            });
        } catch (RuntimeException e) { busy = false; message = "차량 연결과 서버 설정을 확인해 주세요"; changed.run(); }
    }
}
