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
import ai.pleos.playground.vehicle.api.Fuel;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FuelDiagnosticServiceTest {
    @Test public void allFiveGettersCompleteIndependentlyWithOriginalUnits() throws Exception {
        Map<String, String> states = new HashMap<>();
        Map<String, String> values = new HashMap<>();
        Set<String> calls = new HashSet<>();
        CountDownLatch complete = new CountDownLatch(5);
        DiagnosticSession[] session = new DiagnosticSession[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            session[0] = new DiagnosticSession((key, source, state, value, time) -> {
                states.put(key, state);
                values.put(key, value);
                if (!state.equals("대기")) complete.countDown();
            });
            new FuelDiagnosticService(fake(calls), session[0]).readAll();
        });
        try {
            assertTrue(complete.await(3, TimeUnit.SECONDS));
            Set<String> expected = new HashSet<>();
            for (FuelDiagnosticService.Reading reading : FuelDiagnosticService.Reading.values()) {
                expected.add(reading.api);
                assertEquals(reading.api, reading == FuelDiagnosticService.Reading.INSTANT ? "미지원" : "수신",
                        states.get(reading.key));
                if (reading != FuelDiagnosticService.Reading.INSTANT) {
                    assertTrue(reading.api, values.get(reading.key).contains(" " + reading.unit));
                }
            }
            assertEquals(5, states.size());
            assertEquals(expected, calls);
            assertTrue(values.get("fuel.percent").startsWith("0 %"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session[0].close());
        }
    }

    @Test public void individualQueryDoesNotReadOtherFuelProperties() throws Exception {
        Set<String> calls = new HashSet<>();
        CountDownLatch complete = new CountDownLatch(1);
        DiagnosticSession[] session = new DiagnosticSession[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            session[0] = new DiagnosticSession((key, source, state, value, time) -> {
                assertEquals("fuel.current", key);
                if (!state.equals("대기")) complete.countDown();
            });
            new FuelDiagnosticService(fake(calls), session[0]).read(FuelDiagnosticService.Reading.CURRENT);
        });
        try {
            assertTrue(complete.await(3, TimeUnit.SECONDS));
            assertEquals(java.util.Collections.singleton("getCurrentFuelEconomy"), calls);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session[0].close());
        }
    }

    @SuppressWarnings("unchecked")
    private Fuel fake(Set<String> calls) {
        return (Fuel) Proxy.newProxyInstance(Fuel.class.getClassLoader(), new Class<?>[]{Fuel.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("check")) {
                ((Function1<CapabilityConfig, Unit>) args[0]).invoke(new CapabilityConfig(AccessType.READ));
            } else {
                assertTrue(name, name.startsWith("get"));
                assertTrue("Duplicate getter: " + name, calls.add(name));
                if (name.equals("getInstantaneousFuelEconomy")) {
                    ((Function1<Exception, Unit>) args[1]).invoke(new UnsupportedOperationException("ERROR_NOT_SUPPORTED_PROPERTY"));
                } else {
                    Object value;
                    if (name.equals("getFuelPercent")) value = 0;
                    else value = 7.5f;
                    ((Function1<Object, Unit>) args[0]).invoke(value);
                }
            }
            return null;
        });
    }
}
