package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingPayloadsTest {
    @Test public void uploadUsesCandidateNotSubjectAndUtcTimes() throws Exception {
        JSONObject data = new JSONObject().put("intervalId", "local").put("owner", "subject")
                .put("candidateId", "verified-candidate").put("startedAt", 1794013200000L)
                .put("endedAt", 1794016800000L).put("distanceKm", 60).put("distanceValid", true);
        JSONObject wire = RecordingPayloads.upload(data);
        assertEquals("verified-candidate", wire.getString("userConsentId"));
        assertEquals("2026-11-07T01:00:00Z", wire.getString("startedAt"));
        assertFalse(wire.getBoolean("fuelEndpointsValid"));
        assertFalse(wire.getBoolean("priceValid"));
        assertFalse(wire.has("owner"));
        data.remove("candidateId");
        assertTrue(RecordingPayloads.upload(data).isNull("userConsentId"));
    }
    @Test public void sendsDeltaAndExplicitGapsInsteadOfTotalOdometer() throws Exception {
        assertEquals(.125, RecordingPayloads.sample(48200.625f, 48200.5, 0).getDouble("distanceKm"), 0);
        assertEquals("baseline_required", RecordingPayloads.sample(10f, Double.NaN, 0).getString("missingReason"));
        assertEquals("odometer_reset", RecordingPayloads.sample(1f, 10, 0).getString("missingReason"));
        for (Float km : new Float[]{null, Float.NaN, Float.POSITIVE_INFINITY, -1f}) {
            JSONObject sample = RecordingPayloads.sample(km, 10, 0);
            assertFalse(sample.has("distanceKm"));
            assertEquals("odometer_unavailable", sample.getString("missingReason"));
        }
    }
    @Test public void blockedIntervalCannotFinishBeforeSamplesButOtherIntervalsProceed() throws Exception {
        JSONArray jobs = new JSONArray().put(job("samples", "a", 100))
                .put(job("finish", "a", 0)).put(job("start", "b", 0));
        assertEquals(2, RecordingPayloads.nextJob(jobs, new JSONObject(), 50));
        assertEquals(0, RecordingPayloads.nextJob(jobs, new JSONObject(), 100));
    }
    @Test public void missingFuelEndpointDoesNotBlockRecording() throws Exception {
        JSONObject intervals = new JSONObject().put("a", new JSONObject().put("id", "server-a"));
        JSONArray jobs = new JSONArray().put(job("fuel", "a", 1000)).put(job("samples", "a", 0));
        assertEquals(1, RecordingPayloads.nextJob(jobs, intervals, 50));
        jobs.remove(1);
        assertEquals(-1, RecordingPayloads.nextJob(jobs, intervals, 50));
        assertEquals(0, RecordingPayloads.nextJob(jobs, intervals, 1000));
    }
    @Test public void foreignOrPartialAcknowledgementCannotDeleteSamples() throws Exception {
        JSONObject job = job("samples", "a", 0).put("body", new JSONObject().put("batchId", "batch")
                .put("samples", new JSONArray().put(new JSONObject().put("sequence", 3))));
        JSONObject accepted = new JSONObject().put("intervalId", "server-a").put("batchId", "batch")
                .put("acceptedCount", 1).put("lastAcceptedSequence", 3);
        RecordingPayloads.validateResponse(job, accepted, "server-a");
        accepted.put("lastAcceptedSequence", 2);
        assertThrows(Exception.class, () -> RecordingPayloads.validateResponse(job, accepted, "server-a"));
        accepted.put("lastAcceptedSequence", 3).put("batchId", "other");
        assertThrows(Exception.class, () -> RecordingPayloads.validateResponse(job, accepted, "server-a"));
    }
    private JSONObject job(String kind, String local, long retryAt) throws Exception {
        return new JSONObject().put("kind", kind).put("localId", local).put("retryAt", retryAt);
    }
}
