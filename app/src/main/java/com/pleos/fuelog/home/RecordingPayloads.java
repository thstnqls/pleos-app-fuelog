package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Wire values are independent of Android lifecycle and local display models. */
public final class RecordingPayloads {
    private RecordingPayloads() {}
    public static JSONObject sample(Float km, double previousKm, long now) throws Exception {
        JSONObject sample = new JSONObject().put("observedAt", iso(now));
        if (km == null || !Float.isFinite(km) || km < 0) return sample.put("missingReason", "odometer_unavailable");
        if (!Double.isFinite(previousKm)) return sample.put("missingReason", "baseline_required");
        if (km < previousKm) return sample.put("missingReason", "odometer_reset");
        return sample.put("distanceKm", km - previousKm);
    }
    public static int nextJob(JSONArray jobs, JSONObject intervals, long now) throws Exception {
        Set<String> waiting = new HashSet<>();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            if (job.getString("kind").equals("fuel") || job.getString("kind").equals("charge")) continue;
            if (!waiting.add(job.getString("localId"))) continue;
            if (job.optLong("retryAt") <= now) return i;
        }
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            JSONObject interval = intervals.optJSONObject(job.getString("localId"));
            if ((job.getString("kind").equals("fuel") || job.getString("kind").equals("charge")) && interval != null && !interval.optString("id").isEmpty()
                    && job.optLong("retryAt") <= now) return i;
        }
        return -1;
    }
    public static void validateResponse(JSONObject job, JSONObject result, String id) throws Exception {
        String kind = job.getString("kind");
        JSONObject sent = job.optJSONObject("body");
        switch (kind) {
            case "start":
                if (!job.getString("localId").equals(result.getString("localIntervalId"))) throw new IllegalArgumentException();
                UUID.fromString(result.getString("id"));
                if (!sent.isNull("subjectId") && !sent.getString("subjectId").equals(result.getString("subjectId")))
                    throw new IllegalArgumentException("Recording owner mismatch");
                break;
            case "samples":
                if (!id.equals(result.getString("intervalId")) || !sent.getString("batchId").equals(result.getString("batchId"))
                        || result.getInt("acceptedCount") < 1
                        || result.getLong("lastAcceptedSequence") < sent.getJSONArray("samples").getJSONObject(0).getLong("sequence"))
                    throw new IllegalArgumentException();
                break;
            case "finish":
                if (!id.equals(result.getString("id")) || !"finished".equals(result.getString("status"))) throw new IllegalArgumentException();
                break;
            case "upload":
                if (!id.equals(result.getString("tripId")) || result.getLong("revision") != sent.getLong("revision")) throw new IllegalArgumentException();
                break;
            case "summary":
                if (!id.equals(result.getString("tripId"))) throw new IllegalArgumentException();
                break;
            case "deliver":
                if (!id.equals(result.getString("tripId"))) throw new IllegalArgumentException();
                Instant.parse(result.getString("deliveredAt")); break;
            case "charge":
            case "fuel":
                if (!sent.getString("id").equals(result.getString("id")) || !sent.getString("owner").equals(result.getString("owner")))
                    throw new IllegalArgumentException();
                break;
            default: throw new IllegalArgumentException("Unknown upload kind");
        }
    }
    public static String iso(long millis) { return Instant.ofEpochMilli(millis).toString(); }
    public static JSONObject upload(JSONObject data) throws Exception {
        JSONObject result = new JSONObject().put("tripId", data.getString("intervalId")).put("revision", 1)
                .put("source", "manual_app").put("userConsentId", data.optString("candidateId").isEmpty()
                        ? JSONObject.NULL : data.getString("candidateId"))
                .put("startedAt", iso(data.getLong("startedAt"))).put("endedAt", iso(data.getLong("endedAt")))
                .put("endEstimated", data.optBoolean("endEstimated")).put("distanceKm", data.optDouble("distanceKm"))
                .put("distanceValid", data.optBoolean("distanceValid")).put("missing", data.optBoolean("missing"))
                .put("fuelEndpointsValid", data.optBoolean("fuelEndpointsValid"))
                .put("refueled", data.optBoolean("refueled")).put("ownerChanged", data.optBoolean("ownerChanged"))
                .put("priceValid", data.optBoolean("priceValid"));
        for (String key : new String[]{"startFuelLiters", "endFuelLiters", "unitPrice", "currency", "priceSource"})
            if (data.has(key)) result.put(key, data.get(key));
        if (data.optLong("priceObservedAt") > 0) result.put("priceObservedAt", iso(data.getLong("priceObservedAt")));
        if (data.optBoolean("evDetected")) {
            JSONObject ev = new JSONObject().put("detected", true).put("endpointsValid", data.optBoolean("evEndpointsValid"))
                    .put("missing", data.optBoolean("evMissing")).put("chargedDuringInterval", data.optBoolean("chargedDuringInterval"));
            if (data.has("consumedKwh")) ev.put("consumedKwh", data.get("consumedKwh"));
            result.put("ev", ev);
        }
        return result;
    }
}
