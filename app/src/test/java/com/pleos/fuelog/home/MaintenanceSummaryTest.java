package com.pleos.fuelog.home;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

public class MaintenanceSummaryTest {
    private MaintenanceSummary summary(long observedAt, double odometerKm, String nextDate,
                                       double nextKm) throws Exception {
        return new MaintenanceSummary(new JSONObject().put("odometer", new JSONObject()
                .put("km", odometerKm).put("observedAt", observedAt))
                .put("items", new org.json.JSONArray().put(new JSONObject()
                        .put("name", "엔진오일").put("basis", "USER")
                        .put("lastServiceDate", "2026-01-01").put("lastServiceKm", 42000)
                        .put("nextDueDate", nextDate).put("nextDueKm", nextKm))));
    }

    @Test public void eitherReachedConditionMarksServiceDue() throws Exception {
        long now = 1_800_000_000_000L;
        MaintenanceSummary byDistance = summary(now, 52000, "2027-01-01", 52000);
        assertEquals("거리 기준 도래", byDistance.items.get(0).status(byDistance,
                LocalDate.of(2026, 11, 6), now));
        MaintenanceSummary byDate = summary(now, 48000, "2026-11-06", 52000);
        assertEquals("날짜 기준 도래", byDate.items.get(0).status(byDate,
                LocalDate.of(2026, 11, 6), now));
        assertEquals(4000, byDate.items.get(0).kmUntil(byDate, now), 0);
    }

    @Test public void staleOdometerDoesNotClaimRemainingDistance() throws Exception {
        long now = 1_800_000_000_000L;
        MaintenanceSummary stale = summary(now - 8L * 24 * 60 * 60 * 1000,
                53000, "2027-01-01", 52000);
        assertFalse(stale.hasRecentOdometer(now));
        assertNull(stale.items.get(0).kmUntil(stale, now));
        assertEquals("날짜 또는 거리 중 먼저 도달하면 정비 시기입니다",
                stale.items.get(0).status(stale, LocalDate.of(2026, 11, 6), now));
    }

    @Test public void onlyTwoSharedItemsAreShown() throws Exception {
        org.json.JSONArray items = new org.json.JSONArray();
        for (int i = 0; i < 3; i++) items.put(new JSONObject().put("name", "항목 " + i));
        MaintenanceSummary result = new MaintenanceSummary(new JSONObject().put("items", items));
        assertEquals(2, result.items.size());
        assertFalse(result.hasRecentOdometer(System.currentTimeMillis()));
    }
}
