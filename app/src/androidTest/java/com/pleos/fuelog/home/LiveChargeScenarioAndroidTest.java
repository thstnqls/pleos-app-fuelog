package com.pleos.fuelog.home;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.connection.ConnectionSession;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Explicit opt-in probe using real pairing. Run via am instrument; Gradle's runner uninstalls the app. */
public class LiveChargeScenarioAndroidTest {
    private static final String CONSOLE = "http://10.0.2.2:8088";

    @Test public void reproduceChargeSwitchAndEnergyIncrease() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("runLiveChargeScenario", "false")));
        http("POST", "/browser/session?client=fuelog-charge-probe");
        java.util.concurrent.ScheduledExecutorService heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try { http("POST", "/browser/session?client=fuelog-charge-probe"); }
            catch (Exception failure) { android.util.Log.w("FUELOG_CHARGE_PROBE", "Console heartbeat failed"); }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        JSONObject original = http("GET", "/vehicle/state").getJSONObject("ev_battery");
        try {
            set("charge_switch", false); set("charge_port_open", false);
            set("capacity", 60); set("battery_level", 30); set("soc", 50);
            try (ActivityScenario<CarHomeActivity> app = ActivityScenario.launch(CarHomeActivity.class)) {
                pause(); report(app, "baseline");
                app.onActivity(activity -> assertFalse("Pair the vehicle before this manual probe", HomeRecorder.get(activity).verifiedOwner().isEmpty()));
                set("charge_port_open", true); set("charge_switch", true);
                pause(); report(app, "switch-on");
                set("battery_level", 31);
                pause(); report(app, "energy-31");
                set("battery_level", 32);
                pause(); report(app, "energy-32");
                set("charge_switch", false);
                pause(); report(app, "switch-off");
                verifySavedCandidate(app);
            }
        } finally {
            try {
            set("charge_switch", false);
            set("capacity", original.getDouble("maximum_capacity_kwh"));
            set("battery_level", original.getDouble("current_energy_kwh"));
            set("soc", original.getDouble("state_of_charge"));
            set("charge_port_open", original.getBoolean("charging_door_opened"));
            } finally {
                heartbeat.shutdownNow();
                http("POST", "/browser/session?client=fuelog-charge-probe&closing=1");
            }
        }
    }

    @Test public void verifySavedChargeWithoutChangingConsole() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("runLiveChargeScenario", "false")));
        try (ActivityScenario<CarHomeActivity> app = ActivityScenario.launch(CarHomeActivity.class)) {
            pause(); verifySavedCandidate(app);
        }
    }

    private static void verifySavedCandidate(ActivityScenario<CarHomeActivity> app) throws Exception {
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        app.onActivity(activity -> {
            try {
                HomeRecorder recorder = HomeRecorder.get(activity);
                TripSummary summary = recorder.summary();
                assertNotNull("No saved summary", summary);
                assertTrue(summary.data.getBoolean("chargingOnly"));
                assertTrue(recorder.chargeHistory().length() > 0);
                var entries = summary.data.getJSONArray("chargeCandidates");
                JSONObject candidate = entries.getJSONObject(entries.length() - 1);
                assertEquals(2, candidate.getDouble("estimatedKwh"), 0.001);
                assertTrue("Upload not acknowledged", candidate.optLong("serverRevision") > 0);
                var navigate = CarHomeActivity.class.getDeclaredMethod("navigate", int.class);
                navigate.setAccessible(true); navigate.invoke(activity, 2);
                assertNull(activity.findViewById(android.R.id.content).findViewWithTag("statistics.2.chart"));
                assertFalse(hasText(activity.findViewById(android.R.id.content), "충전 관측 요약"));
                assertFalse(hasText(activity.findViewById(android.R.id.content), "충전 후보 상태 확인"));
                navigate.invoke(activity, 3);
                android.view.ViewGroup tabs = (android.view.ViewGroup) field(activity, "navigation");
                assertEquals(5, tabs.getChildCount());
                assertTrue(hasText(tabs, "충전"));
                assertTrue(hasText(activity.findViewById(android.R.id.content), "충전 기록"));
                assertFalse(hasText(activity.findViewById(android.R.id.content), "주유"));
                if ("REFERENCE_ESTIMATE".equals(candidate.optString("estimateStatus"))) {
                    assertNotNull("Expected a slow tariff snapshot", ChargeHistory.slow(candidate));
                    assertNotNull("Expected a slow estimated amount", ChargeHistory.amount(candidate));
                    assertTrue(hasText(activity.findViewById(android.R.id.content), ChargeHistory.amount(candidate).toPlainString() + "원"));
                }
                android.view.View root = activity.findViewById(android.R.id.content);
                assertNotNull(root.findViewWithTag("statistics.2.chart"));
                root.findViewWithTag("charge.metric.1").performClick();
                assertTrue(hasText(root, "완속 기준 추정금액 통계"));
                root.findViewWithTag("statistics.2.period.0").performClick();
                assertEquals(24, ((android.view.ViewGroup) root.findViewWithTag("statistics.2.chart")).getChildCount());
                root.findViewWithTag("statistics.2.period.2").performClick();
                assertEquals(12, ((android.view.ViewGroup) root.findViewWithTag("statistics.2.chart")).getChildCount());
                root.findViewWithTag("charge.metric.0").performClick();
                var open = CarHomeActivity.class.getDeclaredMethod("openTrip", String.class);
                open.setAccessible(true); open.invoke(activity, summary.data.getString("intervalId"));
                assertTrue(hasText(activity.findViewById(android.R.id.content), "충전 관측 요약"));
                assertTrue(hasText(activity.findViewById(android.R.id.content), "관측 충전량"));
                assertFalse(hasText(activity.findViewById(android.R.id.content), "추정 소모 전력량"));
                assertTrue(hasText(activity.findViewById(android.R.id.content), "충전 후보 상태 확인"));
                android.widget.LinearLayout confirmedView = new android.widget.LinearLayout(activity);
                JSONObject confirmed = new JSONObject(candidate.toString()).put("status", "CONFIRMED")
                        .put("correctedKwh", 1.8).put("correctedAmount", 9500).put("correctedPlace", "Test charger");
                var renderCharge = CarHomeActivity.class.getDeclaredMethod("renderChargeContent", android.widget.LinearLayout.class, JSONObject.class);
                renderCharge.setAccessible(true); renderCharge.invoke(activity, confirmedView, confirmed);
                assertTrue(hasText(confirmedView, "확인된 충전량"));
                assertTrue(hasText(confirmedView, "1.8 kWh"));
                assertTrue(hasText(confirmedView, "9,500.0원"));
                assertTrue(hasText(confirmedView, "충전 장소 · Test charger"));
                ConnectionSession.get(activity).request("GET", "charge-candidates/" + candidate.getString("id"), null, "",
                        new com.pleos.fuelog.api.ApiClient.ResultCallback() {
                    public void onSuccess(com.pleos.fuelog.api.ApiClient.ApiResponse response) {
                        try {
                            JSONObject body = new JSONObject(response.getBody());
                            assertEquals(candidate.getString("id"), body.getString("id"));
                            assertEquals(2, body.getDouble("estimatedKwh"), 0.001);
                            assertEquals("CANDIDATE", body.getString("status"));
                            JSONObject slow = ChargeHistory.slow(body);
                            android.util.Log.i("FUELOG_CHARGE_PROBE", "slowRate=" + (slow == null ? "missing" : slow.optString("averageWonPerKwh"))
                                    + " estimatedWon=" + ChargeHistory.amount(body));
                            android.util.Log.i("FUELOG_CHARGE_PROBE", "readback http=" + response.getStatusCode()
                                    + " status=" + body.getString("status") + " estimatedKwh=" + body.getDouble("estimatedKwh")
                                    + " estimateStatus=" + body.optString("estimateStatus") + " scenarios=" + (body.optJSONArray("scenarios") == null ? 0 : body.optJSONArray("scenarios").length())
                                    + " chargeSummaryVisible=true chargeTabPresent=true");
                        } catch (Throwable error) { failure.set(error); }
                        finally { completed.countDown(); }
                    }
                    public void onHttpError(com.pleos.fuelog.api.ApiClient.ApiResponse response) {
                        failure.set(new AssertionError("Readback HTTP " + response.getStatusCode())); completed.countDown();
                    }
                    public void onFailure(java.io.IOException error) { failure.set(error); completed.countDown(); }
                });
            } catch (Throwable error) { failure.set(error); completed.countDown(); }
        });
        assertTrue(completed.await(12, java.util.concurrent.TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static boolean hasText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && ((android.widget.TextView) view).getText().toString().startsWith(text)) return true;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static void pause() throws Exception { Thread.sleep(8000); }
    private static void set(String field, Object value) throws Exception {
        http("PUT", "/vehicle/ev-battery?field=" + field + "&value=" + value);
    }
    private static JSONObject http(String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(CONSOLE + path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        try {
            assertEquals("Console " + path, 200, connection.getResponseCode());
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            }
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void report(ActivityScenario<CarHomeActivity> app, String label) {
        app.onActivity(activity -> {
            try {
                HomeRecorder recorder = HomeRecorder.get(activity);
                ConnectionSession session = ConnectionSession.get(activity);
                EvRecording energy = (EvRecording) field(recorder, "ev");
                JSONObject data = energy.snapshot();
                int count = data.optJSONArray("chargeCandidates") == null ? 0 : data.getJSONArray("chargeCandidates").length();
                android.util.Log.i("FUELOG_CHARGE_PROBE", label + " linked=" + session.linked
                        + " ownerKnown=" + !recorder.verifiedOwner().isEmpty()
                        + " allowed=" + field(activity, "allowed") + " visible=" + field(recorder, "visible")
                        + " active=" + recorder.trip.active + " chargingOnly=" + field(recorder, "chargingOnly")
                        + " switch=" + field(recorder, "chargeSwitchOn") + " pending=" + field(recorder, "evPending")
                        + " lastEnergy=" + field(energy, "lastEnergy") + " chargeStart=" + field(energy, "chargeStart")
                        + " candidates=" + count + " storageError=" + recorder.storageError
                        + " startApi=" + session.apiStatus().get("POST", "recording-intervals").detail
                        + " chargeApi=" + session.apiStatus().get("POST", "charge-candidates/sync").detail);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
}
