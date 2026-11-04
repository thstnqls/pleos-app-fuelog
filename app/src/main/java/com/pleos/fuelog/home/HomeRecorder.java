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
    public String displayUser() { return ownerExpiresAt > System.currentTimeMillis() ? ownerName : "사용자 확인 필요"; }
    public void assignUser(String subject, String name, long expiresAt) throws Exception {
        if (trip.active) throw new IllegalStateException("End interval before switching");
        owner = subject; ownerName = name; ownerExpiresAt = expiresAt;
        save();
    }
    public void endForSwitch() throws Exception {
        if (!trip.active) return;
        org.json.JSONArray history = new org.json.JSONArray(store.getString("history", "[]"));
        history.put(new org.json.JSONObject().put("intervalId", intervalId).put("owner", owner)
                .put("distanceKm", trip.distance).put("sampledAt", trip.sampledAt)
                .put("missing", trip.missing).put("endedAt", System.currentTimeMillis()));
        if (!store.edit().putString("history", history.toString()).putBoolean("active", false).commit())
            throw new java.io.IOException("Unable to preserve interval");
        generation++; pending = false; trip.stop();
        intervalId = java.util.UUID.randomUUID().toString();
        owner = ""; ownerName = ""; ownerExpiresAt = 0;
        trip.distance = 0; trip.sampledAt = 0; save();
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vehicle vehicle;
    private boolean visible, pending;
    private int generation;
    private Runnable changed = () -> {};
    public HomeRecorder(Context context) {
        store = context.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
        intervalId = store.getString("intervalId", java.util.UUID.randomUUID().toString());
        owner = store.getString("owner", ""); ownerName = store.getString("ownerName", "");
        ownerExpiresAt = store.getLong("ownerExpiresAt", 0);
        trip.active = store.getBoolean("active", false);
        trip.missing = store.getBoolean("missing", false) || trip.active;
        trip.distance = Double.longBitsToDouble(store.getLong("distance", 0));
        trip.sampledAt = store.getLong("sampledAt", 0);
        trip.savedAt = store.getLong("savedAt", 0);
        save();
        try { vehicle = new Vehicle(context.getApplicationContext()); } catch (RuntimeException ignored) { }
    }
    public void resume(Runnable listener) { changed = listener; visible = true; tick.run(); }
    public void pause() {
        visible = false; generation++; pending = false; handler.removeCallbacksAndMessages(null);
        trip.interrupt(); save(); changed = () -> {};
    }
    public void toggle() {
        generation++; pending = false;
        if (trip.active) {
            try { endForSwitch(); } catch (Exception e) { trip.interrupt(); }
        } else {
            if (ownerExpiresAt <= System.currentTimeMillis()) { owner = ""; ownerName = ""; }
            trip.start();
        }
        save(); changed.run();
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            if (trip.active && !pending) read();
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
        store.edit().putString("intervalId", intervalId).putString("owner", owner)
                .putString("ownerName", ownerName).putLong("ownerExpiresAt", ownerExpiresAt).putBoolean("active", trip.active).putBoolean("missing", trip.missing)
                .putLong("distance", Double.doubleToLongBits(trip.distance))
                .putLong("sampledAt", trip.sampledAt).putLong("savedAt", trip.savedAt).apply();
    }
}
