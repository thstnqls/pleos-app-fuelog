package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Conservative provisional increase detection, never payment/completion detection. */
public final class FuelCandidates {
    private final JSONObject state;
    public FuelCandidates(String json) throws Exception { state = new JSONObject(json); }
    public String serialize() { return state.toString(); }
    public void sample(int percent, long now, String owner, String interval) throws Exception {
        if (percent < 0 || percent > 100) return;
        String scope = owner + ":" + interval;
        if (!scope.equals(state.optString("scope")) || now <= state.optLong("at")
                || now - state.optLong("at") > 60000) {
            state.put("scope", scope).put("base", percent).put("peak", percent).put("open", "");
        } else {
            int base = state.getInt("base"), peak = state.getInt("peak");
            if (percent < peak - 2) {
                state.put("base", percent).put("peak", percent).put("open", "");
            } else if (percent >= base + 3) {
                JSONArray entries = state.optJSONArray("entries");
                if (entries == null) { entries = new JSONArray(); state.put("entries", entries); }
                String id = state.optString("open");
                JSONObject entry = null;
                for (int i = 0; i < entries.length(); i++)
                    if (entries.getJSONObject(i).getString("id").equals(id)) entry = entries.getJSONObject(i);
                if (entry == null) {
                    entry = new JSONObject().put("id", UUID.randomUUID().toString()).put("owner", owner)
                            .put("intervalId", interval).put("startAt", state.optLong("at"))
                            .put("status", "CANDIDATE").put("revision", 0);
                    entries.put(entry); state.put("open", entry.getString("id"));
                }
                // Repeated equal readings do not create or mutate candidates.
                if (percent > peak) entry.put("endAt", now).put("increasePercent", percent - base)
                        .put("stable", false).put("peakAt", now);
                else if (now - entry.optLong("peakAt", now) >= 6000) entry.put("stable", true);
                state.put("peak", Math.max(peak, percent));
            } else state.put("peak", Math.max(peak, percent));
        }
        state.put("at", now);
    }
    public JSONObject latest(String owner) throws Exception {
        if (owner.isEmpty()) return null;
        JSONArray entries = state.optJSONArray("entries");
        if (entries != null) for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.getJSONObject(i);
            if (owner.equals(entry.optString("owner"))) return new JSONObject(entry.toString());
        }
        return null;
    }
    public void queue(String id, String owner) throws Exception {
        JSONObject entry = find(id, owner);
        if (entry == null) throw new IllegalArgumentException();
        entry.put("queued", true);
    }
    private JSONObject find(String id, String owner) throws Exception {
        JSONArray entries = state.optJSONArray("entries");
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (id.equals(entry.getString("id")) && !owner.isEmpty() && owner.equals(entry.getString("owner"))) return entry;
        }
        return null;
    }
    public void applyServer(JSONObject response, String id, String owner) throws Exception {
        JSONObject entry = find(id, owner);
        if (entry == null || !id.equals(response.getString("id")) || !owner.equals(response.getString("owner")))
            throw new IllegalArgumentException();
        String status = response.getString("status");
        if (!java.util.Arrays.asList("CANDIDATE", "CONFIRMED", "REJECTED").contains(status)) throw new IllegalArgumentException();
        long revision = response.getLong("revision");
        if (revision <= entry.optLong("revision")) return;
        entry.put("status", status).put("revision", revision).put("checkedAt", System.currentTimeMillis());
        // Amount/location are displayed only from validated server metadata.
        for (String key : new String[]{"capacityVerified", "estimatedLiters", "estimatedAmount", "priceAt", "stationCandidates", "stations", "selectedStationId", "fuelType"}) {
            entry.remove(key);
            if (response.has(key)) entry.put(key, response.get(key));
        }
    }
}
