package com.pleos.fuelog.home;

import org.json.JSONObject;

/** Coordinates live only in memory for a candidate's one-shot enrichment operation. */
public final class FuelLocationEvidence {
    public static JSONObject body(String id, double latitude, double longitude, long measuredAt,
                                  long receivedAt, long requestedAt, String fuelType,
                                  boolean coordinateSystemVerified) throws Exception {
        if (!coordinateSystemVerified || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || requestedAt <= 0 || measuredAt < requestedAt || measuredAt > receivedAt
                || receivedAt - requestedAt > 10000
                || !java.util.Arrays.asList("B034", "B027", "D047", "C004", "K015").contains(fuelType))
            throw new IllegalArgumentException("Location/fuel evidence unavailable");
        java.util.UUID.fromString(id);
        return new JSONObject().put("candidateId", id).put("latitude", latitude).put("longitude", longitude)
                .put("measuredAt", RecordingPayloads.iso(measuredAt)).put("receivedAt", RecordingPayloads.iso(receivedAt))
                .put("source", "PLEOS_CURRENT_UPDATE").put("fuelType", fuelType)
                .put("locationOperationId", "station-search:" + id);
    }

    public static boolean validPrice(JSONObject station, long now) {
        try {
            long posted = java.time.Instant.parse(station.getString("pricePostedAt")).toEpochMilli();
            long fetched = java.time.Instant.parse(station.getString("priceFetchedAt")).toEpochMilli();
            double price = station.getDouble("postedPriceWonPerLiter");
            return station.getString("priceStatus").equals("VALID") && Double.isFinite(price) && price > 0
                    && java.util.Arrays.asList("B034", "B027", "D047", "C004", "K015").contains(station.optString("fuelType"))
                    && !station.optString("stationId").isEmpty() && posted > 0 && fetched >= posted
                    && fetched <= now && now - posted < 24 * 60 * 60 * 1000L;
        } catch (Exception invalid) { return false; }
    }
}
