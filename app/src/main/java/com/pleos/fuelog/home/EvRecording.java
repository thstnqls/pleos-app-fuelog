package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Battery increases are provisional ledger entries, never proof of a paid charge. */
public final class EvRecording {
    private JSONObject data = new JSONObject();
    private double start = Double.NaN, parked = Double.NaN;
    private String basis = "", openId = "";
    private boolean driving;
    private long startedAt;

    public static double kwh(double wh, double percent, double capacityWh) {
        if (Double.isFinite(wh) && wh >= 0) return wh / 1000;
        return Double.isFinite(percent) && percent >= 0 && percent <= 100
                && Double.isFinite(capacityWh) && capacityWh > 0 ? percent / 100 * capacityWh / 1000 : Double.NaN;
    }

    public void reset() { interrupt(); data = new JSONObject(); }
    public void interrupt() {
        if (Double.isFinite(start)) {
            try { data.put("evMissing", true); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        start = parked = Double.NaN; driving = false; basis = ""; openId = "";
    }

    public void sample(String gear, double energy, String sampleBasis, long now) throws Exception {
        if (!Double.isFinite(energy) || energy < 0 || now <= 0) { interrupt(); return; }
        if (!basis.isEmpty() && !basis.equals(sampleBasis)) interrupt();
        basis = sampleBasis;
        if (gear.equals("D") || gear.equals("R")) {
            if (!driving) {
                start = energy; startedAt = now; driving = true; parked = Double.NaN; openId = "";
                data.put("evDetected", true);
            }
        } else if (gear.equals("P") && Double.isFinite(start)) {
            if (driving) {
                data.put("evEndpointsValid", true);
                double consumed = start - energy;
                if (consumed > 0) data.put("consumedKwh", data.optDouble("consumedKwh", 0) + consumed);
                driving = false;
                parked = Math.min(start, energy);
            }
            if (energy > parked) {
                JSONArray entries = data.optJSONArray("chargeCandidates");
                if (entries == null) { entries = new JSONArray(); data.put("chargeCandidates", entries); }
                JSONObject entry = null;
                for (int i = 0; i < entries.length(); i++)
                    if (openId.equals(entries.getJSONObject(i).getString("id"))) entry = entries.getJSONObject(i);
                if (entry == null) {
                    openId = UUID.randomUUID().toString();
                    entry = new JSONObject().put("id", openId).put("status", "CANDIDATE")
                            .put("startAt", startedAt).put("basis", basis);
                    entries.put(entry);
                }
                if (energy - parked > entry.optDouble("estimatedKwh", 0))
                    entry.put("endAt", now).put("estimatedKwh", energy - parked);
                data.put("chargedDuringInterval", true);
            }
        }
    }

    public JSONObject snapshot() throws Exception { return new JSONObject(data.toString()); }
    public void restore(JSONObject saved) throws Exception { data = new JSONObject(saved.toString()); interrupt(); }
}
