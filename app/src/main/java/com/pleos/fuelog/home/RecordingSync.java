package com.pleos.fuelog.home;

import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable ordered writes. Only an acknowledged response removes a job. Access on main thread. */
public final class RecordingSync {
    private final EncryptedJsonStore store;
    private final ConnectionSession connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject state;
    private boolean busy, enabled;
    private long retryAt;
    private long privacyGeneration;
    public Consumer<JSONObject> onChargeResponse = response -> {};
    public Consumer<JSONObject> onFuelResponse = response -> {};
    public String message = "서버 동기화 대기";
    private Runnable changed = () -> {};

    // 1. 앱 진입·복귀: 보관된 전송 큐를 복원하고 재개한다.
    public RecordingSync(android.content.Context context, ConnectionSession connection) throws Exception {
        this.store = new EncryptedJsonStore(new File(context.getNoBackupFilesDir(), "recording-outbox.bin"));
        this.connection = connection;
        state = new JSONObject(store.read());
        if (!state.has("jobs")) state.put("jobs", new JSONArray());
        if (!state.has("intervals")) state.put("intervals", new JSONObject());
    }

    public void resume(Runnable listener) { enabled = true; changed = listener; pump(); }

    // 2. 운행 기록: 시작 → 거리 샘플 → 종료·요약 순서로 전송 큐에 저장한다.
    public void start(String localId, long at, String owner) throws Exception {
        JSONObject intervals = state.getJSONObject("intervals");
        if (intervals.has(localId)) return;
        intervals.put(localId, new JSONObject().put("sequence", 0));
        JSONObject body = new JSONObject().put("localIntervalId", localId).put("startedAt", RecordingPayloads.iso(at)).put("source", "manual_app").put("subjectId", owner.isEmpty() ? JSONObject.NULL : owner);
        // Freeze the start-time proof. A later approval must never reattribute an anonymous interval.
        enqueue("POST", "recording-intervals", body, localId, "", "", "start");
    }

    public void sample(String localId, JSONObject sample, String candidate) throws Exception {
        JSONObject interval = state.getJSONObject("intervals").getJSONObject(localId);
        if (interval.optBoolean("ended")) throw new IllegalStateException("Interval already finished");
        long sequence = interval.optLong("sequence") + 1;
        interval.put("sequence", sequence);
        sample.put("sequence", sequence);
        enqueue("POST", "recording-intervals/{id}/samples", new JSONObject()
                .put("batchId", UUID.randomUUID().toString()).put("samples", new JSONArray().put(sample)),
                localId, candidate, "", "samples");
    }

    public void finish(JSONObject snapshot) throws Exception {
        String localId = snapshot.getString("intervalId");
        JSONObject interval = state.getJSONObject("intervals").optJSONObject(localId);
        if (interval == null || interval.optBoolean("ended")) return;
        interval.put("ended", true);
        enqueueJob("POST", "recording-intervals/{id}/finish", new JSONObject()
                .put("operationId", "finish:" + localId).put("endedAt", RecordingPayloads.iso(snapshot.getLong("endedAt")))
                .put("lastSequence", interval.optLong("sequence")), localId, "", "", "finish");
        enqueueJob("PUT", "recording-intervals/{id}", RecordingPayloads.upload(snapshot), localId,
                snapshot.optString("candidateId"), "", "upload");
        enqueueJob("GET", "recording-intervals/{id}/summary", null, localId,
                snapshot.optString("candidateId"), "", "summary");
        save(); pump();
    }

