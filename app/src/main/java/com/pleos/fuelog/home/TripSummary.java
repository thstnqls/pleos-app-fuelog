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
    public double liters() {
        return consumption(data.optDouble("startFuelLiters", Double.NaN), data.optDouble("endFuelLiters", Double.NaN),
                data.optBoolean("fuelEndpointsValid"), data.optBoolean("missing"),
                data.optBoolean("refueled"), data.optBoolean("ownerChanged"));
    }
    public double economy() { return data.optBoolean("distanceValid") ? efficiency(data.optDouble("distanceKm"), liters()) : Double.NaN; }
    public double fuelCost() { return cost(liters(), data.optDouble("unitPrice", Double.NaN),
            data.optBoolean("priceValid") && !data.optString("priceSource").isEmpty()); }
}
