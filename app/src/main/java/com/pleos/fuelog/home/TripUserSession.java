package com.pleos.fuelog.home;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import okhttp3.Call;
import org.json.JSONArray;
import org.json.JSONObject;

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
    public String qrPayload = "", challengeId = "";
    public boolean personalChallenge;
    private long challengeExpiresAt;
    private String challengeOperation = UUID.randomUUID().toString();
    private long eventCursor;
    private boolean pollingEvents;
    private final ConnectionSession connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Call call;
    private int generation;
    public interface Verified { void accept(String subject, String name, long expiresAt, String candidateId) throws Exception; }
    private interface Response { void accept(JSONObject json) throws Exception; }

    // 1. 사용자 화면 진입: 차량 연결 세션을 사용한다.
    public TripUserSession(ConnectionSession connection) { this.connection = connection; }

    // 2. 모바일 동의: 운행 또는 개인 홈 인증 QR을 발급하고 승인을 기다린다.
    public void challenge(String intervalId, boolean personal, Runnable changed) {
        if (busy) return;
        if (challengeValid() && personalChallenge == personal) { changed.run(); return; }
        if (!challengeId.isEmpty()) challengeOperation = UUID.randomUUID().toString();
        try {
            JSONObject body = new JSONObject().put("operationId", challengeOperation);
            if (personal) {
                if (connection.installationId.isEmpty()) {
                    message = "서버 연결 설정에 installationId가 없어 개인 홈 인증을 시작할 수 없습니다";
                    changed.run(); return;
                }
                body.put("installationId", connection.installationId);
            } else {
                if (intervalId.isEmpty()) { message = "기록 시작 후 서버 구간 생성이 완료되면 QR을 발급할 수 있습니다"; changed.run(); return; }
                body.put("intervalId", intervalId);
            }
            send("trip-user/challenges", body, json -> {
                challengeId = json.getString("challengeId");
                challengeExpiresAt = Instant.parse(json.getString("expiresAt")).toEpochMilli();
                personalChallenge = personal;
                JSONObject qr = new JSONObject().put("type", json.getString("type")).put("version", json.getInt("version"))
                        .put("challengeId", challengeId).put("proof", json.getString("proof"));
                if (!personal) qr.put("intervalId", intervalId);
                if (!connection.installationId.isEmpty()) qr.put("installationId", connection.installationId);
                qrPayload = qr.toString(); message = "휴대폰 fuelog 앱으로 QR을 스캔하고 승인해 주세요";
            }, changed);
        } catch (Exception error) { message = "사용자 인증 QR 발급 실패"; changed.run(); }
    }

    public boolean challengeValid() { return !challengeId.isEmpty() && System.currentTimeMillis() < challengeExpiresAt; }

    public void pollPersonal(Runnable changed) {
        if (busy || !personalChallenge || !challengeValid()) return;
        try {
            send("trip-user/session", new JSONObject().put("challengeId", challengeId), json -> {
                if ("ACTIVE".equals(json.getString("status"))) {
                    connection.activatePersonal(json); qrPayload = ""; challengeId = ""; personalChallenge = false;
                    message = "개인 홈 인증 완료";
                } else message = "휴대폰 승인 대기 · " + json.getString("status");
            }, changed);
        } catch (Exception error) { message = "개인 홈 인증 조회 실패"; }
    }

    public void pollEvents(String intervalId, Runnable changed) {
        if (pollingEvents || busy || intervalId.isEmpty()) return;
        pollingEvents = true;
        try {
            connection.request("GET", "trip-user/events?cursor=" + eventCursor, null, "", false, new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                    pollingEvents = false;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        long next = result.getLong("nextCursor");
                        if (next < eventCursor) throw new IllegalArgumentException();
                        eventCursor = next;
                        if (result.getJSONArray("events").length() > 0 && !busy) load(intervalId, changed);
                    } catch (Exception error) { message = "사용자 변경 상태 확인 실패"; }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> pollingEvents = false); }
                public void onFailure(IOException error) { main.post(() -> pollingEvents = false); }
            });
        } catch (RuntimeException error) { pollingEvents = false; }
    }

    // 3. 운행 사용자 선택: 후보 조회 → 선택 유효성 확인 → 서버 검증.
    public void load(String intervalId, Runnable changed) {
        if (intervalId.isEmpty()) { message = "기록 시작 후 서버 구간을 생성해 주세요"; changed.run(); return; }
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

    public Candidate selection() {
        for (Candidate candidate : candidates) if (candidate.id.equals(selected) && candidate.valid()) return candidate;
        return null;
    }

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
                verified.accept(subject, candidate.name, expiresAt, candidate.id);
                qrPayload = ""; challengeId = "";
            }, changed);
        } catch (Exception e) { message = "사용자 확인을 보류했습니다"; changed.run(); }
    }

    // 4. 화면 이탈: 진행 요청과 화면에 남은 사용자 후보를 정리한다.
    public void cancel() { generation++; if (call != null) call.cancel(); busy = false; candidates.clear(); selected = ""; qrPayload = ""; challengeId = ""; }

    // 공통 요청: 취소된 화면의 늦은 응답을 무시한다.
    private void send(String path, JSONObject body, Response response, Runnable changed) {
        busy = true; int version = ++generation;
        try {
            call = connection.requestUser(path, body.toString(), new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse result) { main.post(() -> {
                    if (generation != version) return;
                    busy = false;
                    try { response.accept(new JSONObject(result.getBody())); }
                    catch (Exception e) { connection.apiStatus().invalid("POST", path); candidates.clear(); selected = ""; message = "인증이 만료되었거나 확인할 수 없습니다. 전환을 보류했습니다"; }
                    changed.run();
                }); }
                public void onHttpError(ApiClient.ApiResponse result) {
                    if (result.getStatusCode() == 401 || result.getStatusCode() == 403)
                        main.post(() -> { connection.clearPersonal(); connection.onPersonalInvalid.run(); });
                    fail();
                }
                public void onFailure(IOException error) { fail(); }
                private void fail() { main.post(() -> {
                    if (generation != version) return;
                    busy = false; candidates.clear(); selected = "";
                    message = "서버 검증 불가 · 기존 기록 사용자를 유지합니다"; changed.run();
                }); }
            });
        } catch (RuntimeException e) { busy = false; message = "차량 연결과 서버 설정을 확인해 주세요"; changed.run(); }
    }
}
