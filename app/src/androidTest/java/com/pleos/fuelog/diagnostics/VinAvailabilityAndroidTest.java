package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.Vehicle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Hardware probe: reports only availability and shape, never the VIN or masked suffix. */
public class VinAvailabilityAndroidTest {
    @Test public void reportsVinAvailabilityWithoutLoggingIdentity() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        DiagnosticSession[] session = {null};
        Vehicle[] vehicle = {null};
        String[] shape = {"callback=false"};
        String[] state = {"pending"};
        String[] access = {"unavailable"};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            session[0] = new DiagnosticSession((key, source, status, value, time) -> {
                if (status.equals("대기")) return;
                state[0] = status;
                int at = value.lastIndexOf(" · 접근 ");
                if (at >= 0) access[0] = value.substring(at + " · 접근 ".length());
                completed.countDown();
            });
            try {
                vehicle[0] = new Vehicle(InstrumentationRegistry.getInstrumentation().getTargetContext());
                vehicle[0].initialize();
                new CarInfoDiagnosticService(vehicle[0].getCarInfo(), session[0]).readVin(value -> {
                    shape[0] = "callback=true null=" + (value == null)
                            + " length=" + (value == null ? -1 : value.length())
                            + " trimmedLength=" + (value == null ? -1 : value.trim().length())
                            + " valid=" + !CarInfoDiagnosticService.normalizeVin(value).isEmpty()
                            + " invalidSentinel=" + CarInfoDiagnosticService.isUnavailableVin(value)
                            + " undefinedSentinel=" + (value != null && value.trim().equalsIgnoreCase("undefined"));
                });
            } catch (RuntimeException failure) {
                state[0] = failure.getClass().getSimpleName(); completed.countDown();
            }
        });
        try {
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            android.util.Log.i("FUELOG_VIN_PROBE", "state=" + state[0] + " access=" + access[0] + " " + shape[0]);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                session[0].close();
                if (vehicle[0] != null) vehicle[0].release();
            });
        }
    }
}
