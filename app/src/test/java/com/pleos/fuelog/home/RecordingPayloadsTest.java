package com.pleos.fuelog.home;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingPayloadsTest {
    @Test public void floatDistanceIsRoundedAndRejectedLegacyJobsRecoverWithoutChangingIdentity() throws Exception {
        JSONObject sample = RecordingPayloads.sample(1000.1f, 1000, 1000);
        assertEquals("0.100", sample.get("distanceKm").toString());
        assertEquals("distance_jump", RecordingPayloads.sample(2001f, 1000, 1000).getString("missingReason"));
        JSONObject job = new JSONObject().put("kind", "samples").put("body", new JSONObject().put("batchId", "same-batch")
                .put("samples", new JSONArray().put(new JSONObject().put("sequence", 2).put("distanceKm", 0.0999755859375))));
        assertFalse(RecordingPayloads.repairRejected(job, 503, "INVALID_DISTANCE"));
        assertTrue(RecordingPayloads.repairRejected(job, 400, "INVALID_DISTANCE"));
        assertEquals("same-batch", job.getJSONObject("body").getString("batchId"));
        assertEquals(2, job.getJSONObject("body").getJSONArray("samples").getJSONObject(0).getLong("sequence"));
        assertFalse(RecordingPayloads.repairRejected(job, 400, "INVALID_DISTANCE"));
        JSONObject upload = new JSONObject().put("kind", "upload").put("body", new JSONObject().put("missing", false));
        assertTrue(RecordingPayloads.repairRejected(upload, 409, "RECORDING_QUALITY_MISMATCH"));
        assertTrue(upload.getJSONObject("body").getBoolean("missing"));
        assertFalse(RecordingPayloads.repairRejected(upload, 409, "RECORDING_QUALITY_MISMATCH"));
        upload.getJSONObject("body").put("unitPrice", 325.433333).put("distanceKm", .0999755859375);
        assertTrue(RecordingPayloads.repairRejected(upload, 400, "INVALID_REQUEST"));
        assertEquals("325.433", upload.getJSONObject("body").get("unitPrice").toString());
    }

    @Test public void startAcknowledgementMustKeepThePairedOwner() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        JSONObject job = new JSONObject().put("kind", "start").put("localId", "local")
                .put("body", new JSONObject().put("subjectId", "paired-user"));
        JSONObject result = new JSONObject().put("id", id).put("localIntervalId", "local")
                .put("subjectId", "paired-user");
        RecordingPayloads.validateResponse(job, result, "");
        result.put("subjectId", "other-user");
        assertThrows(Exception.class, () -> RecordingPayloads.validateResponse(job, result, ""));
        result.remove("subjectId");
        assertThrows(Exception.class, () -> RecordingPayloads.validateResponse(job, result, ""));
    }

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
    @Test public void evLedgerCandidatesKeepTheirIdsInUpload() throws Exception {
        JSONObject candidate = new JSONObject().put("id", "charge-1").put("estimatedKwh", 12).put("status", "CANDIDATE");
        JSONObject data = new JSONObject().put("intervalId", "local").put("startedAt", 1000)
                .put("endedAt", 2000).put("distanceKm", 0).put("evDetected", true)
                .put("chargeCandidates", new JSONArray().put(candidate));
        JSONObject wire = RecordingPayloads.upload(data);
        assertTrue(wire.getJSONObject("ev").getBoolean("detected"));
        assertFalse(wire.has("chargeCandidates"));
        assertFalse(wire.has("evDetected"));
    }
    @Test public void evContractAndChargeQueueRemainIndependentOfDistanceJobs() throws Exception {
        JSONObject data = new JSONObject().put("intervalId", "local").put("startedAt", 1000).put("endedAt", 2000)
                .put("distanceKm", 60).put("evDetected", true).put("evEndpointsValid", true).put("consumedKwh", 4);
        JSONObject ev = RecordingPayloads.upload(data).getJSONObject("ev");
        assertTrue(ev.getBoolean("detected")); assertEquals(4, ev.getDouble("consumedKwh"), 0);
        JSONArray jobs = new JSONArray().put(job("charge", "a", 0)).put(job("samples", "a", 0));
        assertEquals(1, RecordingPayloads.nextJob(jobs, new JSONObject(), 1));
        jobs.remove(1);
        assertEquals(-1, RecordingPayloads.nextJob(jobs, new JSONObject(), 1));
        assertEquals(0, RecordingPayloads.nextJob(jobs, new JSONObject().put("a", new JSONObject().put("id", "server-a")), 1));
        JSONObject sent = new JSONObject().put("id", "charge").put("owner", "owner");
        JSONObject job = new JSONObject().put("kind", "charge").put("body", sent);
        RecordingPayloads.validateResponse(job, new JSONObject(sent.toString()), "server-a");
        assertThrows(Exception.class, () -> RecordingPayloads.validateResponse(job,
                new JSONObject(sent.toString()).put("owner", "other"), "server-a"));
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
