package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingSavedTimeAndroidTest {
    @Test public void recordedDistanceBatteryAndPriceReachCurrentTripSummary() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "trip-metrics-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        var connection = com.pleos.fuelog.connection.ConnectionSession.get(isolated);
        String previous = connection.authenticatedSubject;
        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit().putString("owner", "metrics-owner").commit();
        try {
            connection.authenticatedSubject = "metrics-owner";
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    HomeRecorder recorder = new HomeRecorder(isolated);
                    recorder.toggle();
                    assertTrue(recorder.trip.active);
                    recorder.trip.sample(1000, 1000); recorder.trip.sample(1060, 2000);
                    var field = HomeRecorder.class.getDeclaredField("ev"); field.setAccessible(true);
                    EvRecording ev = (EvRecording) field.get(recorder);
                    var data = EvRecording.class.getDeclaredField("data"); data.setAccessible(true);
                    ((org.json.JSONObject) data.get(ev)).put("chargePriceValid", true)
                            .put("chargeUnitPriceWonPerKwh", 350).put("chargePriceSource", "confirmed-charge:test");
                    ev.sample("D", 40, "AAOS_WH", 1000); ev.sample("P", 36, "AAOS_WH", 2000);
                    var save = HomeRecorder.class.getDeclaredMethod("saveEv"); save.setAccessible(true); save.invoke(recorder);
                    TripSummary result = recorder.summary();
                    assertNotNull(result);
                    assertEquals(60, result.data.getDouble("distanceKm"), 0);
                    assertEquals(4, result.consumedKwh(), 0);
                    assertEquals(15, result.evEconomy(), 0);
                    assertEquals(1400, result.evCost(), 0);
                    String id = result.data.getString("intervalId");
                    recorder.rememberLocation(id, "metrics-owner", "CURRENT_REFERENCE", 37.5, 127.1, 1000);
                    assertEquals(37.5, recorder.visibleLocation(id).getDouble("latitude"), 0);
                    assertThrows(IllegalArgumentException.class, () -> recorder.rememberLocation(id, "other", "CURRENT_REFERENCE", 37.5, 127.1, 1000));
                    assertThrows(IllegalArgumentException.class, () -> recorder.rememberLocation(id, "metrics-owner", "CURRENT_REFERENCE", Double.NaN, 127.1, 1000));
                    var reference = new org.json.JSONObject().put("tripId", id).put("owner", "metrics-owner")
                            .put("fetchedAt", java.time.Instant.now().toString()).put("scenarios", new org.json.JSONArray());
                    recorder.applyTripPriceReference(id, reference);
                    assertNotNull(recorder.summary().data.optJSONObject("currentPriceReference"));
                    assertThrows(IllegalArgumentException.class, () -> recorder.applyTripPriceReference(id, new org.json.JSONObject(reference.toString()).put("owner", "other")));
                    var syncField = HomeRecorder.class.getDeclaredField("sync"); syncField.setAccessible(true);
                    var stateField = RecordingSync.class.getDeclaredField("state"); stateField.setAccessible(true);
                    var state = (org.json.JSONObject) stateField.get(syncField.get(recorder));
                    state.getJSONObject("intervals").getJSONObject(id).put("summary", new org.json.JSONObject()
                            .put("quality", new org.json.JSONObject().put("missing", true)));
                    assertTrue(Double.isNaN(recorder.summary().consumedKwh()));
                    recorder.pause();
                    assertNull(recorder.visibleLocation(id));
                } catch (Exception error) { throw new AssertionError(error); }
            });
        } finally {
            connection.authenticatedSubject = previous;
            target.deleteSharedPreferences(prefix + "home_recording");
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    @Test public void openingAndClosingIdleRecorderPreservesPreviousSaveTime() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "saved-time-test-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
        };
        SharedPreferences store = isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
        long previous = 1794013200000L;
        assertTrue(store.edit().putLong("savedAt", previous).commit());
        HomeRecorder[] recorder = new HomeRecorder[1];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0] = new HomeRecorder(isolated));
            assertEquals(previous, recorder[0].trip.savedAt);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0].pause());
            assertEquals(previous, store.getLong("savedAt", 0));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0] = new HomeRecorder(isolated));
            assertEquals(previous, recorder[0].trip.savedAt);
        } finally {
            target.deleteSharedPreferences(prefix + "home_recording");
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
