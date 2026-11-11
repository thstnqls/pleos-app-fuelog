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

    /** The first element is each share; the second is the number who pay one extra won. */
    public static BigDecimal[] splitWon(double amount, int people) {
        if (people < 1) throw new IllegalArgumentException("At least one person required");
        return won(amount).divideAndRemainder(BigDecimal.valueOf(people));
    }
}