    // 3. 모바일 확인 요청: 주유 후보와 종료된 운행을 전달한다.
    public void fuel(JSONObject candidate) throws Exception {
        String id = candidate.getString("id");
        JSONArray jobs = state.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            if (job.optString("kind").equals("fuel") && id.equals(job.getJSONObject("body").optString("id"))) return;
        }
        enqueue("POST", "fuel-candidates/sync", new JSONObject(candidate.toString()),
                candidate.getString("intervalId"), "", "", "fuel");
    }

    public void charge(JSONObject candidate, String owner, String localId) throws Exception {
        JSONObject body = new JSONObject().put("owner", owner).put("intervalId", localId);
        for (String key : new String[]{"id", "startAt", "endAt", "estimatedKwh", "basis"}) body.put(key, candidate.get(key));
        JSONArray jobs = state.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i), old = job.optJSONObject("body");
            if (job.optString("kind").equals("charge") && old != null && old.optString("id").equals(body.getString("id"))
                    && old.optLong("endAt") >= body.getLong("endAt")) return;
        }
        enqueue("POST", "charge-candidates/sync", body, localId, "", "", "charge");
    }

    public void deliver(String localId, String candidate) throws Exception {
        JSONObject interval = state.getJSONObject("intervals").getJSONObject(localId);
        if (candidate.isEmpty() && interval.optString("subjectId").isEmpty())
            throw new IllegalStateException("Authenticated owner required");
        if (!interval.optBoolean("ended")) throw new IllegalStateException("Trip not finished");
        if (interval.optBoolean("deliveryQueued")) return;
        interval.put("deliveryQueued", true);
        enqueue("PUT", "mobile-inbox/trips/{id}", new JSONObject().put("operationId", "mobile-inbox:" + localId)
                .put("tripRevision", 1), localId, candidate, "", "deliver");
    }

    // 4. 화면 상태 조회: 서버 구간 ID·요약·전달 여부를 읽는다.
    public String serverId(String localId) {
        JSONObject intervals = state.optJSONObject("intervals");
        JSONObject interval = intervals == null ? null : intervals.optJSONObject(localId);
        return interval == null ? "" : interval.optString("id");
    }

    public JSONObject summary(String localId) {
        JSONObject entry = state.optJSONObject("intervals").optJSONObject(localId);
        return entry == null ? null : entry.optJSONObject("summary");
    }

    public boolean delivered(String localId) {
        JSONObject entry = state.optJSONObject("intervals").optJSONObject(localId);
        return entry != null && entry.optBoolean("delivered");
    }

    public int pendingCount() { return state.optJSONArray("jobs").length(); }

    // 5. 화면 이탈·개인 세션 해제: 전송을 멈추고 개인 요약 캐시를 지운다.
    public void pause() { enabled = false; changed = () -> {}; }

    public void clearPersonalCache() throws Exception {
        privacyGeneration++;
        JSONObject intervals = state.getJSONObject("intervals");
        Iterator<String> ids = intervals.keys();
        while (ids.hasNext()) intervals.getJSONObject(ids.next()).remove("summary");
        save();
    }

    // 공통 전송: 큐 저장 → 순서대로 요청 → 서버 확인 후 제거 또는 재시도.
    private void enqueue(String method, String path, JSONObject body, String localId, String candidate,
                         String session, String kind) throws Exception {
        enqueueJob(method, path, body, localId, candidate, session, kind); save(); pump();
    }

    private void enqueueJob(String method, String path, JSONObject body, String localId, String candidate,
                            String session, String kind) throws Exception {
        state.getJSONArray("jobs").put(new JSONObject().put("method", method).put("path", path).put("body", body == null ? JSONObject.NULL : body)
                .put("localId", localId).put("candidate", candidate).put("session", session).put("kind", kind));
    }

    private void save() throws Exception {
        try { store.write(state.toString()); }
        catch (IOException error) { state = new JSONObject(store.read()); throw error; }
    }

    public void pump() {
        if (!enabled || busy || !connection.linked || System.currentTimeMillis() < retryAt) return;
        try {
            JSONArray jobs = state.getJSONArray("jobs");
            if (jobs.length() == 0) { message = "전송 대기 기록 없음"; return; }
            final int jobIndex = RecordingPayloads.nextJob(jobs, state.getJSONObject("intervals"), System.currentTimeMillis());
            if (jobIndex < 0) return;
            JSONObject job = jobs.getJSONObject(jobIndex);
            String localId = job.getString("localId"), id = serverId(localId);
            String path = job.getString("path");
            if (path.contains("{id}") && id.isEmpty()) throw new IllegalStateException("Server interval missing");
            JSONObject body = job.optJSONObject("body");
            if (body != null) {
                body = new JSONObject(body.toString());
                if (job.getString("kind").equals("upload")) body.put("tripId", id);
                if (job.getString("kind").equals("fuel") || job.getString("kind").equals("charge")) {
                    if (id.isEmpty()) return;
                    body.put("intervalId", id);
                }
            }
            busy = true;
            long requestPrivacy = privacyGeneration;
            connection.requestWithSession(job.getString("method"), path.replace("{id}", id), body,
                    job.optString("candidate"), job.optString("session"), new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                    busy = false;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        JSONObject interval = state.getJSONObject("intervals").getJSONObject(localId);
                        String kind = job.getString("kind");
                        RecordingPayloads.validateResponse(job, result, id);
                        if (kind.equals("start")) {
                            interval.put("id", result.getString("id"));
                            if (!result.isNull("subjectId")) interval.put("subjectId", result.getString("subjectId"));
                        }
                        else if (kind.equals("summary") && requestPrivacy == privacyGeneration) interval.put("summary", result);
                        else if (kind.equals("deliver")) interval.put("delivered", true);
                        else if (kind.equals("fuel")) onFuelResponse.accept(result);
                        else if (kind.equals("charge")) onChargeResponse.accept(result);
                        state.getJSONArray("jobs").remove(jobIndex); save();
                        message = "서버 수신 확인 · 대기 " + pendingCount() + "건";
                        changed.run(); pump();
                    } catch (Exception error) { connection.apiStatus().invalid(job.optString("method"), path); failJob("연결 필요 · 서버 응답 확인 실패 · 기록 보관 중"); }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> {
                    String code = "";
                    try { code = new JSONObject(response.getBody()).optString("code"); } catch (Exception ignored) { }
                    failJob("동기화 보류 · HTTP " + response.getStatusCode() + " " + code + " · 기록 보관 중");
                }); }
                public void onFailure(IOException error) { main.post(() -> failJob("서버 연결 실패 · 기록 보관 중")); }
                private void failJob(String text) {
                    busy = false; message = text;
                    try { job.put("retryAt", System.currentTimeMillis() + ("fuel".equals(job.optString("kind")) ? 60000 : 15000)); save(); }
                    catch (Exception error) { failed("재시도 상태 저장 실패 · 기록 보관 중"); return; }
                    changed.run(); pump();
                }
            });
        } catch (Exception error) { busy = false; failed("동기화 상태 확인 필요 · 기록 보관 중"); }
    }

    private void failed(String text) { busy = false; retryAt = System.currentTimeMillis() + 15000; message = text; changed.run(); }
}
