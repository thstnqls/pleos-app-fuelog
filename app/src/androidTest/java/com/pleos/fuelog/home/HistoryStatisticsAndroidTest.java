package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.connection.ConnectionSettings;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryStatisticsAndroidTest {
    @Test public void chartsFilterOwnersAndCardsOpenTheSelectedTripWithSplit() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "history-statistics-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        Field sessions = field(ConnectionSession.class, "instance"), recorders = field(HomeRecorder.class, "instance");
        Object previousSession = sessions.get(null), previousRecorder = recorders.get(null);
        long at = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        JSONObject first = trip("older", "owner", at, 10), latest = trip("latest", "owner", at + 1000, 20);
        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit().putString("owner", "owner")
                .putString("history", new JSONArray().put(first).put(latest).put(trip("private", "other", at, 1000)).toString())
                .putString("fuelCandidates", new JSONObject().put("entries", new JSONArray()
                        .put(fuel("owner", at, "CONFIRMED", 10)).put(fuel("owner", at, "CANDIDATE", 20))
                        .put(fuel("owner", at, "REJECTED", 40)).put(fuel("other", at, "CONFIRMED", 1000))).toString()).commit();
        new EncryptedJsonStore(new File(directory, "recording-outbox.bin")).write(new JSONObject()
                .put("jobs", new JSONArray()).put("intervals", new JSONObject().put("older", new JSONObject()
                        .put("id", UUID.randomUUID().toString()).put("subjectId", "owner").put("ended", true))).toString());
        try {
            var constructor = ConnectionSession.class.getDeclaredConstructor(Context.class, ApiClient.class);
            constructor.setAccessible(true);
            ConnectionSession connection = constructor.newInstance(isolated, new ApiClient("http://127.0.0.1:1/"));
            connection.authenticatedSubject = "owner"; connection.state = "ERROR";
            sessions.set(null, connection);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recordersSet(recorders, new HomeRecorder(isolated)));
            HomeRecorder recorder = HomeRecorder.get(isolated);
            try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        connection.linked = true;
                        field(CarHomeActivity.class, "allowed").set(activity, true);
                        field(CarHomeActivity.class, "page").set(activity, 2);
                        field(CarHomeActivity.class, "settings").set(activity, new ConnectionSettings(isolated, connection, recorder));
                        render(activity);
                        View root = activity.findViewById(android.R.id.content);
                        assertTrue(contains(root, "2건 · 합계 30.0 km"));
                        assertNull(root.findViewWithTag("trip.history.card.private"));
                        root.findViewWithTag("statistics.0.period.0").performClick();
                        assertEquals(24, ((ViewGroup) root.findViewWithTag("statistics.0.chart")).getChildCount());
                        root.findViewWithTag("statistics.0.period.2").performClick();
                        assertEquals(12, ((ViewGroup) root.findViewWithTag("statistics.0.chart")).getChildCount());
                        root.findViewWithTag("trip.history.card.older").performClick();
                        assertEquals("older", field(CarHomeActivity.class, "selectedTripId").get(activity));
                        assertTrue(contains(root, "운행 상세"));
                        assertTrue(root.findViewWithTag("trip.detail.split").isEnabled());
                        root.findViewWithTag("trip.detail.split").performClick();
                        AlertDialog dialog = (AlertDialog) field(CarHomeActivity.class, "tripSplitAlert").get(activity);
                        assertTrue(dialog.isShowing());
                        assertTrue(contains(dialog.getWindow().getDecorView(), "2명 · 1인 3400원"));
                        connection.authenticatedSubject = "other"; render(activity);
                        assertFalse(dialog.isShowing());
                        assertFalse(contains(root, "운행 상세"));
                        connection.authenticatedSubject = "owner";
                        field(CarHomeActivity.class, "page").set(activity, 3); render(activity);
                        assertTrue(contains(root, "1건 · 합계 10.0 L"));
                        assertTrue(contains(root, "확인 전 · 통계 제외"));
                        assertTrue(contains(root, "기각됨 · 통계 제외"));
                        connection.linked = false; recorder.sync.pause();
                        recorder.enqueueMobile("older");
                        assertTrue(recorder.mobileQueued("older"));
                        assertFalse(recorder.mobileQueued("latest"));
                        field(CarHomeActivity.class, "allowed").set(activity, false); render(activity);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
            }
        } finally {
            sessions.set(null, previousSession); recorders.set(null, previousRecorder);
            target.deleteSharedPreferences(prefix + "home_recording");
            target.deleteSharedPreferences(prefix + "connection_settings");
            File[] files = directory.listFiles(); if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static void recordersSet(Field field, HomeRecorder recorder) {
        try { field.set(null, recorder); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static JSONObject trip(String id, String owner, long at, double distance) throws Exception {
        return new JSONObject().put("intervalId", id).put("owner", owner).put("candidateId", "")
                .put("startedAt", at - 60000).put("endedAt", at).put("distanceValid", true).put("distanceKm", distance)
                .put("startFuelLiters", 40).put("endFuelLiters", 36).put("fuelEndpointsValid", true)
                .put("unitPrice", 1700).put("currency", "KRW").put("priceValid", true).put("priceSource", "confirmed");
    }
    private static JSONObject fuel(String owner, long at, String status, double liters) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("owner", owner).put("endAt", at)
                .put("status", status).put("capacityVerified", true).put("estimatedLiters", liters).put("estimatedAmount", liters * 1700);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void render(CarHomeActivity activity) throws Exception {
        var method = CarHomeActivity.class.getDeclaredMethod("render"); method.setAccessible(true); method.invoke(activity);
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
}
