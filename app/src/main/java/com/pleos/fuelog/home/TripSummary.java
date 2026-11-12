package com.pleos.fuelog.home;

import org.json.JSONObject;

/** A single immutable recording interval. Missing fuel never invalidates distance. */
public final class TripSummary {
    public final JSONObject data;
    public TripSummary(JSONObject data) { this.data = data; }
    public static double consumption(double startLiters, double endLiters, boolean endpointsValid,
                                     boolean missing, boolean refueled, boolean ownerChanged) {
        if (!endpointsValid || missing || refueled || ownerChanged || !Double.isFinite(startLiters)
                || !Double.isFinite(endLiters) || startLiters < 0 || endLiters < 0 || startLiters <= endLiters)
            return Double.NaN;
        return startLiters - endLiters;
    }
    public static double efficiency(double km, double liters) {
        return Double.isFinite(km) && km >= 0 && Double.isFinite(liters) && liters > 0 ? km / liters : Double.NaN;
    }
    public static double cost(double liters, double price, boolean priceValid) {
        return priceValid && Double.isFinite(liters) && liters > 0 && Double.isFinite(price) && price > 0
                ? liters * price : Double.NaN;
    }
    public double consumedKwh() {
        double value = data.optDouble("consumedKwh", Double.NaN);
        return data.optBoolean("evEndpointsValid") && !data.optBoolean("missing")
                && !data.optBoolean("chargedDuringInterval") && !data.optBoolean("evMissing") && Double.isFinite(value) && value > 0 ? value : Double.NaN;
    }
    public double evEconomy() {
        return data.optBoolean("distanceValid") ? efficiency(data.optDouble("distanceKm"), consumedKwh()) : Double.NaN;
    }
    public static boolean validSavedCost(JSONObject cost) {
        if(cost==null) return false;
        try {
            double amount=cost.getDouble("amount"),rate=cost.getDouble("averageWonPerKwh"),energy=cost.getDouble("consumedKwh");
            int count=cost.getInt("sourceCount");
            boolean basis="SLOW_OPERATOR_MEAN".equals(cost.getString("basis")) && count>=1 && count<=3
                    || "SLOW_THREE_OPERATOR_MEAN".equals(cost.getString("basis")) && count==3
                    || "LEGACY_SAVED_ESTIMATE".equals(cost.getString("basis"));
            java.time.LocalDate.parse(cost.getString("tariffDate"));java.time.Instant.parse(cost.getString("calculatedAt"));
            return basis && "KRW".equals(cost.getString("currency")) && Double.isFinite(amount) && amount>=0
                    && Double.isFinite(rate) && rate>0 && Double.isFinite(energy) && energy>0;
        } catch(Exception invalid) {return false;}
    }
    public double evCost() {
        JSONObject cost=data.optJSONObject("savedEvCost");
        return validSavedCost(cost)?cost.optDouble("amount",Double.NaN):Double.NaN;
    }
    public double liters() {
        return consumption(data.optDouble("startFuelLiters", Double.NaN), data.optDouble("endFuelLiters", Double.NaN),
                data.optBoolean("fuelEndpointsValid"), data.optBoolean("missing"),
                data.optBoolean("refueled"), data.optBoolean("ownerChanged"));
    }
    public double economy() { return data.optBoolean("distanceValid") ? efficiency(data.optDouble("distanceKm"), liters()) : Double.NaN; }
    public double fuelCost() { return cost(liters(), data.optDouble("unitPrice", Double.NaN),
            data.optBoolean("priceValid") && !data.optString("priceSource").isEmpty()); }
}
