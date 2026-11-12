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
            @Override public int checkSelfPermission(String permission) {
                return com.pleos.fuelog.location.PleosLocationClient.PERMISSION.equals(permission)
                        ? android.content.pm.PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
            }
        };
        var connection = com.pleos.fuelog.connection.ConnectionSession.get(isolated);
        String previous = connection.authenticatedSubject;
        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit().putString("owner", "metrics-owner").commit();
        try {
            connection.authenticatedSubject = "metrics-owner";
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    HomeRecorder recorder = new HomeRecorder(isolated);
                    recorder.attachScreen(()->{});
                    recorder.toggle();
                    assertTrue(recorder.trip.active);
                    recorder.trip.sample(1000, 1000); recorder.trip.sample(1060, 2000);
                    var field = HomeRecorder.class.getDeclaredField("ev"); field.setAccessible(true);
                    EvRecording ev = (EvRecording) field.get(recorder);
                    var data = EvRecording.class.getDeclaredField("data"); data.setAccessible(true);
                    ((org.json.JSONObject) data.get(ev)).put("chargePriceValid", true)
                            .put("chargeUnitPriceWonPerKwh", 350).put("chargePriceSource", "confirmed-charge:test");
                    ev.sample(false, 40, "AAOS_WH", 1000); ev.sample(false, 36, "AAOS_WH", 2000);
                    var save = HomeRecorder.class.getDeclaredMethod("saveEv"); save.setAccessible(true); save.invoke(recorder);
                    TripSummary result = recorder.summary();
                    assertNotNull(result);
                    assertEquals(60, result.data.getDouble("distanceKm"), 0);
                    assertEquals(4, result.consumedKwh(), 0);
                    assertEquals(15, result.evEconomy(), 0);
                    assertTrue(Double.isNaN(result.evCost()));
                    String id = result.data.getString("intervalId");
                    recorder.rememberLocation(id, "metrics-owner", "CURRENT_REFERENCE", 37.5, 127.1, 1000);
                    assertEquals(37.5, recorder.visibleLocation(id).getDouble("latitude"), 0);
                    assertThrows(IllegalArgumentException.class, () -> recorder.rememberLocation(id, "other", "CURRENT_REFERENCE", 37.5, 127.1, 1000));
                    assertThrows(IllegalArgumentException.class, () -> recorder.rememberLocation(id, "metrics-owner", "CURRENT_REFERENCE", Double.NaN, 127.1, 1000));
                    recorder.endForSwitch();
                    assertFalse(recorder.trip.active);
                    assertEquals(1,new org.json.JSONArray(isolated.getSharedPreferences("home_recording",Context.MODE_PRIVATE).getString("history","[]")).length());
                    assertEquals("LOCATION_REQUIRED",recorder.summary().data.getString("evCostStatus"));
                    assertEquals(60,recorder.summary().data.getDouble("distanceKm"),0);
                    assertTrue(Double.isNaN(recorder.summary().evCost()));
                    assertFalse(RecordingPayloads.upload(recorder.summary().data).getBoolean("priceValid"));
                    var cost=new org.json.JSONObject().put("amount",1320).put("currency","KRW").put("averageWonPerKwh",330).put("consumedKwh",4)
                            .put("tariffDate","2026-11-09").put("calculatedAt","2026-11-11T00:00:00Z")
                            .put("basis","SLOW_THREE_OPERATOR_MEAN").put("sourceCount",3).put("locationBasis","CONFIRMATION");
                    var saved=new org.json.JSONObject().put("tripId",id).put("owner","metrics-owner").put("status","SAVED").put("cost",cost);
                    recorder.applySavedTripCost(id,saved);
                    assertEquals(1320,recorder.summary().evCost(),0);
                    recorder.applySavedTripCost(id,new org.json.JSONObject(saved.toString()).put("cost",new org.json.JSONObject(cost.toString()).put("amount",9999)));
                    assertEquals("A saved amount cannot be overwritten",1320,recorder.summary().evCost(),0);
                    assertThrows(IllegalArgumentException.class,()->recorder.applySavedTripCost(id,new org.json.JSONObject(saved.toString()).put("owner","other")));
                    var syncField = HomeRecorder.class.getDeclaredField("sync"); syncField.setAccessible(true);
                    var stateField = RecordingSync.class.getDeclaredField("state"); stateField.setAccessible(true);
                    var state = (org.json.JSONObject) stateField.get(syncField.get(recorder));
                    state.getJSONObject("intervals").getJSONObject(id).put("summary", new org.json.JSONObject()
                            .put("quality", new org.json.JSONObject().put("missing", true)));
                    assertTrue(Double.isNaN(recorder.summary().consumedKwh()));
                    assertEquals(1320,recorder.summary().evCost(),0);
                    recorder.pause();
                    assertNull(recorder.visibleLocation(id));
                    HomeRecorder restored=new HomeRecorder(isolated);
                    assertEquals(1320,restored.summary().evCost(),0);
                    assertEquals("2026-11-11T00:00:00Z",restored.summary().data.getJSONObject("savedEvCost").getString("calculatedAt"));
                    assertEquals(1320,restored.completedTrip(id).evCost(),0);
                    assertNull(restored.completedTrip("unknown"));
                    restored.pause();
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

    @Test public void switchOnWhileParkedCreatesOneChargeIntervalAndOffClosesIt() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "switch-charge-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix); assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        var connection = com.pleos.fuelog.connection.ConnectionSession.get(isolated);
        String priorUser = connection.authenticatedSubject;
        boolean priorLinked = connection.linked;
        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit().putString("owner", "charge-owner").commit();
        try {
            connection.authenticatedSubject = "charge-owner"; connection.linked = false;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    HomeRecorder recorder = new HomeRecorder(isolated);
                    recorder.attachScreen(()->{});
                    recorder.resume(()->{});
                    recorder.detachScreen();
                    var visible = HomeRecorder.class.getDeclaredField("visible"); visible.setAccessible(true);
                    assertTrue("Detaching the screen must leave service collection running",visible.getBoolean(recorder));
                    var generation = HomeRecorder.class.getDeclaredField("evGeneration"); generation.setAccessible(true); generation.set(recorder, 1);
                    var accept = HomeRecorder.class.getDeclaredMethod("acceptEv", int.class, String.class, String.class, Boolean.class, double.class, String.class, Runnable.class);
                    accept.setAccessible(true);
                    String interval = recorder.intervalId;
                    accept.invoke(recorder, 1, "charge-owner", interval, false, 30d, "AAOS_WH", (Runnable) () -> {});
                    assertFalse(recorder.trip.active);
                    accept.invoke(recorder, 1, "charge-owner", interval, true, 30d, "AAOS_WH", (Runnable) () -> {});
                    assertTrue(recorder.chargingInterval());
                    accept.invoke(recorder, 1, "charge-owner", interval, true, 32d, "AAOS_WH", (Runnable) () -> {});
                    accept.invoke(recorder, 1, "charge-owner", interval, false, 33d, "AAOS_WH", (Runnable) () -> {});
                    assertFalse(recorder.trip.active);
                    TripSummary summary = recorder.summary(); assertNotNull(summary);
                    assertTrue(summary.data.getBoolean("chargingOnly"));
                    assertFalse(summary.data.getBoolean("distanceValid"));
                    var charges = summary.data.getJSONArray("chargeCandidates");
                    assertEquals(1, charges.length());
                    assertEquals(3, charges.getJSONObject(0).getDouble("estimatedKwh"), 0);
                    assertEquals(interval, summary.data.getString("intervalId"));
                    assertNotNull(recorder.completedTrip(interval));
                    var notice=recorder.pendingChargeNotice();
                    assertNotNull("A positive switch ON/OFF interval must leave a durable notification",notice);
                    assertEquals(interval,notice.getString("intervalId"));
                    recorder.markChargeNotified(notice);
                    assertNull(recorder.pendingChargeNotice());
                    HomeRecorder restored=new HomeRecorder(isolated);
                    assertNull("Already notified charging records must not alert after recreation",restored.pendingChargeNotice());
                    assertNotNull(restored.completedTrip(interval));
                    restored.pause();
                    assertEquals(1, new org.json.JSONArray(isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).getString("history", "[]")).length());
                    recorder.toggle();
                    String next = recorder.intervalId;
                    int nextRequest = generation.getInt(recorder);
                    var finishAfterEv = HomeRecorder.class.getDeclaredField("finishAfterEv"); finishAfterEv.setAccessible(true); finishAfterEv.set(recorder, true);
                    accept.invoke(recorder, nextRequest, "charge-owner", next, true, 33d, "AAOS_WH", (Runnable) () -> {});
                    assertFalse("A driving interval must still close at P even if the switch is ON", recorder.trip.active);
                    assertFalse(recorder.summary().data.getBoolean("chargingOnly"));
                    recorder.pause();
                } catch (Exception error) { throw new AssertionError(error); }
            });
        } finally {
            connection.authenticatedSubject = priorUser; connection.linked = priorLinked;
            target.deleteSharedPreferences(prefix + "home_recording");
            File[] files = directory.listFiles(); if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
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
