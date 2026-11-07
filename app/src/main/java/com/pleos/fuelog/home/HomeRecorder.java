package com.pleos.fuelog.home;

import ai.pleos.playground.vehicle.Vehicle;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.IOException;
import java.util.UUID;
import kotlin.Unit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground-only MVP collector. Stopped screens create an explicit gap. */
public final class HomeRecorder {
    private static HomeRecorder instance;
    public final TripState trip = new TripState();
    private final SharedPreferences store;
    public String intervalId, owner, ownerName;
    private String candidateId = "";
    public RecordingSync sync;
    private DeviceStatus deviceStatus;
    private long ownerExpiresAt;
    private long startedAt;
    public String storageError = "";
    private FuelCandidates fuelCandidates;
    private boolean fuelPending;
    private int fuelGeneration;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vehicle vehicle;
    private boolean visible, pending;
    private int generation;
    private Runnable changed = () -> {};
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            if (sync != null) sync.pump();
            deviceStatus.refresh();
            if (trip.active && !pending) read();
            readFuel();
            changed.run();
            handler.postDelayed(this, 3000);
        }
    };

    // 1. 앱 진입: 이전 기록·전송 큐를 복원하고 중단된 구간을 보존한다.
    public static synchronized HomeRecorder get(Context context) {
        if (instance == null) instance = new HomeRecorder(context.getApplicationContext());
        return instance;
    }

    HomeRecorder(Context context) {
        store = context.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
        ConnectionSession connection = ConnectionSession.get(context);
        try {
            sync = new RecordingSync(context, connection);
            sync.onFuelResponse = response -> {
                try { fuelCandidates.applyServer(response, response.getString("id"), response.getString("owner")); saveFuel(); }
                catch (Exception error) { storageError = "주유 동기화 응답 저장 실패"; }
            };
        }
        catch (Exception e) { storageError = "업로드 큐를 읽지 못했습니다 · 기록 보존을 위해 수집을 보류합니다"; }
        deviceStatus = new DeviceStatus(context, connection);
        try { fuelCandidates = new FuelCandidates(store.getString("fuelCandidates", "{}")); }
        catch (Exception e) { storageError = "연료 감지 기록을 읽지 못했습니다"; }
        intervalId = store.getString("intervalId", UUID.randomUUID().toString());
        owner = store.getString("owner", ""); ownerName = store.getString("ownerName", "");
        ownerExpiresAt = store.getLong("ownerExpiresAt", 0);
        candidateId = store.getString("candidateId", "");
        trip.active = store.getBoolean("active", false);
        trip.missing = store.getBoolean("missing", false) || trip.active;
        trip.distance = Double.longBitsToDouble(store.getLong("distance", 0));
        trip.sampledAt = store.getLong("sampledAt", 0);
        trip.savedAt = store.getLong("savedAt", 0);
        startedAt = store.getLong("startedAt", 0);
        trip.sampleCount = store.getInt("sampleCount", 0);
        if (trip.active) {
            try {
                JSONArray history = new JSONArray(store.getString("history", "[]"));
                JSONObject recovered = snapshot(true).put("endedAt", trip.sampledAt > 0 ? trip.sampledAt : trip.savedAt)
                        .put("endEstimated", true).put("missing", true);
                boolean exists = false;
                for (int i = 0; i < history.length(); i++) if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
                if (sync == null) throw new IOException();
                sync.finish(recovered);
                if (!exists) history.put(recovered);
                String next = UUID.randomUUID().toString();
                if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                        .putString("intervalId", next).putLong("distance", 0).putLong("sampledAt", 0).putInt("sampleCount", 0).commit())
                    throw new IOException();
                trip.stop(); intervalId = next; candidateId = ""; trip.distance = 0; trip.sampledAt = 0; trip.sampleCount = 0;
            } catch (Exception e) { storageError = "복구 기록 저장 실패"; }
        }
        save(false);
        try { vehicle = new Vehicle(context.getApplicationContext()); } catch (RuntimeException ignored) { }
    }

    // 2. 화면 복귀: 수집과 서버 동기화를 재개한다.
    public void resume(Runnable listener) {
        changed = listener;
        visible = true;
        if (sync != null) sync.resume(listener);
        tick.run();
    }

    // 3. 기록 시작·종료: 사용자가 지정한 앱 기록 구간을 저장한다.
    public void toggle() {
        generation++;
        pending = false;
        if (trip.active) {
            try { endForSwitch(); }
            catch (Exception e) { trip.interrupt(); storageError = "운행 저장 실패 · 다시 종료해 주세요"; }
        } else {
            try {
                if (sync == null) throw new IOException();
                startedAt = System.currentTimeMillis();
                sync.start(intervalId, startedAt);
                owner = "";
                ownerName = "";
                ownerExpiresAt = 0;
                candidateId = "";
                trip.start();
            } catch (Exception e) { storageError = "업로드 큐 저장 실패 · 기록 시작 보류"; }
        }
        save(); changed.run();
    }

    public void endForSwitch() throws Exception {
        if (!trip.active) return;
        JSONArray history = new JSONArray(store.getString("history", "[]"));
        boolean exists = false;
        for (int i = 0; i < history.length(); i++)
            if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
        JSONObject ended = snapshot(true);
        if (sync == null) throw new IOException("Upload queue unavailable");
        sync.finish(ended);
        if (!exists) history.put(ended);
        String nextId = UUID.randomUUID().toString();
        if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                .putString("intervalId", nextId).putString("owner", "").putString("ownerName", "")
                .putLong("ownerExpiresAt", 0).putLong("distance", 0).putLong("sampledAt", 0)
                .putInt("sampleCount", 0).commit())
            throw new IOException("Unable to preserve interval");
        generation++;
        pending = false;
        trip.stop();
        intervalId = nextId;
        candidateId = "";
        trip.distance = 0;
        trip.sampledAt = 0;
        trip.sampleCount = 0;
        save();
    }

    // 4. 사용자 확인: 서버에서 검증한 사용자만 현재 구간에 연결한다.
    public void assignUser(String subject, String name, long expiresAt, String verifiedCandidate) throws Exception {
        if (store.getBoolean("disconnectPending", false)) throw new IllegalStateException("Disconnect pending");
        if (!trip.active || serverIntervalId().isEmpty()) throw new IllegalStateException("Start interval before verifying");
        if (!owner.isEmpty() && !owner.equals(subject)) throw new IllegalStateException("End interval before switching users");
        if (!store.edit().putBoolean("privacyLocked", false).commit()) throw new IOException();
        owner = subject;
        ownerName = name;
        ownerExpiresAt = expiresAt;
        candidateId = verifiedCandidate;
        save();
    }

    public String verifiedOwner() {
        if (store.getBoolean("privacyLocked", false)) return "";
        return ownerExpiresAt > System.currentTimeMillis() ? owner : "";
    }

    public String displayUser() { return verifiedOwner().isEmpty() ? "사용자 확인 필요" : ownerName; }

    // 5. 기록 중 수집: 누적거리 차이와 연료 증가를 저장한다.
    private void read() {
        if (vehicle == null) { finishUnavailable(); return; }
        pending = true;
        int request = ++generation;
        Runnable timeout = () -> finish(request, null);
        handler.postDelayed(timeout, 10000);
        try {
            vehicle.getOdometer().getCurrentDistanceDriven(value -> {
                handler.post(() -> { handler.removeCallbacks(timeout); finish(request, value); }); return Unit.INSTANCE;
            }, error -> {
                handler.post(() -> { handler.removeCallbacks(timeout); finish(request, null); }); return Unit.INSTANCE;
            });
        } catch (RuntimeException e) { handler.removeCallbacks(timeout); finish(request, null); }
    }

    private void finishUnavailable() { pending = true; finish(++generation, null); }

    private void finish(int request, Float km) {
        if (!visible || request != generation || !pending) return;
        pending = false;
        long now = System.currentTimeMillis();
        double previousKm = trip.previousKm();
        if (km == null) trip.interrupt(); else trip.sample(km, now);
        try {
            JSONObject sample = RecordingPayloads.sample(km, previousKm, now);
            if (sync == null) throw new IOException();
            sync.sample(intervalId, sample, verifiedOwner().isEmpty() ? "" : candidateId);
            deviceStatus.odometer(km, now);
        } catch (Exception error) { trip.interrupt(); storageError = "샘플 업로드 큐 저장 실패"; }
        save(); changed.run();
    }

    private void readFuel() {
        if (vehicle == null || fuelPending) return;
        fuelPending = true;
        int request = ++fuelGeneration;
        String subject = verifiedOwner(), interval = intervalId;
        if (!trip.active || serverIntervalId().isEmpty()) { fuelPending = false; return; }
        Runnable timeout = () -> {
            if (request != fuelGeneration) return;
            fuelGeneration++;
            fuelPending = false;
        };
        handler.postDelayed(timeout, 10000);
        try {
            vehicle.getFuel().getFuelPercent(value -> {
                handler.post(() -> {
                    if (!visible || request != fuelGeneration) return;
                    handler.removeCallbacks(timeout); fuelPending = false;
                    deviceStatus.fuel(value != null);
                    if (value == null || !subject.equals(verifiedOwner()) || !interval.equals(intervalId)) return;
                    try { fuelCandidates.sample(value, System.currentTimeMillis(), subject, interval); saveFuel(); }
                    catch (Exception e) { storageError = "연료 증가 감지 저장 실패"; }
                    changed.run();
                }); return Unit.INSTANCE;
            }, error -> { handler.post(timeout); return Unit.INSTANCE; });
        } catch (RuntimeException e) { timeout.run(); }
    }

    // 6. 운행 결과 확인: 로컬·서버 요약과 모바일 전달 상태를 조회한다.
    public TripSummary summary() {
        if (store.getBoolean("privacyLocked", false)) return null;
        try {
            if (trip.active) return new TripSummary(snapshot(false));
            JSONArray history = new JSONArray(store.getString("history", "[]"));
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject entry = history.getJSONObject(i);
                String subject = entry.optString("owner");
                if (subject.equals(owner) && (subject.isEmpty() || ownerExpiresAt > System.currentTimeMillis()))
                    return new TripSummary(entry);
            }
        } catch (Exception ignored) { }
        return null;
    }

    public JSONObject serverSummary(String id) { return sync == null ? null : sync.summary(id); }

    public String serverIntervalId() { return sync == null ? "" : sync.serverId(intervalId); }

    public String syncMessage() { return sync == null ? storageError : sync.message; }

    public void enqueueMobile(String id) throws Exception {
        TripSummary summary = summary();
        if (summary == null || !id.equals(summary.data.getString("intervalId")) || trip.active)
            throw new IllegalStateException();
        if (summary.data.optString("candidateId").isEmpty() || sync == null) throw new IllegalStateException("Verified candidate required");
        sync.deliver(id, summary.data.getString("candidateId"));
        JSONObject queue = new JSONObject(store.getString("mobileQueue", "{}"));
        queue.put(id, new JSONObject().put("tripId", id).put("status", "PENDING")
                .put("operationId", "mobile-inbox:" + id));
        if (!store.edit().putString("mobileQueue", queue.toString()).commit()) throw new IOException();
    }

    public boolean mobileQueued(String id) {
        try { return new JSONObject(store.getString("mobileQueue", "{}")).has(id); }
        catch (Exception e) { return false; }
    }

    public boolean mobileDelivered(String id) { return sync != null && sync.delivered(id); }

    // 7. 주유 후보 확인: 감지 결과를 조회하고 모바일 확인 요청을 보관한다.
    public JSONObject fuelCandidate() {
        try { return fuelCandidates == null ? null : fuelCandidates.latest(verifiedOwner()); }
        catch (Exception e) { return null; }
    }

    public void queueFuel(String id) throws Exception {
        if (sync == null) throw new IOException();
        JSONObject candidate = fuelCandidate();
        if (candidate == null || !id.equals(candidate.optString("id"))) throw new IllegalArgumentException();
        sync.fuel(candidate.put("queued", true));
        fuelCandidates.queue(id, verifiedOwner()); saveFuel();
    }

    public void updateFuel(JSONObject response, String id, String subject) throws Exception {
        if (!subject.equals(verifiedOwner())) throw new IllegalStateException();
        fuelCandidates.applyServer(response, id, subject); saveFuel();
    }

    // 8. 이탈·연결 해제: 수집을 중단하고 원래 사용자 기록을 보존한다.
    public void pause() {
        visible = false;
        fuelGeneration++;
        fuelPending = false;
        generation++;
        pending = false;
        handler.removeCallbacksAndMessages(null);
        trip.interrupt();
        save(trip.active);
        if (sync != null) sync.pause();
        changed = () -> {};
    }

    public void clearPersonalCache() {
        try { if (!owner.isEmpty()) endForSwitch(); }
        catch (Exception error) { storageError = "개인 기록 보존 실패 · 화면 잠금 유지"; }
        store.edit().putBoolean("privacyLocked", true).commit();
        try { if (sync != null) sync.clearPersonalCache(); }
        catch (Exception error) { storageError = "개인 캐시 삭제 실패 · 화면 잠금 유지"; }
        ownerName = ""; ownerExpiresAt = 0;
        fuelGeneration++; fuelPending = false;
        save(); changed.run();
    }

    public void lockPersonalSession() throws Exception {
        if (!store.edit().putBoolean("privacyLocked", true).putBoolean("disconnectPending", true).commit())
            throw new IOException();
        // Archive under the original owner before clearing credentials; never reattribute queued records.
        endForSwitch();
        owner = "";
        ownerName = "";
        ownerExpiresAt = 0;
        candidateId = "";
        fuelGeneration++;
        fuelPending = false;
        if (!store.edit().putString("owner", "").putString("ownerName", "").putLong("ownerExpiresAt", 0).commit())
            throw new IOException();
    }

    public void remoteDisconnected() throws Exception {
        if (!store.edit().putBoolean("disconnectPending", false).commit()) throw new IOException();
    }

    // 공통 저장: 운행 스냅샷과 연료 후보를 각각 저장한다.
    private JSONObject snapshot(boolean ended) throws Exception {
        return new JSONObject().put("intervalId", intervalId).put("owner", owner)
                .put("candidateId", candidateId).put("startedAt", startedAt).put("endedAt", ended ? System.currentTimeMillis() : 0)
                .put("distanceKm", trip.distance).put("distanceValid", trip.sampleCount >= 2)
                .put("sampledAt", trip.sampledAt).put("missing", trip.missing)
                .put("synced", false).put("savedAt", trip.savedAt);
    }

    private void saveFuel() throws Exception {
        if (!store.edit().putString("fuelCandidates", fuelCandidates.serialize()).commit()) throw new IOException();
    }

    private void save() { save(true); }

    private void save(boolean recordingChanged) {
        long savedAt = recordingChanged ? System.currentTimeMillis() : trip.savedAt;
        boolean saved = store.edit()
                .putString("candidateId", candidateId).putLong("startedAt", startedAt)
                .putInt("sampleCount", trip.sampleCount).putString("intervalId", intervalId)
                .putString("owner", owner).putString("ownerName", ownerName)
                .putLong("ownerExpiresAt", ownerExpiresAt)
                .putBoolean("active", trip.active).putBoolean("missing", trip.missing)
                .putLong("distance", Double.doubleToLongBits(trip.distance))
                .putLong("sampledAt", trip.sampledAt).putLong("savedAt", savedAt).commit();
        if (saved) trip.savedAt = savedAt;
        else storageError = "기록 저장 실패 · 마지막 저장 시각을 유지합니다";
    }
}
