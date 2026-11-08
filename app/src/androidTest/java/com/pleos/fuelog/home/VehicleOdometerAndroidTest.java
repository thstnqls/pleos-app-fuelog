package com.pleos.fuelog.home;

import ai.pleos.playground.vehicle.api.Odometer;
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Proxy;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleOdometerAndroidTest {
    @Test public void readsWithoutRecordingAndStreamsUntilStoppedOrClosed() {
        FakeOdometer source = new FakeOdometer();
        VehicleOdometer[] reading = new VehicleOdometer[1];
        onMain(() -> {
            reading[0] = new VehicleOdometer(source.api, () -> {});
            reading[0].read();
            source.read.invoke(12345f);
        });
        drain();
        onMain(() -> {
            assertEquals(12345f, reading[0].km, 0f);
            assertTrue(reading[0].observedAt > 0);
            reading[0].startUpdates();
            reading[0].startUpdates();
            assertEquals(1, source.registrations);
            assertTrue(reading[0].subscribed());
            source.listener.onDistanceDrivenUpdated(12346f);
        });
        drain();
        onMain(() -> {
            assertEquals(12346f, reading[0].km, 0f);
            reading[0].stopUpdates();
            assertEquals(1, source.unregistrations);
            assertFalse(reading[0].subscribed());
            source.listener.onDistanceDrivenUpdated(99999f);
            reading[0].startUpdates();
            reading[0].read();
            reading[0].close();
            source.listener.onDistanceDrivenUpdated(99999f);
            source.read.invoke(99999f);
        });
        drain();
        onMain(() -> {
            assertEquals(2, source.unregistrations);
            assertFalse(reading[0].subscribed());
            assertEquals(12346f, reading[0].km, 0f);
        });
    }

    @Test public void invalidValuesKeepLastReadingAndStreamWinsOverOlderRead() {
        FakeOdometer source = new FakeOdometer();
        VehicleOdometer[] reading = new VehicleOdometer[1];
        onMain(() -> {
            reading[0] = new VehicleOdometer(source.api, () -> {});
            reading[0].read();
            reading[0].startUpdates();
            source.listener.onDistanceDrivenUpdated(0f);
            source.read.invoke(100f);
        });
        drain();
        onMain(() -> {
            assertEquals(0f, reading[0].km, 0f);
            source.listener.onDistanceDrivenUpdated(Float.NaN);
            source.listener.onDistanceDrivenUpdated(-1f);
            source.listener.onDistanceDrivenUpdated(null);
        });
        drain();
        onMain(() -> {
            assertEquals(0f, reading[0].km, 0f);
            assertTrue(reading[0].readStatus.contains("유효한 거리 없음"));
            source.listener.onFailed(new SecurityException());
        });
        drain();
        onMain(() -> {
            assertFalse(reading[0].subscribed());
            assertTrue(reading[0].subscriptionStatus.contains("구독 오류"));
            assertEquals(0f, reading[0].km, 0f);
            reading[0].startUpdates();
            assertTrue(reading[0].subscribed());
            reading[0].close();
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void drain() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    static final class FakeOdometer {
        Function1<Float, Unit> read;
        DistanceDrivenListener listener;
        int registrations, unregistrations;
        @SuppressWarnings("unchecked")
        final Odometer api = (Odometer) Proxy.newProxyInstance(Odometer.class.getClassLoader(),
                new Class<?>[]{Odometer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCurrentDistanceDriven": read = (Function1<Float, Unit>) args[0]; break;
                        case "registerDistanceDriven": listener = (DistanceDrivenListener) args[0]; registrations++; break;
                        case "unregisterDistanceDriven": assertSame(listener, args[0]); unregistrations++; break;
                        default: throw new AssertionError(method.getName());
                    }
                    return null;
                });
    }
}
