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
    public double evCost() {
        return cost(consumedKwh(), data.optDouble("chargeUnitPriceWonPerKwh", Double.NaN),
                data.optBoolean("chargePriceValid") && !data.optString("chargePriceSource").isEmpty());
    }
    public double referenceCost(JSONObject scenario) {
        if (scenario == null) return Double.NaN;
        org.json.JSONArray stations = scenario.optJSONArray("stations");
        return cost(consumedKwh(), scenario.optDouble("averageWonPerKwh", Double.NaN),
                stations != null && stations.length() >= 2 && stations.length() <= 3);
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
