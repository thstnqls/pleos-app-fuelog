package com.pleos.fuelog.home;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordStatisticsTest {
    private final ZoneId zone = ZoneId.of("Asia/Seoul");

    @Test public void dayUsesLocalHourAndIncludesOnlySelectedDate() throws Exception {
        JSONArray rows = new JSONArray().put(row("2026-11-11T14:59:59Z", 5, 10))
                .put(row("2026-11-11T15:00:00Z", 10, 20)).put(row("2026-11-12T14:59:59Z", 20, 30))
                .put(row("2026-11-12T15:00:00Z", 90, 999));
        RecordStatistics stats = new RecordStatistics(rows, LocalDate.of(2026, 11, 12), 0, zone);
        assertEquals(24, stats.values.length);
        assertEquals(2, stats.count);
        assertEquals(10, stats.values[0], 0);
        assertEquals(20, stats.values[23], 0);
        assertEquals(30, stats.total, 0);
        assertEquals(new BigDecimal("50"), stats.amount);
    }

    @Test public void leapMonthAndYearHaveCorrectBuckets() throws Exception {
        JSONArray rows = new JSONArray().put(row("2024-02-29T03:00:00Z", 5, 10))
                .put(row("2024-03-01T03:00:00Z", 10, 20)).put(row("2025-02-01T03:00:00Z", 50, 90));
        RecordStatistics month = new RecordStatistics(rows, LocalDate.of(2024, 2, 1), 1, zone);
        assertEquals(29, month.values.length);
        assertEquals(1, month.count);
        assertEquals(5, month.values[28], 0);
        RecordStatistics year = new RecordStatistics(rows, LocalDate.of(2024, 1, 1), 2, zone);
        assertEquals(12, year.values.length);
        assertEquals(2, year.count);
        assertEquals(5, year.values[1], 0);
        assertEquals(10, year.values[2], 0);
    }

    @Test public void missingValuesAreCountedAndKnownZeroRemainsMeasured() throws Exception {
        JSONArray rows = new JSONArray().put(row("2026-11-12T01:00:00Z", 0, 0))
                .put(new JSONObject().put("statAt", "2026-11-12T01:00:00Z"))
                .put(row("2026-11-12T01:00:00Z", -1, -1))
                .put(row("invalid", 50, 99)).put(new JSONObject().put("statAt", 0));
        RecordStatistics stats = new RecordStatistics(rows, LocalDate.of(2026, 11, 12), 0, zone);
        assertEquals(3, stats.count);
        assertEquals(2, stats.missingValues);
        assertEquals(2, stats.missingAmounts);
        assertEquals(1, stats.measured[10]);
        assertEquals(3, stats.counts[10]);
        assertEquals(BigDecimal.ZERO, stats.amount);
        assertEquals(0, stats.total, 0);
        assertThrows(IllegalArgumentException.class, () -> new RecordStatistics(rows, LocalDate.now(), 3, zone));
    }

    @Test public void roundedRecordAmountsSumExactlyAsShownInCards() throws Exception {
        JSONArray rows = new JSONArray().put(row("2026-11-12T01:00:00Z", 5, 10.5))
                .put(row("2026-11-12T01:00:00Z", 10, 10.5));
        assertEquals(new BigDecimal("22"), new RecordStatistics(rows, LocalDate.of(2026, 11, 12), 0, zone).amount);
    }

    private JSONObject row(String at, double value, double amount) throws Exception {
        return new JSONObject().put("statAt", at).put("statValue", value).put("statAmount", amount);
    }
}
