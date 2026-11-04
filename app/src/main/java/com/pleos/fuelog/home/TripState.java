package com.pleos.fuelog.home;

/** Distance belongs to an app recording interval, never an ignition interval. */
public final class TripState {
    public boolean active, missing;
    public double distance;
    public long sampledAt, savedAt;
    private double previous = Double.NaN;
    public void start() { active = true; missing = false; distance = 0; sampledAt = 0; previous = Double.NaN; }
    public void interrupt() { if (active) missing = true; previous = Double.NaN; }
    public void stop() { active = false; previous = Double.NaN; }
    public void sample(double km, long now) {
        if (!active) return;
        if (!Double.isFinite(km) || km < 0) { interrupt(); return; }
        if (Double.isFinite(previous)) {
            if (km >= previous) distance += km - previous;
            else missing = true;
        }
        previous = km; sampledAt = now;
    }
    public String status(long now) {
        if (!active) return "기록 준비";
        if (missing || sampledAt > 0 && now - sampledAt > 15000) return "일부 누락";
        return sampledAt == 0 ? "기록 준비" : "기록 중";
    }
}
