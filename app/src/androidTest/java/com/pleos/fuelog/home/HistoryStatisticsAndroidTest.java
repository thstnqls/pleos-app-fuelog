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
            connection.authenticatedName = "driver@example.com";
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
                        assertNotNull(root.findViewWithTag("navigation.5"));
                        assertFalse(contains(root, "운행 요약 새로 확인"));
                        assertFalse(contains(root, "API 연결 확인됨"));
                        assertNull(root.findViewWithTag("trip.history.card.private"));
                        snapshot(activity, "trip");
                        JSONArray many = new JSONArray();
                        for (int i = 0; i < 12; i++) many.put(trip("page-" + i, "owner", at, 1));
                        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit()
                                .putString("history", many.toString()).commit();
                        render(activity);
                        assertTrue(contains(root, "12건 · 합계 12.0 km"));
                        assertNull(root.findViewWithTag("trip.history.card.page-0"));
                        root.findViewWithTag("history.more").performClick();
                        assertNotNull(root.findViewWithTag("trip.history.card.page-0"));
                        assertNull(root.findViewWithTag("history.more"));
                        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit()
                                .putString("history", new JSONArray().put(first).put(latest).toString()).commit();
                        render(activity);
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
                        field(CarHomeActivity.class, "page").set(activity, 0); render(activity);
                        ViewGroup navigation = (ViewGroup) field(CarHomeActivity.class, "navigation").get(activity);
                        int[] pages = {2, 3, 0, 4, 5};
                        assertEquals(5, navigation.getChildCount());
                        for (int i = 0; i < pages.length; i++) assertEquals("navigation." + pages[i], navigation.getChildAt(i).getTag());
                        assertTrue(navigation.getChildAt(2).isSelected());
                        assertFalse(contains(root, "이번 운행 · D → P"));
                        assertFalse(contains(root, "확인할 항목"));
                        assertFalse(contains(root, "운행 요약"));
                        assertFalse(contains(root, "연결 상태"));
                        field(CarHomeActivity.class, "page").set(activity, 3);
                        JSONArray charges = new JSONArray();
                        for (int i = 0; i < 12; i++) charges.put(new JSONObject().put("id", "charge-" + i)
                                .put("status", "CANDIDATE").put("startAt", at).put("endAt", at + 1000).put("estimatedKwh", 2)
                                .put("scenarios", new JSONArray().put(new JSONObject().put("powerBand", "SLOW")
                                        .put("averageWonPerKwh", "100").put("stations", new JSONArray().put(new JSONObject()
                                                .put("name", "참고 충전소 A").put("address", "서울 테스트로 1").put("wonPerKwh", "100"))))));
                        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit().putString("history",
                                new JSONArray().put(new JSONObject().put("intervalId", "charging").put("owner", "owner")
                                        .put("evDetected", true).put("chargingOnly", true).put("endedAt", at + 1000)
                                        .put("chargeCandidates", charges)).toString()).commit();
                        render(activity);
                        assertTrue(contains(root, "12건 · 합계 24.0 kWh"));
                        assertTrue(contains(root, "참고 충전소 · 참고 충전소 A · 서울 테스트로 1 · 100원/kWh"));
                        assertNotNull(root.findViewWithTag("history.more"));
                        snapshot(activity, "charge");
                        root.findViewWithTag("history.more").performClick();
                        assertNull(root.findViewWithTag("history.more"));
                        root.findViewWithTag("charge.metric.1").performClick();
                        assertTrue(contains(root, "12건 · 합계 2,400.0 원"));
                        connection.vehicleAlias = "우리 차";
                        ((ConnectionSettings) field(CarHomeActivity.class, "settings").get(activity)).snapshot.put("deviceAlias", "내 휴대폰");
                        field(CarHomeActivity.class, "maintenanceSummary").set(activity, new MaintenanceSummary(new JSONObject()
                                .put("manufacturerDefaults", new JSONArray()
                                        .put(new JSONObject().put("name", "브레이크 오일").put("intervalMonths", 24))
                                        .put(new JSONObject().put("name", "에어컨 필터").put("intervalMonths", 12)))
                                .put("items", new JSONArray()
                                        .put(new JSONObject().put("name", "일반 정비").put("nextDueDate", "2020-01-01"))
                                        .put(new JSONObject().put("name", "위험 정비").put("riskLevel", "DANGER")))));
                        field(CarHomeActivity.class, "maintenanceVehicleId").set(activity, connection.vehicleId);
                        field(CarHomeActivity.class, "page").set(activity, 0); render(activity);
                        View urgent = root.findViewWithTag("home.maintenance");
                        assertTrue(contains(urgent, "위험 정비"));
                        assertFalse(contains(urgent, "일반 정비"));
                        assertNotNull(root.findViewWithTag("home.connection"));
                        assertTrue(contains(root.findViewWithTag("home.connection"), "우리 차 ↔ driver@example.com"));
                        assertFalse(contains(root.findViewWithTag("home.connection"), "내 휴대폰"));
                        assertFalse(contains(root.findViewWithTag("home.connection"), "현재 사용자 ·"));
                        assertFalse(contains(root, "우리 차 · 정차 상태"));
                        snapshot(activity, "home");
                        field(CarHomeActivity.class, "page").set(activity, 4); render(activity);
                        assertNull(root.findViewWithTag("vehicle.odometer"));
                        assertFalse(contains(root, "모바일에서 기록"));
                        snapshot(activity, "maintenance");
                        connection.authenticatedName = "driver@example.com";
                        field(CarHomeActivity.class, "page").set(activity, 5); render(activity);
                        assertNotNull(root.findViewWithTag("settings.alias.edit"));
                        assertNotNull(root.findViewWithTag("settings.collection"));
                        assertNotNull(root.findViewWithTag("settings.chargeNotifications"));
                        assertTrue(contains(root, "driver@example.com"));
                        assertFalse(contains(root, "설치 등록 방식"));
                        assertFalse(contains(root, "연결 상태 새로 확인  →"));
                        assertFalse(contains(root, "VIN 다시 조회  →"));
                        assertFalse(contains(root, "API 연결 확인됨"));
                        assertFalse(contains(root, "연결 상세 확인  →"));
                        snapshot(activity, "settings");
                        isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).edit()
                                .putString("history", new JSONArray().put(first).put(latest).toString()).commit();
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
    private static void snapshot(CarHomeActivity activity, String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) return;
        View view = (View) field(CarHomeActivity.class, "body").get(activity);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, view.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(activity.getColor(com.pleos.fuelog.R.color.car_background));
        view.draw(canvas);
        try (var output = new java.io.FileOutputStream(new File(activity.getCacheDir(), "ui-" + name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
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
