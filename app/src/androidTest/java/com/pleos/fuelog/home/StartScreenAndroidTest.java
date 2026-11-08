package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.tabs.TabLayout;
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
            server.enqueue(new MockResponse().setBody("{\"odometer\":null,\"items\":[{\"name\":\"Shared oil\",\"basis\":\"USER\"}]}"));
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
                        TabLayout tabs = activity.findViewById(android.R.id.content).findViewWithTag("start.tabs");
                        assertEquals(2, tabs.getTabCount());
                        assertEquals("QR 연결", tabs.getTabAt(0).getText());
                        assertEquals("차량 기본정보", tabs.getTabAt(1).getText());
                        tabs.selectTab(tabs.getTabAt(1));
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
                    assertTrue(contains(root, "현재 차량 거리 확인  →"));
                    assertTrue(contains(root, "현재 배터리 확인  →"));
                    assertTrue(contains(root.findViewWithTag("vehicle.odometer"), "12,345.0 km"));
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
                        TextView subscribe = root.findViewWithTag("vehicle.odometer.subscribe");
                        assertEquals("누적거리 구독 시작", subscribe.getText());
                        subscribe.performClick();
                        root = activity.findViewById(android.R.id.content);
                        assertEquals("누적거리 구독 중지", ((TextView) root.findViewWithTag("vehicle.odometer.subscribe")).getText());
                        assertFalse(HomeRecorder.get(isolated).trip.active);
                        source.listener.onDistanceDrivenUpdated(12346f);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> assertTrue(contains(
                        activity.findViewById(android.R.id.content).findViewWithTag("vehicle.odometer"), "12,346.0 km")));
            }
            assertEquals("Leaving the activity must unregister the vehicle stream", 1, source.unregistrations);
        } finally {
            sessionInstance.set(null, previousSession); recorderInstance.set(null, previousRecorder);
            target.deleteSharedPreferences(prefix + "home_recording");
            target.deleteSharedPreferences(prefix + "connection_settings");
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
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
