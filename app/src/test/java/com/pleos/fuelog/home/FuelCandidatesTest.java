package com.pleos.fuelog.home;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class FuelCandidatesTest {
    @Test public void repeatedIncreaseKeepsOneIdAcrossRestore() throws Exception {
        FuelCandidates store = new FuelCandidates("{}");
        store.sample(30, 1000, "a", "trip"); store.sample(35, 2000, "a", "trip");
        String id = store.latest("a").getString("id");
        store = new FuelCandidates(store.serialize());
        store.sample(35, 3000, "a", "trip"); store.sample(40, 4000, "a", "trip");
        assertEquals(id, store.latest("a").getString("id"));
        assertEquals(10, store.latest("a").getInt("increasePercent"));
    }
    @Test public void stableIncreaseRequiresSixSecondsWithoutNewPeak() throws Exception {
        FuelCandidates store = new FuelCandidates("{}");
        store.sample(30, 1000, "a", "trip"); store.sample(35, 2000, "a", "trip");
        assertFalse(store.latest("a").optBoolean("stable"));
        store.sample(35, 5000, "a", "trip"); assertFalse(store.latest("a").optBoolean("stable"));
        store.sample(35, 8000, "a", "trip"); assertTrue(store.latest("a").optBoolean("stable"));
        store.sample(36, 9000, "a", "trip"); assertFalse(store.latest("a").optBoolean("stable"));
    }
    @Test public void ownerAndGapNeverBridgeReadings() throws Exception {
        FuelCandidates store = new FuelCandidates("{}");
        store.sample(30, 1000, "a", "trip"); store.sample(40, 2000, "b", "trip");
        assertNull(store.latest("b"));
        store.sample(50, 90000, "b", "trip"); assertNull(store.latest("b"));
        assertNull(store.latest(""));
    }
    @Test public void serverStateDoesNotRegressAndOtherOwnerIsRejected() throws Exception {
        FuelCandidates store = new FuelCandidates("{}");
        store.sample(30, 1000, "a", "trip"); store.sample(40, 2000, "a", "trip");
        String id = store.latest("a").getString("id");
        JSONObject response = new JSONObject().put("id", id).put("owner", "a").put("status", "CONFIRMED").put("revision", 2);
        store.applyServer(response, id, "a");
        store.applyServer(response.put("status", "CANDIDATE").put("revision", 1), id, "a");
        assertEquals("CONFIRMED", store.latest("a").getString("status"));
        try { store.applyServer(response.put("owner", "b"), id, "a"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
