package com.pleos.fuelog.home;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the tariff snapshot captured at charging time, including after quantity corrections. */
public final class ChargeHistory {
    private ChargeHistory() {}

    public static double quantity(JSONObject charge) {
        return "CONFIRMED".equals(charge.optString("status")) && !charge.isNull("correctedKwh")
                ? charge.optDouble("correctedKwh", Double.NaN) : charge.optDouble("estimatedKwh", Double.NaN);
    }

    public static JSONObject slow(JSONObject charge) {
        JSONArray scenarios = charge.optJSONArray("scenarios");
        for (int i = 0; scenarios != null && i < scenarios.length(); i++) {
            JSONObject scenario = scenarios.optJSONObject(i);
            if (scenario != null && "SLOW".equals(scenario.optString("powerBand"))) return scenario;
        }
        return null;
    }

    public static BigDecimal amount(JSONObject charge) {
        if ("REJECTED".equals(charge.optString("status"))) return null;
        JSONObject scenario = slow(charge);
        double kwh = quantity(charge);
        if (scenario == null || !Double.isFinite(kwh) || kwh < 0) return null;
        JSONArray stations = scenario.optJSONArray("stations");
        if (stations == null || stations.length() == 0) return null;
        try {
            BigDecimal rate = new BigDecimal(scenario.getString("averageWonPerKwh"));
            return rate.signum() <= 0 ? null : rate.multiply(BigDecimal.valueOf(kwh)).setScale(0, RoundingMode.HALF_UP);
        } catch (Exception invalid) { return null; }
    }

    public static JSONArray records(JSONArray history, boolean money) {
        JSONArray result = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject record = history.optJSONObject(i);
            JSONArray charges = record == null ? null : record.optJSONArray("chargeCandidates");
            for (int j = 0; charges != null && j < charges.length(); j++) {
                JSONObject charge = charges.optJSONObject(j);
                if (charge == null || "REJECTED".equals(charge.optString("status"))
                        || charge.optString("id").isEmpty() || !seen.add(charge.optString("id"))) continue;
                BigDecimal amount = amount(charge);
                double kwh = quantity(charge);
                Object value = money ? amount : Double.isFinite(kwh) && kwh >= 0 ? kwh : null;
                try {
                    result.put(new JSONObject().put("intervalId", record.optString("intervalId")).put("charge", charge)
                            .put("statAt", charge.opt("startAt")).put("statValue", value == null ? JSONObject.NULL : value)
                            .put("statAmount", amount == null ? JSONObject.NULL : amount));
                } catch (org.json.JSONException invalid) { /* Skip malformed local records. */ }
            }
        }
        return result;
    }
}
