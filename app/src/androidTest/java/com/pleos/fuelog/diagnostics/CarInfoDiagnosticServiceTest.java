package com.pleos.fuelog.diagnostics;

import androidx.test.platform.app.InstrumentationRegistry;
import ai.pleos.playground.vehicle.api.CarInfo;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.Test;
import static org.junit.Assert.*;

public class CarInfoDiagnosticServiceTest {
    @Test public void sharedReadPassesAndDisplaysReceivedValue() throws Exception {
        String raw = "1HGCM82633A004352";
        java.util.List<String> calls = new java.util.ArrayList<>();
        String[] identity = {null}, display = {null};
        CountDownLatch completed = new CountDownLatch(1);
        DiagnosticSession[] session = {null};
        CarInfo info = (CarInfo) Proxy.newProxyInstance(CarInfo.class.getClassLoader(), new Class[]{CarInfo.class}, (proxy, method, args) -> {
            if (method.getName().equals("checkVINInfoCapabilities")) {
                calls.add("capability");
                ((Function1<CapabilityConfig, Unit>) args[0]).invoke(new CapabilityConfig(AccessType.READ));
            } else if (method.getName().equals("getVINInfo")) {
                calls.add("get");
                ((Function1<String, Unit>) args[0]).invoke(raw);
            }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            session[0] = new DiagnosticSession((key, source, state, value, time) -> {
                if (!state.equals("대기")) { display[0] = value; completed.countDown(); }
            });
            new CarInfoDiagnosticService(info, session[0]).readVin(value -> identity[0] = value);
        });
        try {
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList("capability", "get"), calls);
            assertEquals(raw, identity[0]);
            assertTrue(display[0].startsWith(raw));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session[0].close());
        }
    }
    @Test public void unreadableCapabilityDoesNotCallVinGetter() throws Exception {
        for (AccessType access : new AccessType[]{AccessType.NONE, AccessType.WRITE}) {
            CountDownLatch completed = new CountDownLatch(1);
            DiagnosticSession[] session = {null};
            String[] state = {null};
            CarInfo info = (CarInfo) Proxy.newProxyInstance(CarInfo.class.getClassLoader(), new Class[]{CarInfo.class}, (proxy, method, args) -> {
                if (method.getName().equals("checkVINInfoCapabilities"))
                    ((Function1<CapabilityConfig, Unit>) args[0]).invoke(new CapabilityConfig(access));
                else if (method.getName().equals("getVINInfo")) fail("Unreadable VIN must not be requested");
                return null;
            });
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                session[0] = new DiagnosticSession((key, source, status, value, time) -> {
                    if (!status.equals("대기")) { state[0] = status; completed.countDown(); }
                });
                new CarInfoDiagnosticService(info, session[0]).readVin();
            });
            try {
                assertTrue(completed.await(3, TimeUnit.SECONDS));
                assertEquals("읽기 불가", state[0]);
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session[0].close());
            }
        }
    }

}
