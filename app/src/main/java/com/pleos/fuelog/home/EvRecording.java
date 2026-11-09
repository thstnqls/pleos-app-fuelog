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
        data.put("batterySampledAt", now);
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
                            .put("startAt", startedAt).put("detectedAt", now).put("basis", basis);
                    entries.put(entry);
                }
                if (!entry.optString("status").equals("CANDIDATE")) return;
                if (now - entry.optLong("detectedAt", now) >= 6000) entry.put("stable", true);
                if (energy - parked > entry.optDouble("estimatedKwh", 0))
                    entry.put("endAt", now).put("estimatedKwh", energy - parked);
                data.put("chargedDuringInterval", true);
            }
        }
    }

    /** Freeze a confirmed, same-user charging reference when the next recording starts. */
    public void setPriceReference(JSONObject intervals, String owner, long before) throws Exception {
        if (intervals == null || owner.isEmpty()) return;
        JSONObject latest = null;
        java.util.Iterator<String> keys = intervals.keys();
        while (keys.hasNext()) {
            JSONObject interval = intervals.optJSONObject(keys.next());
            if (interval == null || !owner.equals(interval.optString("owner"))) continue;
            JSONArray charges = interval.optJSONArray("chargeCandidates");
            if (charges == null) continue;
            for (int i = 0; i < charges.length(); i++) {
                JSONObject charge = charges.optJSONObject(i);
                if (charge == null || !"CONFIRMED".equals(charge.optString("status"))
                        || charge.optLong("endAt") <= 0 || charge.optLong("endAt") > before
                        || charge.optLong("syncedEndAt") < charge.optLong("endAt")) continue;
                JSONArray scenarios = charge.optJSONArray("scenarios");
                if (scenarios == null || scenarios.length() == 0) continue;
                if (latest == null || charge.optLong("endAt") > latest.optLong("endAt")) latest = charge;
            }
        }
        if (latest != null) {
            data.put("chargePriceScenarios", new JSONArray(latest.getJSONArray("scenarios").toString()));
            data.put("chargePriceSource", "confirmed-charge:" + latest.getString("id"));
            data.put("chargePriceObservedAt", latest.getLong("endAt"));
            JSONArray scenarios = data.getJSONArray("chargePriceScenarios");
            if (scenarios.length() == 1) {
                JSONObject scenario = scenarios.getJSONObject(0);
                JSONArray stations = scenario.optJSONArray("stations");
                double price = scenario.optDouble("averageWonPerKwh", Double.NaN);
                if (stations != null && stations.length() >= 2 && stations.length() <= 3 && Double.isFinite(price) && price > 0) {
                    data.put("chargeUnitPriceWonPerKwh", price).put("chargePriceValid", true);
                }
            }
        }
    }

    public static boolean applyResponse(JSONObject entry, JSONObject response) throws Exception {
        if (!entry.getString("id").equals(response.getString("id"))) throw new IllegalArgumentException();
        String status = response.getString("status");
        if (!java.util.Arrays.asList("CANDIDATE", "CONFIRMED", "REJECTED").contains(status)) throw new IllegalArgumentException();
        long revision = response.getLong("revision");
        if (revision <= entry.optLong("serverRevision")) return false;
        entry.put("serverRevision", revision).put("syncedEndAt", response.getLong("endAt")).put("status", status);
        for (String key : new String[]{"estimateStatus", "scenarios", "correctedKwh", "correctedAmount", "correctedPlace"}) {
            entry.remove(key);
            if (response.has(key)) entry.put(key, response.get(key));
        }
        return true;
    }
    public boolean applyResponse(JSONObject response) throws Exception {
        org.json.JSONArray entries = data.optJSONArray("chargeCandidates");
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry.optString("id").equals(response.optString("id"))) return applyResponse(entry, response);
        }
        return false;
    }
    public JSONObject snapshot() throws Exception { return new JSONObject(data.toString()); }
    public void restore(JSONObject saved) throws Exception { data = new JSONObject(saved.toString()); interrupt(); }
}
