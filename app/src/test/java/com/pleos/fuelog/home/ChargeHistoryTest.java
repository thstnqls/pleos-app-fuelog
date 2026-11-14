package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class ChargeHistoryTest {
    @Test public void slowSnapshotUsesCorrectedQuantityAndRoundsWon() throws Exception {
        JSONObject charge = charge().put("status", "CONFIRMED").put("correctedKwh", 1.8);
        assertEquals(new BigDecimal("586"), ChargeHistory.amount(charge));
        charge.put("correctedAmount", 9000);
        assertEquals(new BigDecimal("586"), ChargeHistory.amount(charge));
        charge.put("status", "REJECTED");
        assertNull(ChargeHistory.amount(charge));
    }
    @Test public void chartsDeduplicateExcludeRejectedAndKeepUnknownPricesMissing() throws Exception {
        JSONObject priced = charge();
        JSONObject unknown = charge().put("id", "unknown").put("scenarios", new JSONArray());
        JSONObject rejected = charge().put("id", "rejected").put("status", "REJECTED");
        JSONArray history = new JSONArray().put(new JSONObject().put("intervalId", "trip").put("chargeCandidates",
                new JSONArray().put(priced).put(priced).put(unknown).put(rejected)));
        JSONArray energy = ChargeHistory.records(history, false);
        assertEquals(2, energy.length());
        RecordStatistics kwh = new RecordStatistics(energy, LocalDate.now(), 1, ZoneId.systemDefault());
        assertEquals(4, kwh.total, 0);
        RecordStatistics money = new RecordStatistics(ChargeHistory.records(history, true), LocalDate.now(), 1, ZoneId.systemDefault());
        assertEquals(651, money.total, 0);
        assertEquals(1, money.missingValues);
        assertEquals(1, money.missingAmounts);
    }
    private JSONObject charge() throws Exception {
        return new JSONObject().put("id", "charge").put("status", "CANDIDATE").put("startAt", System.currentTimeMillis()).put("estimatedKwh", 2)
                .put("scenarios", new JSONArray()
                        .put(new JSONObject().put("powerBand", "FAST_50").put("averageWonPerKwh", 900))
                        .put(new JSONObject().put("powerBand", "SLOW").put("averageWonPerKwh", "325.4")
                                .put("stations", new JSONArray().put(new JSONObject().put("name", "test")))));
    }
}
