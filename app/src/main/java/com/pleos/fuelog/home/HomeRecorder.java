package com.pleos.fuelog.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.vehicle.Vehicle;
import kotlin.Unit;

/** Foreground-only MVP collector. Stopped screens create an explicit gap. */
public final class HomeRecorder {
    public final TripState trip = new TripState();
    private final SharedPreferences store;
    public String intervalId, owner, ownerName;
    private long ownerExpiresAt;
    private long startedAt;
    private static final java.util.Set<String> seen = new java.util.HashSet<>();
    public String storageError = "";
    private FuelCandidates fuelCandidates;
    private boolean fuelPending;
    private int fuelGeneration;
    public String verifiedOwner() { if (store.getBoolean("privacyLocked", false)) return ""; return ownerExpiresAt > System.currentTimeMillis() ? owner : ""; }
    public org.json.JSONObject fuelCandidate() {
        try { return fuelCandidates == null ? null : fuelCandidates.latest(verifiedOwner()); }
        catch (Exception e) { return null; }
    }
    private void saveFuel() throws Exception {
        if (!store.edit().putString("fuelCandidates", fuelCandidates.serialize()).commit()) throw new java.io.IOException();
    }
    public void queueFuel(String id) throws Exception { fuelCandidates.queue(id, verifiedOwner()); saveFuel(); }
    public void updateFuel(org.json.JSONObject response, String id, String subject) throws Exception {
        if (!subject.equals(verifiedOwner())) throw new IllegalStateException();
        fuelCandidates.applyServer(response, id, subject); saveFuel();
    }
    private void readFuel() {
        if (vehicle == null || fuelPending) return;
        fuelPending = true;
        int request = ++fuelGeneration;
        String subject = verifiedOwner(), interval = intervalId;
        Runnable timeout = () -> { if (request == fuelGeneration) { fuelGeneration++; fuelPending = false; } };
        handler.postDelayed(timeout, 10000);
        try {
            vehicle.getFuel().getFuelPercent(value -> {
                handler.post(() -> {
                    if (!visible || request != fuelGeneration) return;
                    handler.removeCallbacks(timeout); fuelPending = false;
                    if (value == null || !subject.equals(verifiedOwner()) || !interval.equals(intervalId)) return;
                    try { fuelCandidates.sample(value, System.currentTimeMillis(), subject, interval); saveFuel(); }
                    catch (Exception e) { storageError = "연료 증가 감지 저장 실패"; }
                    changed.run();
                }); return Unit.INSTANCE;
            }, error -> { handler.post(timeout); return Unit.INSTANCE; });
        } catch (RuntimeException e) { timeout.run(); }
    }
    public TripSummary summary() {
        if (store.getBoolean("privacyLocked", false)) return null;
        try {
            if (trip.active) return new TripSummary(snapshot(false));
            org.json.JSONArray history = new org.json.JSONArray(store.getString("history", "[]"));
            for (int i = history.length() - 1; i >= 0; i--) {
                org.json.JSONObject entry = history.getJSONObject(i);
                String subject = entry.optString("owner");
                if (subject.equals(owner) && (subject.isEmpty() || ownerExpiresAt > System.currentTimeMillis()))
                    return new TripSummary(entry);
            }
        } catch (Exception ignored) { }
        return null;
    }
    private org.json.JSONObject snapshot(boolean ended) throws Exception {
        return new org.json.JSONObject().put("intervalId", intervalId).put("owner", owner)
                .put("startedAt", startedAt).put("endedAt", ended ? System.currentTimeMillis() : 0)
                .put("distanceKm", trip.distance).put("distanceValid", trip.sampleCount >= 2)
                .put("sampledAt", trip.sampledAt).put("missing", trip.missing)
                .put("synced", false).put("savedAt", trip.savedAt);
    }
    public void enqueueMobile(String id) throws Exception {
        TripSummary summary = summary();
        if (summary == null || !id.equals(summary.data.getString("intervalId")) || trip.active)
            throw new IllegalStateException();
        org.json.JSONObject queue = new org.json.JSONObject(store.getString("mobileQueue", "{}"));
        queue.put(id, new org.json.JSONObject().put("tripId", id).put("status", "PENDING")
                .put("operationId", "mobile-inbox:" + id));
        if (!store.edit().putString("mobileQueue", queue.toString()).commit()) throw new java.io.IOException();
    }
    public boolean mobileQueued(String id) {
        try { return new org.json.JSONObject(store.getString("mobileQueue", "{}")).has(id); }
        catch (Exception e) { return false; }
    }
    public String displayUser() { return verifiedOwner().isEmpty() ? "사용자 확인 필요" : ownerName; }
    public void assignUser(String subject, String name, long expiresAt) throws Exception {
        if (store.getBoolean("disconnectPending", false)) throw new IllegalStateException("Disconnect pending");
        if (trip.active) throw new IllegalStateException("End interval before switching");
        if (!store.edit().putBoolean("privacyLocked", false).commit()) throw new java.io.IOException();
        owner = subject; ownerName = name; ownerExpiresAt = expiresAt;
        save();
    }
    public void lockPersonalSession() throws Exception {
        if (!store.edit().putBoolean("privacyLocked", true).putBoolean("disconnectPending", true).commit())
            throw new java.io.IOException();
        // Archive under the original owner before clearing credentials; never reattribute queued records.
        endForSwitch();
        owner = ""; ownerName = ""; ownerExpiresAt = 0;
        fuelGeneration++; fuelPending = false;
        if (!store.edit().putString("owner", "").putString("ownerName", "").putLong("ownerExpiresAt", 0).commit())
            throw new java.io.IOException();
    }
    public void remoteDisconnected() throws Exception {
        if (!store.edit().putBoolean("disconnectPending", false).commit()) throw new java.io.IOException();
    }
    public void endForSwitch() throws Exception {
        if (!trip.active) return;
        org.json.JSONArray history = new org.json.JSONArray(store.getString("history", "[]"));
        boolean exists = false;
        for (int i = 0; i < history.length(); i++)
            if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
        if (!exists) history.put(snapshot(true));
        String nextId = java.util.UUID.randomUUID().toString();
        if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                .putString("intervalId", nextId).putString("owner", "").putString("ownerName", "")
                .putLong("ownerExpiresAt", 0).putLong("distance", 0).putLong("sampledAt", 0)
                .putInt("sampleCount", 0).commit())
            throw new java.io.IOException("Unable to preserve interval");
        generation++; pending = false; trip.stop();
        intervalId = nextId;
        owner = ""; ownerName = ""; ownerExpiresAt = 0;
        trip.distance = 0; trip.sampledAt = 0; trip.sampleCount = 0; save();
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vehicle vehicle;
    private boolean visible, pending;
    private int generation;
    private Runnable changed = () -> {};
    public HomeRecorder(Context context) {
        store = context.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
        try { fuelCandidates = new FuelCandidates(store.getString("fuelCandidates", "{}")); }
        catch (Exception e) { storageError = "연료 감지 기록을 읽지 못했습니다"; }
        intervalId = store.getString("intervalId", java.util.UUID.randomUUID().toString());
        owner = store.getString("owner", ""); ownerName = store.getString("ownerName", "");
        ownerExpiresAt = store.getLong("ownerExpiresAt", 0);
        trip.active = store.getBoolean("active", false);
        trip.missing = store.getBoolean("missing", false) || trip.active;
        trip.distance = Double.longBitsToDouble(store.getLong("distance", 0));
        trip.sampledAt = store.getLong("sampledAt", 0);
        trip.savedAt = store.getLong("savedAt", 0);
        startedAt = store.getLong("startedAt", 0);
        trip.sampleCount = store.getInt("sampleCount", 0);
        if (trip.active && seen.add(intervalId)) {
            try {
                org.json.JSONArray history = new org.json.JSONArray(store.getString("history", "[]"));
                org.json.JSONObject recovered = snapshot(true).put("endedAt", trip.sampledAt > 0 ? trip.sampledAt : trip.savedAt)
                        .put("endEstimated", true).put("missing", true);
                boolean exists = false;
                for (int i = 0; i < history.length(); i++) if (intervalId.equals(history.getJSONObject(i).optString("intervalId"))) exists = true;
                if (!exists) history.put(recovered);
                String next = java.util.UUID.randomUUID().toString();
                if (!store.edit().putString("history", history.toString()).putBoolean("active", false)
                        .putString("intervalId", next).putLong("distance", 0).putLong("sampledAt", 0).putInt("sampleCount", 0).commit())
                    throw new java.io.IOException();
                trip.stop(); intervalId = next; trip.distance = 0; trip.sampledAt = 0; trip.sampleCount = 0;
            } catch (Exception e) { storageError = "복구 기록 저장 실패"; }
        }
        save();
        try { vehicle = new Vehicle(context.getApplicationContext()); } catch (RuntimeException ignored) { }
    }
    public void resume(Runnable listener) { changed = listener; visible = true; tick.run(); }
    public void pause() {
        visible = false; fuelGeneration++; fuelPending = false; generation++; pending = false; handler.removeCallbacksAndMessages(null);
        trip.interrupt(); save(); changed = () -> {};
    }
    public void toggle() {
        generation++; pending = false;
        if (trip.active) {
            String previousOwner = owner, previousName = ownerName;
            long previousExpiry = ownerExpiresAt;
            try { endForSwitch(); owner = previousOwner; ownerName = previousName; ownerExpiresAt = previousExpiry; }
            catch (Exception e) { trip.interrupt(); storageError = "운행 저장 실패 · 다시 종료해 주세요"; }
        } else {
            if (ownerExpiresAt <= System.currentTimeMillis()) { owner = ""; ownerName = ""; }
            trip.start(); startedAt = System.currentTimeMillis(); seen.add(intervalId);
        }
        save(); changed.run();
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            if (trip.active && !pending) read();
            readFuel();
            changed.run();
            handler.postDelayed(this, 3000);
        }
    };
    private void read() {
        if (vehicle == null) { trip.interrupt(); save(); return; }
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
    private void finish(int request, Float km) {
        if (!visible || request != generation || !pending) return;
        pending = false;
        if (km == null) trip.interrupt(); else trip.sample(km, System.currentTimeMillis());
        save(); changed.run();
    }
    private void save() {
        trip.savedAt = System.currentTimeMillis();
        store.edit().putLong("startedAt", startedAt).putInt("sampleCount", trip.sampleCount).putString("intervalId", intervalId).putString("owner", owner)
                .putString("ownerName", ownerName).putLong("ownerExpiresAt", ownerExpiresAt).putBoolean("active", trip.active).putBoolean("missing", trip.missing)
                .putLong("distance", Double.doubleToLongBits(trip.distance))
                .putLong("sampledAt", trip.sampledAt).putLong("savedAt", trip.savedAt).apply();
    }
}
