package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.connection.ConnectionSettings;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartScreenAndroidTest {
    @Test public void sharedMaintenanceTabWorksWithoutPairedUser() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "start-tabs-test-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
        };
        Field sessionInstance = ConnectionSession.class.getDeclaredField("instance");
        Field recorderInstance = HomeRecorder.class.getDeclaredField("instance");
        sessionInstance.setAccessible(true); recorderInstance.setAccessible(true);
        Object previousSession = sessionInstance.get(null), previousRecorder = recorderInstance.get(null);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"odometer\":null,\"items\":[{\"name\":\"Shared oil\",\"basis\":\"USER\",\"intervalKm\":10000,\"intervalMonths\":12}]}"));
            var constructor = ConnectionSession.class.getDeclaredConstructor(Context.class, ApiClient.class);
            constructor.setAccessible(true);
            ConnectionSession connection = constructor.newInstance(isolated, new ApiClient(server.url("/").toString()));
            connection.state = "ERROR";
            connection.vehicleId = UUID.randomUUID().toString();
            connection.vehicleName = "Registered car";
            sessionInstance.set(null, connection);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { recorderInstance.set(null, new HomeRecorder(isolated)); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            VehicleOdometerAndroidTest.FakeOdometer source = new VehicleOdometerAndroidTest.FakeOdometer();
            try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        set(activity, "allowed", true);
                        set(activity, "page", 6);
                        set(activity, "settings", new ConnectionSettings(isolated, connection, HomeRecorder.get(isolated)));
                        VehicleOdometer reading = new VehicleOdometer(source.api, () -> {
                            try {
                                var refresh = CarHomeActivity.class.getDeclaredMethod("render");
                                refresh.setAccessible(true); refresh.invoke(activity);
                            } catch (Exception error) { throw new AssertionError(error); }
                        });
                        set(activity, "vehicleOdometer", reading);
                        reading.read();
                        source.read.invoke(12345f);
                        var render = CarHomeActivity.class.getDeclaredMethod("render");
                        render.setAccessible(true); render.invoke(activity);
                        View root = activity.findViewById(android.R.id.content);
                        assertNull(root.findViewWithTag("start.tabs"));
                        assertNotNull(root.findViewWithTag("start.qr"));
                        assertNotNull(root.findViewWithTag("start.vehicle"));
                        assertTrue(contains(root.findViewWithTag("start.qr"), "QR 연결"));
                        assertTrue(contains(root.findViewWithTag("start.vehicle"), "차량 기본정보"));
                        android.widget.EditText alias = root.findViewWithTag("pairing.vehicleAlias");
                        assertNotNull(alias);
                        alias.requestFocus(); alias.setText("우리 차"); alias.setSelection(1);
                        render.invoke(activity);
                        assertSame(alias, root.findViewWithTag("pairing.vehicleAlias"));
                        assertEquals("우리 차", alias.getText().toString());
                        assertEquals(1, alias.getSelectionStart());
                        alias.clearFocus(); render.invoke(activity);
                        android.widget.EditText restored = root.findViewWithTag("pairing.vehicleAlias");
                        assertEquals("우리 차", restored.getText().toString());
                        restored.setText(""); restored.clearFocus();
                        var subscribe = CarHomeActivity.class.getDeclaredMethod("startVehicleOdometer");
                        subscribe.setAccessible(true); subscribe.invoke(activity);
                        var maintenance = CarHomeActivity.class.getDeclaredMethod("loadMaintenance");
                        maintenance.setAccessible(true);
                        if (get(activity, "maintenanceCall") == null && get(activity, "maintenanceSummary") == null)
                            maintenance.invoke(activity);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                assertEquals("/maintenance/summary", server.takeRequest(5, TimeUnit.SECONDS).getPath());
                boolean[] loaded = {false};
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (!loaded[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity(activity -> loaded[0] = contains(activity.findViewById(android.R.id.content), "Shared oil"));
                    if (!loaded[0]) android.os.SystemClock.sleep(25);
                }
                assertTrue("Shared maintenance should be visible without a paired user", loaded[0]);
                scenario.onActivity(activity -> {
                    View root = activity.findViewById(android.R.id.content);
                    assertTrue(contains(root, "차량 공용 정비 주기"));
                    android.widget.Button refresh = root.findViewWithTag("maintenance.refresh");
                    assertEquals("정비 정보 갱신", refresh.getText().toString());
                    assertFalse(((android.graphics.drawable.AnimatedVectorDrawable) refresh.getCompoundDrawablesRelative()[0]).isRunning());
                    assertNull(root.findViewWithTag("maintenance.manage"));
                    assertTrue(contains(root, "정비 주기 · 10,000 km 또는 12개월"));
                    assertTrue(contains(root, "주기 출처 · 차량 설정"));
                    assertTrue(contains(root, "마지막 정비 기록을 확인해 주세요"));
                    assertFalse(contains(root, "현재 차량 거리 확인  →"));
                    assertFalse(contains(root, "현재 배터리 확인  →"));
                    assertTrue(contains(root.findViewWithTag("vehicle.odometer"), "12,345.0 km"));
                    assertEquals("Tab entry must subscribe once", 1, source.registrations);
                    assertNotNull(source.listener);
                });
                assertFalse(connection.linked);
                assertEquals("", connection.authenticatedSubject);
                scenario.onActivity(activity -> {
                    try {
                        // The same vehicle reading must also appear on the paired home page.
                        connection.linked = true;
                        set(activity, "page", 0);
                        var render = CarHomeActivity.class.getDeclaredMethod("render");
                        render.setAccessible(true); render.invoke(activity);
                        View root = activity.findViewById(android.R.id.content);
                        assertTrue(contains(root.findViewWithTag("vehicle.odometer"), "12,345.0 km"));
                        assertNull(root.findViewWithTag("maintenance.manage"));
                        assertEquals("Navigation must keep the existing subscription", 1, source.registrations);
                        assertFalse(HomeRecorder.get(isolated).trip.active);
                        source.listener.onDistanceDrivenUpdated(12346f);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> assertTrue(contains(
                        activity.findViewById(android.R.id.content).findViewWithTag("vehicle.odometer"), "12,346.0 km")));
                String owner = UUID.randomUUID().toString();
                server.enqueue(new MockResponse().setBody("{\"items\":[]}"));
                scenario.onActivity(activity -> {
                    try {
                        connection.authenticatedSubject = owner;
                        HomeRecorder.get(isolated).owner = owner;
                        org.json.JSONObject common = new org.json.JSONObject().put("canEdit", true).put("editorSubjectId", owner)
                                .put("items", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "Shared oil").put("basis","USER")
                                        .put("intervalMonths", 12).put("nextDueDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(30).toString())));
                        common.put("manufacturerDefaults",new org.json.JSONArray().put(new org.json.JSONObject()
                                .put("id","selectable-default").put("name","제조사 기본 필터").put("intervalMonths",12)
                                .put("evidence",new org.json.JSONObject().put("verified",true).put("model","다른 모델").put("modelYearFrom",2020).put("modelYearTo",2021))));
                        set(activity, "maintenanceSummary", new MaintenanceSummary(common));
                        set(activity, "maintenanceVehicleId", connection.vehicleId);
                        assertEquals(owner, HomeRecorder.get(isolated).verifiedOwner());
                        set(activity, "page", 4);
                        var render = CarHomeActivity.class.getDeclaredMethod("render"); render.setAccessible(true); render.invoke(activity);
                        View root = activity.findViewById(android.R.id.content);
                        assertTrue(contains(root, "교체 D-30 · 30일 남음"));
                        assertTrue("Verified documents remain selectable without a matching model/year",
                                root.findViewWithTag("maintenance.default.select.selectable-default").isEnabled());
                        assertTrue(root.findViewWithTag("maintenance.default.select.selectable-default").performClick());
                        Object selectedEditor=get(activity,"maintenanceEditor");
                        assertTrue(((androidx.appcompat.app.AlertDialog)get(selectedEditor,"dialog")).isShowing());
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    try {
                        View root = activity.findViewById(android.R.id.content);
                        var closeEditor=CarHomeActivity.class.getDeclaredMethod("closeMaintenanceEditor");closeEditor.setAccessible(true);closeEditor.invoke(activity);
                        assertNotNull(root.findViewWithTag("maintenance.manage"));
                        root.findViewWithTag("maintenance.manage").performClick();
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                assertEquals("/maintenance/plans", server.takeRequest(5, TimeUnit.SECONDS).getPath());
                androidx.appcompat.app.AlertDialog[] form = {null};
                boolean[] menuReady = {false};
                long menuDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (!menuReady[0] && android.os.SystemClock.elapsedRealtime() < menuDeadline) {
                    scenario.onActivity(activity -> {
                        try {
                            Object editor = get(activity, "maintenanceEditor");
                            menuReady[0] = editor != null && get(editor, "dialog") != null;
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    });
                    if (!menuReady[0]) android.os.SystemClock.sleep(25);
                }
                assertTrue(menuReady[0]);
                scenario.onActivity(activity -> {
                    try {
                        Object editor = get(activity, "maintenanceEditor");
                        androidx.appcompat.app.AlertDialog menu = (androidx.appcompat.app.AlertDialog) get(editor, "dialog");
                        if(menu.getListView()!=null) menu.getListView().performItemClick(menu.getListView().getChildAt(0), 0, 0);
                        form[0] = (androidx.appcompat.app.AlertDialog) get(editor, "dialog");
                        View fields = form[0].findViewById(android.R.id.content);
                        for (String label : new String[]{"항목명", "거리 주기 (km)", "기간 주기 (개월)",
                                "마지막 교체일", "교체 당시 누적거리 (km)"}) {
                            android.view.inputmethod.EditorInfo info = new android.view.inputmethod.EditorInfo();
                            assertNotNull(findInput(fields, label).onCreateInputConnection(info));
                            assertTrue((info.imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
                            assertNull(info.hintText);
                        }
                        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                                form[0].getWindow().getAttributes().softInputMode
                                        & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
                        findInput(fields, "항목명").setText("타이어");
                        findInput(fields, "기간 주기 (개월)").setText("12");
                        findInput(fields, "마지막 교체일").setText("2026-01-01");
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                server.enqueue(new MockResponse().setBody("{\"revision\":1}"));
                server.enqueue(new MockResponse().setBody(new org.json.JSONObject().put("canEdit", true).put("editorSubjectId", owner)
                        .put("items", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "타이어").put("basis", "USER")
                                .put("intervalMonths", 12).put("lastServiceDate", "2026-01-01").put("nextDueDate", "2027-01-01"))).toString()));
                scenario.onActivity(activity -> form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
                var write = server.takeRequest(5, TimeUnit.SECONDS);
                assertEquals("PUT", write.getMethod());
                assertTrue(write.getPath().startsWith("/maintenance/plans/"));
                assertEquals("0", write.getHeader("If-Match"));
                org.json.JSONObject submitted = new org.json.JSONObject(write.getBody().readUtf8());
                assertTrue(submitted.getJSONObject("plan").getBoolean("shared"));
                assertEquals(12, submitted.getJSONObject("plan").getInt("intervalMonths"));
                assertEquals("2026-01-01", submitted.getString("lastServiceDate"));
                assertEquals("/maintenance/summary", server.takeRequest(5, TimeUnit.SECONDS).getPath());
                MaintenancePlanEditor[] completionEditor = {null};
                int[] completed = {0};
                scenario.onActivity(activity -> {
                    assertFalse(form[0].isShowing());
                    try {
                        completionEditor[0] = new MaintenancePlanEditor(activity, connection, () -> true, () -> completed[0]++);
                        var showCompletion = MaintenancePlanEditor.class.getDeclaredMethod("form", org.json.JSONObject.class);
                        showCompletion.setAccessible(true);
                        showCompletion.invoke(completionEditor[0], new org.json.JSONObject().put("id", "cycle-id").put("revision", 4)
                                .put("name", "타이어").put("intervalKm", 10000));
                        androidx.appcompat.app.AlertDialog edit = (androidx.appcompat.app.AlertDialog) get(completionEditor[0], "dialog");
                        edit.findViewById(android.R.id.content).findViewWithTag("maintenance.complete").performClick();
                        form[0] = (androidx.appcompat.app.AlertDialog) get(completionEditor[0], "dialog");
                        View fields = form[0].findViewById(android.R.id.content);
                        findInput(fields, "실제 교체일 · YYYY-MM-DD").setText("2026-01-10");
                        findInput(fields, "교체 당시 누적거리 km · 필수").setText("52000");
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
                scenario.onActivity(activity -> {
                    form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
                    form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
                });
                var firstCompletion = server.takeRequest(5, TimeUnit.SECONDS);
                assertEquals("POST", firstCompletion.getMethod());
                assertEquals("/maintenance/plans/cycle-id/complete", firstCompletion.getPath());
                assertEquals("4", firstCompletion.getHeader("If-Match"));
                assertNotNull(firstCompletion.getHeader("Idempotency-Key"));
                String completionBody = firstCompletion.getBody().readUtf8();
                org.json.JSONObject confirmation = new org.json.JSONObject(completionBody);
                assertTrue(confirmation.getBoolean("confirmed"));
                assertEquals("2026-01-10", confirmation.getString("serviceDate"));
                assertEquals(52000, confirmation.getDouble("odometerKm"), 0);
                boolean[] retryReady = {false};
                long retryDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (!retryReady[0] && android.os.SystemClock.elapsedRealtime() < retryDeadline) {
                    scenario.onActivity(activity -> retryReady[0] = form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled());
                    if (!retryReady[0]) android.os.SystemClock.sleep(25);
                }
                assertTrue(retryReady[0]);
                server.enqueue(new MockResponse().setBody("{\"event\":{},\"completedPlan\":{},\"nextPlan\":{\"id\":\"next-cycle\",\"revision\":1}}"));
                scenario.onActivity(activity -> form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
                var retryCompletion = server.takeRequest(5, TimeUnit.SECONDS);
                assertEquals(firstCompletion.getHeader("Idempotency-Key"), retryCompletion.getHeader("Idempotency-Key"));
                assertEquals(completionBody, retryCompletion.getBody().readUtf8());
                long completionDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (completed[0] == 0 && android.os.SystemClock.elapsedRealtime() < completionDeadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    android.os.SystemClock.sleep(25);
                }
                assertEquals(1, completed[0]);
                String defaultId=UUID.randomUUID().toString();
                scenario.onActivity(activity->{
                    try {
                        completionEditor[0]=new MaintenancePlanEditor(activity,connection,()->true,()->completed[0]++);
                        completionEditor[0].startManufacturer(new org.json.JSONObject().put("id",defaultId).put("name","제조사 필터")
                                .put("intervalKm",10000).put("intervalMonths",12).put("evidence",new org.json.JSONObject().put("verified",true)));
                        form[0]=(androidx.appcompat.app.AlertDialog)get(completionEditor[0],"dialog");
                        View fields=form[0].findViewById(android.R.id.content);
                        assertEquals("완료 기록 · 다음 일정 저장",form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
                        findInput(fields,"시작일 · 첫 정비 완료일").setText("2026-01-10");
                        findInput(fields,"교체 당시 누적거리 km · 필수").setText("52000");
                    }catch(Exception error){throw new AssertionError(error);}
                });
                server.enqueue(new MockResponse().setBody("{\"event\":{},\"completedPlan\":{},\"nextPlan\":{\"id\":\"next-default\"}}"));
                scenario.onActivity(activity->form[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
                var startRequest=server.takeRequest(5,TimeUnit.SECONDS);
                assertEquals("/maintenance/manufacturer-defaults/"+defaultId+"/start",startRequest.getPath());
                assertNotNull(startRequest.getHeader("Idempotency-Key"));
                var baseline=new org.json.JSONObject(startRequest.getBody().readUtf8());
                assertEquals("2026-01-10",baseline.getString("serviceDate"));assertTrue(baseline.getBoolean("confirmed"));
                long startDeadline=android.os.SystemClock.elapsedRealtime()+5000;
                while(completed[0]<2 && android.os.SystemClock.elapsedRealtime()<startDeadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(25);
                }
                assertEquals(2,completed[0]);
                scenario.onActivity(activity -> {
                    assertFalse(form[0].isShowing());
                    try {
                        HomeRecorder.get(isolated).rememberLocation("display-location", owner, "CURRENT_REFERENCE", 37.5, 127.1, System.currentTimeMillis());
                        var renderLocation = CarHomeActivity.class.getDeclaredMethod("renderLocation", String.class);
                        renderLocation.setAccessible(true); renderLocation.invoke(activity, "display-location");
                        var address = CarHomeActivity.class.getDeclaredMethod("renderStationAddress", org.json.JSONObject.class);
                        address.setAccessible(true); address.invoke(activity, new org.json.JSONObject().put("address", "서울 테스트로 1"));
                        View root = activity.findViewById(android.R.id.content);
                        assertTrue("latitude", contains(root, "37.500000")); assertTrue("longitude", contains(root, "127.100000")); assertTrue("address", contains(root, "주소 · 서울 테스트로 1"));
                        set(activity, "page", 5);
                        var refresh = CarHomeActivity.class.getDeclaredMethod("render"); refresh.setAccessible(true);
                        refresh.invoke(activity);
                        assertEquals(View.GONE, ((View) get(activity, "headerNavigation")).getVisibility());
                        assertNotNull(root.findViewWithTag("navigation.5"));
                        isolated.getSharedPreferences("connection_settings", Context.MODE_PRIVATE).edit().putString("pending", "USER").commit();
                        refresh.invoke(activity);
                        assertEquals(View.GONE, ((View) get(activity, "headerNavigation")).getVisibility());
                        assertEquals(View.GONE, ((View) get(activity, "navigation")).getVisibility());
                        isolated.getSharedPreferences("connection_settings", Context.MODE_PRIVATE).edit().remove("pending").commit();
                        connection.registrationReset();
                        var settingsChanged = CarHomeActivity.class.getDeclaredMethod("settingsChanged"); settingsChanged.setAccessible(true); settingsChanged.invoke(activity);
                        assertEquals(6, get(activity, "page"));
                        assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("start.split"));
                        assertEquals(View.GONE, ((View) get(activity, "headerNavigation")).getVisibility());
                        set(activity, "allowed", false); refresh.invoke(activity);
                        assertEquals(View.GONE, ((View) get(activity, "headerNavigation")).getVisibility());
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
            }
            assertEquals("Leaving the activity must unregister the stream", 1, source.unregistrations);
        } finally {
            sessionInstance.set(null, previousSession); recorderInstance.set(null, previousRecorder);
            target.deleteSharedPreferences(prefix + "home_recording");
            target.deleteSharedPreferences(prefix + "connection_settings");
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static android.widget.EditText findInput(View view, String label) {
        if (view instanceof android.widget.EditText && label.contentEquals(view.getContentDescription())) return (android.widget.EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText result = findInput(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }
}
