package com.pleos.fuelog.home;

import ai.pleos.playground.vehicle.Vehicle;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.IOException;
import java.io.File;
import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import java.util.UUID;
import kotlin.Unit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground-only MVP collector. Stopped screens create an explicit gap. */
public final class HomeRecorder {
    private static HomeRecorder instance;
    public final TripState trip = new TripState();
    private final SharedPreferences store;
    private final ConnectionSession connection;
    public String intervalId, owner, ownerName;
    private String candidateId = "";
    public RecordingSync sync;
    private DeviceStatus deviceStatus;
    private long startedAt;
    public String storageError = "";
    private FuelCandidates fuelCandidates;
    private final FuelStationEnrichment stationEnrichment;
    private final ChargeEnrichment chargeEnrichment;
    private final EvRecording ev = new EvRecording();
    private EncryptedJsonStore evStore;
    private JSONObject evIntervals;
    private AaosVehicleDiagnosticService evSource;
    private boolean evPending;
    private int evGeneration;
    private boolean fuelPending;
    private int fuelGeneration;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vehicle vehicle;
    private boolean visible, pending, gearPending, endAtPark;
    private int gearGeneration;
    private int generation;
    private Runnable changed = () -> {};
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            if (sync != null) sync.pump();
            deviceStatus.refresh();
            reconcileUser();
            readGear();
            readFuel();
            readEv();
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
        connection = ConnectionSession.get(context);
        stationEnrichment = new FuelStationEnrichment(context, this, connection);
        chargeEnrichment = new ChargeEnrichment(context, this, connection);
        try {
            sync = new RecordingSync(context, connection);
            sync.onChargeResponse = response -> {
                try { applyChargeResponse(response); chargeEnrichment.synced(response); }
                catch (Exception error) { storageError = "충전 동기화 응답 저장 실패"; }
            };
            sync.onFuelResponse = response -> {
                try { fuelCandidates.applyServer(response, response.getString("id"), response.getString("owner")); saveFuel(); stationEnrichment.synced(response); }
                catch (Exception error) { storageError = "주유 동기화 응답 저장 실패"; }
            };
        }
        catch (Exception e) { storageError = "업로드 큐를 읽지 못했습니다 · 기록 보존을 위해 수집을 보류합니다"; }
        deviceStatus = new DeviceStatus(context, connection);
        try { fuelCandidates = new FuelCandidates(store.getString("fuelCandidates", "{}")); }
        catch (Exception e) { storageError = "연료 감지 기록을 읽지 못했습니다"; }
        intervalId = store.getString("intervalId", UUID.randomUUID().toString());
        owner = store.getString("owner", ""); ownerName = store.getString("ownerName", "");
        candidateId = store.getString("candidateId", "");
        try {
            evStore = new EncryptedJsonStore(new File(context.getNoBackupFilesDir(), "ev-recordings.bin"));
            evIntervals = new JSONObject(evStore.read());
            JSONObject savedEv = evIntervals.optJSONObject(intervalId);
            if (savedEv != null) ev.restore(savedEv);
        } catch (Exception error) { evIntervals = null; storageError = "배터리 기록 복원 실패 · 수집 보류"; }
        trip.active = store.getBoolean("active", false);
        trip.missing = store.getBoolean("missing", false) || trip.active;
        trip.distance = Double.longBitsToDouble(store.getLong("distance", 0));
        trip.sampledAt = store.getLong("sampledAt", 0);
        trip.savedAt = store.getLong("savedAt", 0);
        startedAt = store.getLong("startedAt", 0);
        trip.sampleCount = store.getInt("sampleCount", 0);
        trip.startKm = Double.longBitsToDouble(store.getLong("startKm", Double.doubleToLongBits(Double.NaN)));
        trip.endKm = Double.longBitsToDouble(store.getLong("endKm", Double.doubleToLongBits(Double.NaN)));
        if (trip.active) {
            try {
                JSONArray history = new JSONArray(store.getString("history", "[]"));
                JSONObject recovered = snapshot(true).put("endedAt", Math.max(trip.sampledAt > 0 ? trip.sampledAt : trip.savedAt, ev.snapshot().optLong("batterySampledAt")))
                        .put("endEstimated", true).put("missing", true);
                boolean exists = false;
                for (int i = 0; i < history.length(); i++) if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
                if (sync == null) throw new IOException();
                queueCharges(ev.snapshot());
                sync.finish(withEv(recovered));
                if (!exists) history.put(recovered);
                String next = UUID.randomUUID().toString();
                if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                        .putString("intervalId", next).putLong("distance", 0).putLong("sampledAt", 0).putInt("sampleCount", 0).commit())
                    throw new IOException();
                trip.stop(); intervalId = next; candidateId = ""; trip.distance = 0; trip.sampledAt = 0; trip.sampleCount = 0;
            } catch (Exception e) { storageError = "복구 기록 저장 실패"; }
        }
        save(false);
        try { vehicle = new Vehicle(context.getApplicationContext()); vehicle.initialize(); } catch (RuntimeException ignored) { vehicle = null; }
    }

    // 2. 화면 복귀: 수집과 서버 동기화를 재개한다.
    public void resume(Runnable listener) {
        changed = listener;
        visible = true;
        if (sync != null) sync.resume(listener);
        tick.run();
    }

    // 3. 기록 시작·종료: 기어로 구분한 운행 구간을 저장한다.
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
                reconcileUser();
                if (verifiedOwner().isEmpty()) throw new IllegalStateException("Pairing user required");
                sync.start(intervalId, startedAt, owner);
                candidateId = "";
                ev.reset();
                ev.setPriceReference(evIntervals, owner, startedAt);
                trip.start();
            } catch (Exception e) { storageError = verifiedOwner().isEmpty() ? "페어링 사용자 확인 후 기록을 시작해 주세요" : "업로드 큐 저장 실패 · 기록 시작 보류"; }
        }
        save(); changed.run();
    }

    public void endForSwitch() throws Exception {
        if (!trip.active) return;
        JSONArray history = new JSONArray(store.getString("history", "[]"));
        boolean exists = false;
        for (int i = 0; i < history.length(); i++)
            if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
        stationEnrichment.close();
        chargeEnrichment.close();
        JSONObject ended = snapshot(true);
        if (sync == null) throw new IOException("Upload queue unavailable");
        saveEv();
        queueCharges(ev.snapshot());
        sync.finish(withEv(ended));
        if (!exists) history.put(ended);
        String nextId = UUID.randomUUID().toString();
        if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                .putString("intervalId", nextId).putLong("distance", 0).putLong("sampledAt", 0)
                .putInt("sampleCount", 0).commit())
            throw new IOException("Unable to preserve interval");
        generation++;
        pending = false;
        trip.stop();
        ev.interrupt(); evGeneration++; evPending = false;
        intervalId = nextId;
        candidateId = "";
        trip.distance = 0;
        trip.sampledAt = 0;
        trip.sampleCount = 0;
        save();
    }

    // 4. 사용자 유지: 서버에서 확인한 페어링 사용자만 적용하고 기존 구간의 소유자는 바꾸지 않는다.
    private void reconcileUser() {
        String subject = connection.authenticatedSubject;
        if (subject.isEmpty() || store.getBoolean("disconnectPending", false)) return;
        if (subject.equals(owner) && connection.authenticatedName.equals(ownerName)
                && !store.getBoolean("privacyLocked", false)) return;
        try {
            // Even an anonymous interval ends before a new identity can own future records.
            if (trip.active && !subject.equals(owner)) endForSwitch();
            if (!store.edit().putBoolean("privacyLocked", false).commit()) throw new IOException();
            owner = subject;
            ownerName = connection.authenticatedName;
            candidateId = "";
            save(false);
        } catch (Exception error) { storageError = "페어링 사용자 적용 실패 · 기존 기록 보존 중"; }
    }

    public String verifiedOwner() {
        if (store.getBoolean("privacyLocked", false)) return "";
        return owner.equals(connection.authenticatedSubject) ? owner : "";
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

    private void readGear() {
        if (evSource == null || gearPending || pending || verifiedOwner().isEmpty()) return;
        gearPending = true;
        int request = ++gearGeneration;
        Runnable timeout = () -> {
            if (request != gearGeneration) return;
            gearGeneration++;
            gearPending = false;
            trip.interrupt();
        };
        handler.postDelayed(timeout, 10000);
        evSource.readEvRecording((gear, wh, capacity) -> {
            if (!visible || request != gearGeneration) return;
            handler.removeCallbacks(timeout);
            gearPending = false;
            if (verifiedOwner().isEmpty()) return;
            if (trip.shouldStart(gear)) toggle();
            endAtPark = trip.shouldEnd(gear);
            if (trip.active) read();
        });
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
        if (endAtPark) {
            endAtPark = false;
            try { endForSwitch(); }
            catch (Exception error) { trip.interrupt(); storageError = "운행 저장 실패 · P 상태에서 재시도합니다"; }
        }
        save(); changed.run();
    }

    private void readFuel() {
        if (vehicle == null || fuelPending || verifiedOwner().isEmpty()) return;
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
                    try {
                        JSONObject before = fuelCandidates.latest(subject);
                        fuelCandidates.sample(value, System.currentTimeMillis(), subject, interval); saveFuel();
                        JSONObject after = fuelCandidates.latest(subject);
                        stationEnrichment.observe(after, after != null && (before == null
                                || !before.optString("id").equals(after.optString("id"))));
                    }
                    catch (Exception e) { storageError = "연료 증가 감지 저장 실패"; }
                    changed.run();
                }); return Unit.INSTANCE;
            }, error -> { handler.post(timeout); return Unit.INSTANCE; });
        } catch (RuntimeException e) { timeout.run(); }
    }

    public void configureStationSearch(boolean coordinatesVerified, String fuelType) {
        stationEnrichment.configure(coordinatesVerified, fuelType);
    }
    public boolean locationAssistConsent() {
        String subject = verifiedOwner();
        return !subject.isEmpty() && subject.equals(store.getString("locationAssistConsentOwner", ""))
                && store.getBoolean("locationAssistConsent", false);
    }
    public void setLocationAssistConsent(boolean consent) throws IOException {
        if (consent && verifiedOwner().isEmpty()) throw new IOException("사용자 확인 필요");
        if (!store.edit().putBoolean("locationAssistConsent", consent)
                .putString("locationAssistConsentOwner", verifiedOwner()).commit()) throw new IOException();
        if (!consent) { stationEnrichment.close(); chargeEnrichment.close(); }
    }

    public boolean locationAssistDecisionMade() {
        return !verifiedOwner().isEmpty()
                && verifiedOwner().equals(store.getString("locationAssistConsentOwner", ""));
    }

    private final java.util.Map<String, JSONObject> visibleLocations = new java.util.HashMap<>();

    public void rememberLocation(String id, String subject, String purpose, double latitude, double longitude, long measuredAt) throws Exception {
        if (id.isEmpty() || !subject.equals(verifiedOwner()) || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || measuredAt <= 0) throw new IllegalArgumentException("Invalid display location");
        visibleLocations.put(id, new JSONObject().put("owner", subject).put("purpose", purpose)
                .put("latitude", latitude).put("longitude", longitude).put("measuredAt", measuredAt));
    }

    public JSONObject visibleLocation(String id) {
        if (verifiedOwner().isEmpty()) return null;
        JSONObject value = visibleLocations.get(id);
        if (value == null || !verifiedOwner().equals(value.optString("owner"))) return null;
        try { return new JSONObject(value.toString()); } catch (Exception invalid) { return null; }
    }

    public void setEvSource(AaosVehicleDiagnosticService source) { evSource = source; }

    private void interruptEv() {
        evGeneration++; evPending = false; ev.interrupt();
    }

    private void readEv() {
        if (!trip.active || evSource == null || evPending || evIntervals == null || verifiedOwner().isEmpty()) return;
        evPending = true;
        int request = ++evGeneration;
        String subject = verifiedOwner(), interval = intervalId;
        Runnable timeout = () -> { if (request == evGeneration) interruptEv(); };
        handler.postDelayed(timeout, 10000);
        evSource.readEvRecording((gear, wh, capacity) -> {
            if (!visible || request != evGeneration) return;
            if (!Double.isFinite(wh) && vehicle != null && !gear.isEmpty()) {
                try {
                    vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(config -> {
                        handler.post(() -> {
                            if (!visible || request != evGeneration) return;
                            if (config == null || (config.getAccessType() != ai.pleos.playground.vehicle.api.model.capability.AccessType.READ
                                    && config.getAccessType() != ai.pleos.playground.vehicle.api.model.capability.AccessType.READ_WRITE)) { timeout.run(); return; }
                            try {
                                vehicle.getEvBattery().getEvBatteryStateOfCharge(percent -> {
                                    handler.post(() -> {
                                        if (!visible || request != evGeneration || evSource == null) return;
                                        evSource.readEvRecording((currentGear, ignoredWh, ignoredCapacity) -> {
                                            if (!gear.equals(currentGear)) { timeout.run(); return; }
                                            acceptEv(request, subject, interval, gear,
                                                    EvRecording.kwh(Double.NaN, percent == null ? Double.NaN : percent, capacity),
                                                    "SOC_PERCENT:" + capacity, timeout);
                                        });
                                    });
                                    return Unit.INSTANCE;
                                }, error -> { handler.post(timeout); return Unit.INSTANCE; });
                            } catch (RuntimeException error) { timeout.run(); }
                        }); return Unit.INSTANCE;
                    }, error -> { handler.post(timeout); return Unit.INSTANCE; });
                } catch (RuntimeException error) { timeout.run(); }
            } else acceptEv(request, subject, interval, gear, EvRecording.kwh(wh, Double.NaN, capacity), "AAOS_WH", timeout);
        });
    }

    private void acceptEv(int request, String subject, String interval, String gear, double kwh,
                          String basis, Runnable timeout) {
        if (!visible || request != evGeneration) return;
        handler.removeCallbacks(timeout); evPending = false;
        if (!trip.active || !subject.equals(verifiedOwner()) || !interval.equals(intervalId)) { interruptEv(); return; }
        try {
            JSONObject prior = latestCharge(ev.snapshot());
            if (gear.isEmpty()) ev.interrupt();
            else ev.sample(gear, kwh, basis, System.currentTimeMillis());
            JSONObject latest = latestCharge(ev.snapshot());
            if (latest != null) {
                chargeEnrichment.observe(latest, prior == null || !prior.optString("id").equals(latest.optString("id")));
            }
            saveEv();
            queueCharges(ev.snapshot());
            changed.run();
        } catch (Exception error) { storageError = "배터리 기록 저장 실패"; interruptEv(); }
    }

    private static JSONObject latestCharge(JSONObject data) {
        JSONArray entries = data.optJSONArray("chargeCandidates");
        return entries == null || entries.length() == 0 ? null : entries.optJSONObject(entries.length() - 1);
    }

    public JSONObject currentCharge(String id) {
        if (!trip.active) return null;
        try {
            JSONArray entries = ev.snapshot().optJSONArray("chargeCandidates");
            if (entries != null) for (int i = 0; i < entries.length(); i++)
                if (id.equals(entries.getJSONObject(i).optString("id"))) return entries.getJSONObject(i);
        } catch (Exception ignored) { }
        return null;
    }

    private void queueCharges(JSONObject data) throws Exception {
        if (sync == null || owner.isEmpty()) return;
        JSONArray entries = data.optJSONArray("chargeCandidates");
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry.optString("status").equals("CANDIDATE") && entry.optLong("syncedEndAt") < entry.optLong("endAt"))
                sync.charge(entry, owner, intervalId);
        }
    }

    public void applyChargeResponse(JSONObject response) throws Exception {
        String id = response.getString("id");
        // The durable sync path already validates id+owner against the queued request.
        if (currentCharge(id) != null) {
            if (!owner.equals(response.getString("owner"))) throw new IllegalArgumentException();
            if (ev.applyResponse(response)) saveEv();
        } else if (evIntervals != null) {
            java.util.Iterator<String> ids = evIntervals.keys();
            while (ids.hasNext()) {
                JSONArray entries = evIntervals.getJSONObject(ids.next()).optJSONArray("chargeCandidates");
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if (id.equals(entry.optString("id")) && EvRecording.applyResponse(entry, response))
                        evStore.write(evIntervals.toString());
                }
            }
        }
        changed.run();
    }

    private void saveEv() throws Exception {
        if (evIntervals == null) throw new IOException("Battery store unavailable");
        evIntervals.put(intervalId, ev.snapshot().put("owner", owner));
        evStore.write(evIntervals.toString());
    }

    private JSONObject withEv(JSONObject snapshot) throws Exception {
        JSONObject merged = new JSONObject(snapshot.toString());
        JSONObject energy = evIntervals == null ? null : evIntervals.optJSONObject(snapshot.optString("intervalId"));
        if (energy != null) {
            java.util.Iterator<String> keys = energy.keys();
            while (keys.hasNext()) { String key = keys.next(); merged.put(key, energy.get(key)); }
        }
        JSONObject remote = sync == null ? null : sync.summary(snapshot.optString("intervalId"));
        JSONObject quality = remote == null ? null : remote.optJSONObject("quality");
        if (quality != null && quality.optBoolean("missing")) merged.put("missing", true);
        return merged;
    }

    // 6. 운행 결과 확인: 로컬·서버 요약과 모바일 전달 상태를 조회한다.
    public TripSummary summary() {
        if (store.getBoolean("privacyLocked", false) || verifiedOwner().isEmpty()) return null;
        try {
            if (trip.active) return new TripSummary(withEv(snapshot(false)));
            JSONArray history = new JSONArray(store.getString("history", "[]"));
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject entry = history.getJSONObject(i);
                String subject = entry.optString("owner");
                if (subject.equals(owner) && (subject.isEmpty() || subject.equals(verifiedOwner())))
                    return new TripSummary(withEv(entry));
            }
        } catch (Exception ignored) { }
        return null;
    }

    public void applyTripPriceReference(String id, JSONObject response) throws Exception {
        TripSummary current = summary();
        if (current == null || !id.equals(current.data.getString("intervalId"))
                || !id.equals(response.getString("tripId")) || !verifiedOwner().equals(response.getString("owner")))
            throw new IllegalArgumentException("Trip reference scope mismatch");
        java.time.Instant.parse(response.getString("fetchedAt"));
        JSONObject energy = evIntervals.optJSONObject(id);
        if (energy == null) throw new IllegalStateException("Battery recording unavailable");
        energy.put("currentPriceReference", response);
        evStore.write(evIntervals.toString());
        changed.run();
    }

    public JSONObject serverSummary(String id) { return sync == null ? null : sync.summary(id); }

    public String serverIntervalId() { return sync == null ? "" : sync.serverId(intervalId); }

    public String syncMessage() { return sync == null ? storageError : sync.message; }

    public void enqueueMobile(String id) throws Exception {
        TripSummary summary = summary();
        if (summary == null || !id.equals(summary.data.getString("intervalId")) || trip.active)
            throw new IllegalStateException();
        if (summary.data.optString("owner").isEmpty() || sync == null) throw new IllegalStateException("Authenticated owner required");
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
        visibleLocations.clear();
        visible = false;
        gearGeneration++; gearPending = false; endAtPark = false;
        evSource = null;
        stationEnrichment.close();
        chargeEnrichment.close();
        interruptEv();
        try { if (trip.active) saveEv(); } catch (Exception error) { storageError = "배터리 중단 상태 저장 실패"; }
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
        visibleLocations.clear();
        stationEnrichment.close();
        chargeEnrichment.close();
        interruptEv();
        try { if (!owner.isEmpty()) endForSwitch(); }
        catch (Exception error) { storageError = "개인 기록 보존 실패 · 화면 잠금 유지"; }
        store.edit().putBoolean("privacyLocked", true).commit();
        try { if (sync != null) sync.clearPersonalCache(); }
        catch (Exception error) { storageError = "개인 캐시 삭제 실패 · 화면 잠금 유지"; }
        ownerName = "";
        fuelGeneration++; fuelPending = false;
        save(); changed.run();
    }

    public void lockPersonalSession() throws Exception {
        stationEnrichment.close();
        chargeEnrichment.close();
        interruptEv();
        if (!store.edit().putBoolean("privacyLocked", true).putBoolean("disconnectPending", true).commit())
            throw new IOException();
        // Archive under the original owner before clearing credentials; never reattribute queued records.
        endForSwitch();
        owner = "";
        ownerName = "";
        candidateId = "";
        fuelGeneration++;
        fuelPending = false;
        if (!store.edit().putString("owner", "").putString("ownerName", "").commit())
            throw new IOException();
    }

    public void remoteDisconnected() throws Exception {
        if (!store.edit().putBoolean("disconnectPending", false).commit()) throw new IOException();
    }

    // 공통 저장: 운행 스냅샷과 연료 후보를 각각 저장한다.
    private JSONObject snapshot(boolean ended) throws Exception {
        return new JSONObject().put("intervalId", intervalId).put("owner", owner)
                .put("candidateId", candidateId).put("startedAt", startedAt).put("endedAt", ended ? System.currentTimeMillis() : 0)
                .put("startOdometerKm", Double.isFinite(trip.startKm) ? trip.startKm : JSONObject.NULL)
                .put("endOdometerKm", ended && Double.isFinite(trip.endKm) ? trip.endKm : JSONObject.NULL)
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

                .putBoolean("active", trip.active).putBoolean("missing", trip.missing)
                .putLong("startKm", Double.doubleToLongBits(trip.startKm))
                .putLong("endKm", Double.doubleToLongBits(trip.endKm))
                .putLong("distance", Double.doubleToLongBits(trip.distance))
                .putLong("sampledAt", trip.sampledAt).putLong("savedAt", savedAt).commit();
        if (saved) trip.savedAt = savedAt;
        else storageError = "기록 저장 실패 · 마지막 저장 시각을 유지합니다";
    }
}
