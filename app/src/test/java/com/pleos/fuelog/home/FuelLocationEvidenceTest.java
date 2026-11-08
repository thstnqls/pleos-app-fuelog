package com.pleos.fuelog.home;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FuelLocationEvidenceTest {
    private final String id = "11111111-1111-4111-8111-111111111111";
    @Test public void candidateBoundBodyRejectsUnknownCoordinatesStaleOrLateLocation() throws Exception {
        JSONObject body = FuelLocationEvidence.body(id, 37, 127, 1100, 1200, 1000, "B027", true);
        assertEquals(id, body.getString("candidateId"));
        assertEquals("PLEOS_CURRENT_UPDATE", body.getString("source"));
        assertThrows(IllegalArgumentException.class, () -> FuelLocationEvidence.body(id, 37, 127, 1100, 1200, 1000, "B027", false));
        assertThrows(IllegalArgumentException.class, () -> FuelLocationEvidence.body(id, 37, 127, 900, 1200, 1000, "B027", true));
        assertThrows(IllegalArgumentException.class, () -> FuelLocationEvidence.body(id, 37, 127, 1100, 12000, 1000, "B027", true));
        assertThrows(IllegalArgumentException.class, () -> FuelLocationEvidence.body(id, Double.NaN, 127, 1100, 1200, 1000, "B027", true));
        assertThrows(IllegalArgumentException.class, () -> FuelLocationEvidence.body(id, 37, 127, 1100, 1200, 1000, "ELECTRIC", true));
    }
    @Test public void oldZeroOrFuturePriceCannotBeUsed() throws Exception {
        long now = 1794099600000L;
        JSONObject station = new JSONObject().put("stationId", "a").put("fuelType", "B027").put("priceStatus", "VALID")
                .put("postedPriceWonPerLiter", 1700).put("pricePostedAt", RecordingPayloads.iso(now - 1000))
                .put("priceFetchedAt", RecordingPayloads.iso(now));
        assertTrue(FuelLocationEvidence.validPrice(station, now));
        assertFalse(FuelLocationEvidence.validPrice(station, now + 86400000));
        station.put("postedPriceWonPerLiter", 0);
        assertFalse(FuelLocationEvidence.validPrice(station, now));
        station.put("postedPriceWonPerLiter", 1700).put("pricePostedAt", RecordingPayloads.iso(now + 1));
        assertFalse(FuelLocationEvidence.validPrice(station, now));
    }
}
