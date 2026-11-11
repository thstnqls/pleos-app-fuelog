package com.pleos.fuelog.home;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TripHistoryTest {
    @Test public void onlyCompletedDrivesOfVerifiedOwnerAreVisibleNewestFirst() throws Exception {
        JSONArray history = new JSONArray()
                .put(entry("mine", 10, false)).put(entry("other", 20, false))
                .put(entry("mine", 0, false)).put(entry("mine", 30, true)).put(entry("mine", 40, false));
        JSONArray visible = TripHistory.visible(history, "mine");
        assertEquals(2, visible.length());
        assertEquals(40, visible.getJSONObject(0).getLong("endedAt"));
        assertEquals(10, visible.getJSONObject(1).getLong("endedAt"));
        assertEquals(0, TripHistory.visible(history, "").length());
        assertEquals(5, history.length());
    }

    @Test public void unavailableOrInvalidServerMetricsAreNeverUsedAsZero() throws Exception {
        JSONObject summary = new JSONObject().put("distanceKm", new JSONObject().put("status", "available").put("value", 60));
        assertEquals(60, TripHistory.metric(summary, "distanceKm"), 0);
        assertTrue(Double.isNaN(TripHistory.metric(null, "distanceKm")));
        assertTrue(Double.isNaN(TripHistory.metric(summary, "estimatedCost")));
        summary.getJSONObject("distanceKm").put("status", "unavailable");
        assertTrue(Double.isNaN(TripHistory.metric(summary, "distanceKm")));
        summary.getJSONObject("distanceKm").put("status", "available").put("value", JSONObject.NULL);
        assertTrue(Double.isNaN(TripHistory.metric(summary, "distanceKm")));
        summary.getJSONObject("distanceKm").put("value", -1);
        assertTrue(Double.isNaN(TripHistory.metric(summary, "distanceKm")));
    }

    @Test public void wonRoundingAndRemaindersPreserveTheTotal() {
        assertSplit(10000, 3, "3333", "1");
        assertSplit(6800, 4, "1700", "0");
        assertSplit(10.5, 3, "3", "2");
        assertSplit(0, 2, "0", "0");
        assertSplit(1, 99, "0", "1");
        assertSplit(123.4, 1, "123", "0");
        assertThrows(IllegalArgumentException.class, () -> TripHistory.splitWon(10, 0));
        assertThrows(IllegalArgumentException.class, () -> TripHistory.splitWon(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> TripHistory.splitWon(Double.NaN, 2));
        assertThrows(IllegalArgumentException.class, () -> TripHistory.splitWon(Double.POSITIVE_INFINITY, 2));
    }

    private void assertSplit(double total, int people, String each, String extra) {
        BigDecimal[] parts = TripHistory.splitWon(total, people);
        assertEquals(new BigDecimal(each), parts[0]);
        assertEquals(new BigDecimal(extra), parts[1]);
        assertEquals(TripHistory.won(total), parts[0].multiply(BigDecimal.valueOf(people)).add(parts[1]));
    }

    private JSONObject entry(String owner, long ended, boolean charging) throws Exception {
        return new JSONObject().put("owner", owner).put("endedAt", ended).put("chargingOnly", charging);
    }
}
