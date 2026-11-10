package com.pleos.fuelog.home;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EvRecordingTest {
    @Test public void switchEdgesCaptureFinalIncreaseAndSeparateSessions() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(false, 20, "WH", 1000);
        ev.sample(false, 22, "WH", 2000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
        ev.sample(true, 22, "WH", 3000);
        ev.sample(true, 24, "WH", 4000);
        ev.sample(false, 25, "WH", 11000);
        JSONObject first = ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0);
        assertEquals(3, first.getDouble("estimatedKwh"), 0);
        assertTrue(first.getBoolean("stable"));
        assertEquals(11000, first.getLong("switchEndedAt"));
        assertEquals("PLEOS_EV_CHARGE_SWITCH", first.getString("detectionSource"));
        ev.sample(true, 25, "WH", 12000);
        ev.sample(true, 26, "WH", 13000);
        var charges = ev.snapshot().getJSONArray("chargeCandidates");
        assertEquals(2, charges.length());
        assertNotEquals(first.getString("id"), charges.getJSONObject(1).getString("id"));
    }

    @Test public void unknownSwitchNeverBecomesOffOrBridgesBatterySamples() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, 20, "WH", 1000);
        ev.sample(null, 21, "WH", 2000);
        assertFalse(ev.snapshot().getBoolean("chargeSwitchKnown"));
        assertFalse(ev.snapshot().has("chargeSwitchOn"));
        ev.sample(true, 30, "WH", 3000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
        assertTrue(ev.snapshot().getBoolean("evMissing"));
    }

    @Test public void socFallbackMeasuresOnlyTheSwitchOnSegment() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, EvRecording.kwh(Double.NaN, 50, 60000), "SOC:60000", 1000);
        ev.sample(false, EvRecording.kwh(Double.NaN, 60, 60000), "SOC:60000", 11000);
        assertEquals(6, ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0).getDouble("estimatedKwh"), 0);
        assertTrue(Double.isNaN(new TripSummary(ev.snapshot()).consumedKwh()));
    }

    @Test public void confirmedPriceIsFrozenForSameUserAndProducesTripCost() throws Exception {
        JSONObject scenario = new JSONObject().put("averageWonPerKwh", 350)
                .put("stations", new org.json.JSONArray().put(new JSONObject()).put(new JSONObject()));
        JSONObject charge = new JSONObject().put("id", "price-ref").put("status", "CONFIRMED")
                .put("endAt", 500).put("syncedEndAt", 500).put("scenarios", new org.json.JSONArray().put(scenario));
        JSONObject intervals = new JSONObject().put("old", new JSONObject().put("owner", "owner-a")
                .put("chargeCandidates", new org.json.JSONArray().put(charge)));
        EvRecording ev = new EvRecording();
        ev.setPriceReference(intervals, "owner-b", 1000);
        assertFalse(ev.snapshot().has("chargePriceValid"));
        ev.setPriceReference(intervals, "owner-a", 1000);
        scenario.put("averageWonPerKwh", 999);
        ev.sample(false, 40, "WH", 1000); ev.sample(false, 36, "WH", 2000);
        JSONObject data = ev.snapshot().put("distanceKm", 60).put("distanceValid", true);
        assertEquals(1400, new TripSummary(data).evCost(), 0);
        JSONObject upload = RecordingPayloads.upload(data.put("intervalId", "trip").put("startedAt", 1000).put("endedAt", 2000));
        assertEquals(350, upload.getDouble("unitPrice"), 0);
        assertTrue(upload.getBoolean("priceValid"));
        ev.reset();
        charge.put("status", "REJECTED");
        ev.setPriceReference(intervals, "owner-a", 1000);
        assertFalse(ev.snapshot().has("chargePriceScenarios"));
    }

    @Test public void currentReferenceCostUsesOnlyComparableRatesAndValidConsumption() throws Exception {
        JSONObject data = new JSONObject().put("evEndpointsValid", true).put("consumedKwh", 4);
        JSONObject scenario = new JSONObject().put("averageWonPerKwh", 350)
                .put("stations", new org.json.JSONArray().put(new JSONObject()).put(new JSONObject()));
        TripSummary summary = new TripSummary(data);
        assertEquals(1400, summary.referenceCost(scenario), 0);
        assertTrue(Double.isNaN(summary.referenceCost(new JSONObject().put("averageWonPerKwh", 350))));
        data.put("evMissing", true);
        assertTrue(Double.isNaN(summary.referenceCost(scenario)));
    }

    @Test public void whPreferredAndPercentFallbackNeedsCapacity() {
        assertEquals(32, EvRecording.kwh(32000, 90, 60000), 0);
        assertEquals(30, EvRecording.kwh(Double.NaN, 50, 60000), 0);
        assertTrue(Double.isNaN(EvRecording.kwh(Double.NaN, 50, 0)));
        assertTrue(Double.isNaN(EvRecording.kwh(Double.NaN, 101, 60000)));
    }

    @Test public void nonChargingBatterySamplesMeasureConsumptionWithoutGear() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(false, 40, "WH", 1000);
        ev.sample(false, 39, "WH", 2000);
        ev.sample(false, 36, "WH", 3000);
        JSONObject data = ev.snapshot().put("distanceValid", true).put("distanceKm", 60);
        assertEquals(4, new TripSummary(data).consumedKwh(), 0);
        assertEquals(15, new TripSummary(data).evEconomy(), 0);
    }

    @Test public void increasedBatteryCreatesOneCandidateAndDoesNotRegress() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, 20, "WH", 1000);
        ev.sample(true, 22, "WH", 2000);
        String id = ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0).getString("id");
        ev.sample(true, 23, "WH", 3000);
        ev.sample(true, 22.5, "WH", 4000);
        JSONObject data = ev.snapshot();
        assertEquals(1, data.getJSONArray("chargeCandidates").length());
        JSONObject charge = data.getJSONArray("chargeCandidates").getJSONObject(0);
        assertEquals(id, charge.getString("id"));
        assertEquals(3, charge.getDouble("estimatedKwh"), 0);
        assertFalse(charge.has("estimatedAmount"));
        assertTrue(Double.isNaN(new TripSummary(data).consumedKwh()));
    }

    @Test public void interruptedOrChangedSourceNeverBridgesEndpoints() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, 20, "WH", 1000);
        ev.interrupt();
        ev.sample(true, 30, "WH", 2000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
        ev.sample(false, 20, "WH", 3000);
        ev.sample(true, 30, "PERCENT:60", 4000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
    }

    @Test public void restoredLedgerKeepsCandidatesButNotLiveBaseline() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, 20, "WH", 1000); ev.sample(true, 25, "WH", 2000);
        EvRecording restored = new EvRecording(); restored.restore(ev.snapshot());
        restored.sample(true, 40, "WH", 3000);
        assertEquals(5, restored.snapshot().getJSONArray("chargeCandidates").getJSONObject(0).getDouble("estimatedKwh"), 0);
        restored.reset(); assertFalse(restored.snapshot().has("chargeCandidates"));
    }
    @Test public void serverRevisionCannotRegressAndTerminalCandidateStopsGrowing() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample(true, 20, "WH", 1000); ev.sample(true, 22, "WH", 2000);
        ev.sample(true, 23, "WH", 9000);
        JSONObject entry = ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0);
        assertTrue(entry.getBoolean("stable"));
        JSONObject response = new JSONObject().put("id", entry.getString("id")).put("endAt", 9000)
                .put("revision", 2).put("status", "CONFIRMED").put("scenarios", new org.json.JSONArray());
        assertTrue(ev.applyResponse(response));
        assertFalse(ev.applyResponse(response.put("revision", 1).put("status", "CANDIDATE")));
        ev.sample(true, 24, "WH", 12000);
        JSONObject confirmed = ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0);
        assertEquals("CONFIRMED", confirmed.getString("status"));
        assertEquals(3, confirmed.getDouble("estimatedKwh"), 0);
    }
}
