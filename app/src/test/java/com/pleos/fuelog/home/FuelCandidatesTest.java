package com.pleos.fuelog.home;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class FuelCandidatesTest {
    @Test public void historyIsOwnerScopedNewestFirstAndDetached() throws Exception {
        FuelCandidates store = new FuelCandidates("{\"entries\":[{\"id\":\"1\",\"owner\":\"a\",\"status\":\"CONFIRMED\"},"
                + "{\"id\":\"2\",\"owner\":\"b\"},{\"id\":\"3\",\"owner\":\"a\",\"status\":\"REJECTED\"}]}");
        var history = store.history("a");
        assertEquals(2, history.length());
        assertEquals("3", history.getJSONObject(0).getString("id"));
        assertEquals("1", history.getJSONObject(1).getString("id"));
        history.getJSONObject(0).put("status", "CONFIRMED");
        assertEquals("REJECTED", store.latest("a").getString("status"));
        assertEquals(0, store.history("").length());
    }
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
