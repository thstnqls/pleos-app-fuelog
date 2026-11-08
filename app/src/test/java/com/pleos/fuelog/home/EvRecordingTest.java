package com.pleos.fuelog.home;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EvRecordingTest {
    @Test public void whPreferredAndPercentFallbackNeedsCapacity() {
        assertEquals(32, EvRecording.kwh(32000, 90, 60000), 0);
        assertEquals(30, EvRecording.kwh(Double.NaN, 50, 60000), 0);
        assertTrue(Double.isNaN(EvRecording.kwh(Double.NaN, 50, 0)));
        assertTrue(Double.isNaN(EvRecording.kwh(Double.NaN, 101, 60000)));
    }

    @Test public void driveAndReverseShareOneStartUntilPark() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample("D", 40, "WH", 1000);
        ev.sample("R", 39, "WH", 2000);
        ev.sample("P", 36, "WH", 3000);
        JSONObject data = ev.snapshot().put("distanceValid", true).put("distanceKm", 60);
        assertEquals(4, new TripSummary(data).consumedKwh(), 0);
        assertEquals(15, new TripSummary(data).evEconomy(), 0);
    }

    @Test public void increasedBatteryCreatesOneCandidateAndDoesNotRegress() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample("R", 20, "WH", 1000);
        ev.sample("P", 22, "WH", 2000);
        String id = ev.snapshot().getJSONArray("chargeCandidates").getJSONObject(0).getString("id");
        ev.sample("P", 23, "WH", 3000);
        ev.sample("P", 22.5, "WH", 4000);
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
        ev.sample("D", 20, "WH", 1000);
        ev.interrupt();
        ev.sample("P", 30, "WH", 2000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
        ev.sample("D", 20, "WH", 3000);
        ev.sample("P", 30, "PERCENT:60", 4000);
        assertFalse(ev.snapshot().has("chargeCandidates"));
    }

    @Test public void restoredLedgerKeepsCandidatesButNotLiveBaseline() throws Exception {
        EvRecording ev = new EvRecording();
        ev.sample("D", 20, "WH", 1000); ev.sample("P", 25, "WH", 2000);
        EvRecording restored = new EvRecording(); restored.restore(ev.snapshot());
        restored.sample("P", 40, "WH", 3000);
        assertEquals(5, restored.snapshot().getJSONArray("chargeCandidates").getJSONObject(0).getDouble("estimatedKwh"), 0);
        restored.reset(); assertFalse(restored.snapshot().has("chargeCandidates"));
    }
}
