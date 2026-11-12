package com.pleos.fuelog.home;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.json.JSONArray;
import org.json.JSONObject;

/** Completed driving intervals visible to the currently verified owner. */
public final class TripHistory {
    private TripHistory() {}

    public static JSONArray visible(JSONArray history, String owner) throws Exception {
        JSONArray result = new JSONArray();
        if (owner.isEmpty()) return result;
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject entry = history.getJSONObject(i);
            if (owner.equals(entry.optString("owner")) && entry.optLong("endedAt") > 0
                    && !entry.optBoolean("chargingOnly")) result.put(entry);
        }
        return result;
    }

    public static double metric(JSONObject summary, String key) {
        JSONObject metric = summary == null ? null : summary.optJSONObject(key);
        double value = metric == null ? Double.NaN : metric.optDouble("value", Double.NaN);
        return metric != null && "available".equals(metric.optString("status"))
                && Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }

    public static BigDecimal won(double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid amount");
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.HALF_UP);
    }

    public static boolean electric(JSONObject data) {
        return data.optBoolean("evDetected") || !data.has("startFuelLiters");
    }

    public static double distance(JSONObject data, JSONObject remote) {
        return remote != null ? metric(remote, "distanceKm") : data.optBoolean("distanceValid")
                ? data.optDouble("distanceKm", Double.NaN) : Double.NaN;
    }

    public static double amount(JSONObject data, JSONObject remote) {
        TripSummary local = new TripSummary(data);
        if (electric(data) && TripSummary.validSavedCost(data.optJSONObject("savedEvCost"))) return local.evCost();
        JSONObject money = remote == null ? null : remote.optJSONObject("estimatedCost");
        String currency = remote == null ? electric(data) ? "KRW" : data.optString("currency")
                : money == null ? "" : money.optString("currency");
        if (!"KRW".equals(currency)) return Double.NaN;
        return remote != null ? metric(remote, "estimatedCost") : electric(data) ? local.evCost() : local.fuelCost();
    }

    /** The first element is each share; the second is the number who pay one extra won. */
    public static BigDecimal[] splitWon(double amount, int people) {
        if (people < 1) throw new IllegalArgumentException("At least one person required");
        return won(amount).divideAndRemainder(BigDecimal.valueOf(people));
    }
}
