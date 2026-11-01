package com.pleos.fuelog.diagnostics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ai.pleos.playground.vehicle.api.Brake;
import ai.pleos.playground.vehicle.api.DrivingMode;
import ai.pleos.playground.vehicle.api.Odometer;
import ai.pleos.playground.vehicle.api.SteeringWheel;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import ai.pleos.playground.vehicle.constant.control.CruiseControl;
import ai.pleos.playground.vehicle.constant.control.DrivingModeType;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DriveDiagnosticServiceTest {
    @Test public void allThirteenGettersAreIndependentAndPreserveTypedValuesAndUnits() throws Exception {
        Set<String> getters = new HashSet<>();
        Map<String, String> states = new HashMap<>();
        Map<String, String> values = new HashMap<>();
        CountDownLatch completed = new CountDownLatch(13);
        DiagnosticSession[] session = new DiagnosticSession[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            session[0] = new DiagnosticSession((key, source, state, value, time) -> {
                states.put(key, state);
                values.put(key, value);
                if (!state.equals("대기")) completed.countDown();
            });
            new DriveDiagnosticService(fake(DrivingMode.class, getters), fake(Odometer.class, getters),
                    fake(Brake.class, getters), fake(SteeringWheel.class, getters), session[0]).readAll();
        });
        try {
            assertTrue("Every request must finish even when one getter fails", completed.await(3, TimeUnit.SECONDS));
            Set<String> expected = new HashSet<>();
            for (DriveDiagnosticService.Reading reading : DriveDiagnosticService.Reading.values()) {
                expected.add(reading.api);
                assertEquals(reading.api, reading == DriveDiagnosticService.Reading.TRIP_CUMULATIVE_DISTANCE
                        ? "미지원" : "수신", states.get(reading.key));
            }
            assertEquals(13, states.size());
            assertEquals(expected, getters);
            assertTrue(values.get(DriveDiagnosticService.Reading.STEERING.key).startsWith("-12.5 °"));
            assertTrue(values.get(DriveDiagnosticService.Reading.ODOMETER.key).startsWith("100.0 km"));
            assertTrue(values.get(DriveDiagnosticService.Reading.TRIP_CURRENT_DISTANCE.key).startsWith("100.0 m"));
            assertTrue(values.get(DriveDiagnosticService.Reading.TRIP_CURRENT_TIME.key).startsWith("7 분"));
            assertTrue(values.get(DriveDiagnosticService.Reading.CRUISE_SPEED.key).startsWith("100.0 m/s"));
            assertTrue(values.get(DriveDiagnosticService.Reading.BRAKE.key).startsWith("false"));
            assertTrue(values.get(DriveDiagnosticService.Reading.MODE.key).startsWith("ECO"));
            assertTrue(values.get(DriveDiagnosticService.Reading.CRUISE_STATE.key).startsWith("SUSPENDED"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session[0].close());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T fake(Class<T> domain, Set<String> getters) {
        return (T) Proxy.newProxyInstance(domain.getClassLoader(), new Class<?>[]{domain}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("check")) {
                CapabilityConfig readable = new CapabilityConfig(AccessType.READ);
                if (args[0] instanceof Function2) {
                    ((Function2<CapabilityConfig, Object, Unit>) args[0]).invoke(readable, null);
                } else {
                    ((Function1<CapabilityConfig, Unit>) args[0]).invoke(readable);
                }
                return null;
            }
            assertTrue("Only getter calls are authorized: " + name, name.startsWith("get"));
            assertTrue("Getter must run only once: " + name, getters.add(name));
            if (name.equals("getTripCumulativeDrivingDistance")) {
                ((Function1<Exception, Unit>) args[1]).invoke(new UnsupportedOperationException("ERROR_NOT_SUPPORTED_PROPERTY"));
            } else {
                Object value;
                if (name.equals("getCurrentDrivingModeState")) value = DrivingModeType.ECO;
                else if (name.equals("getCruiseControlState")) value = CruiseControl.SUSPENDED;
                else if (name.equals("getCurrentBrakePedalStatus")) value = false;
                else if (name.equals("getCurrentSteeringWheelAngle")) value = -12.5f;
                else if (name.contains("Time")) value = 7;
                else value = 100f;
                ((Function1<Object, Unit>) args[0]).invoke(value);
            }
            return null;
        });
    }
}
