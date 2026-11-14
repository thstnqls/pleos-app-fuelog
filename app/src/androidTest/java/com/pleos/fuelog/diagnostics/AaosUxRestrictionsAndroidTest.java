package com.pleos.fuelog.diagnostics;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class AaosUxRestrictionsAndroidTest {
    @Test public void virtualDisplayUsesDriverRestrictionsAndStillBlocksDriving() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        try (ImageReader image = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)) {
            VirtualDisplay virtual = displays.createVirtualDisplay("fuelog-ux-test", 640, 480, 160, image.getSurface(), 0);
            assertNotNull(virtual);
            AaosVehicleDiagnosticService[] services = new AaosVehicleDiagnosticService[2];
            CountDownLatch received = new CountDownLatch(2);
            boolean[] allowed = new boolean[2];
            boolean[] hasState = new boolean[2];
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    for (int i = 0; i < services.length; i++) {
                        final int index = i;
                        Display display = i == 0 ? displays.getDisplay(Display.DEFAULT_DISPLAY) : virtual.getDisplay();
                        services[i] = new AaosVehicleDiagnosticService(context.createDisplayContext(display));
                        services[i].start((value, detail) -> {
                            if (detail.contains("UX 제한 비트")) {
                                allowed[index] = value;
                                if (!hasState[index]) { hasState[index] = true; received.countDown(); }
                            }
                        });
                    }
                });
                assertTrue("Both displays must receive UX state", received.await(10, TimeUnit.SECONDS));
                assertEquals("Virtual display must follow the driver", allowed[0], allowed[1]);
                Field serviceContext = AaosVehicleDiagnosticService.class.getDeclaredField("context");
                serviceContext.setAccessible(true);
                Context selected = (Context) serviceContext.get(services[1]);
                assertEquals(Display.DEFAULT_DISPLAY, selected.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getDisplayId());
                Field observer = AaosVehicleDiagnosticService.class.getDeclaredField("observer");
                observer.setAccessible(true);
                observer.set(services[1], (AaosVehicleDiagnosticService.UxObserver) (value, detail) -> allowed[1] = value);
                var update = AaosVehicleDiagnosticService.class.getDeclaredMethod("onRestrictions", CarUxRestrictions.class);
                update.setAccessible(true);
                update.invoke(services[1], new CarUxRestrictions.Builder(true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, 1).build());
                assertFalse("Driving restrictions must still block", allowed[1]);
                update.invoke(services[1], new CarUxRestrictions.Builder(false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 2).build());
                assertTrue("Parked state must restore access", allowed[1]);
                update.invoke(services[1], new Object[]{null});
                assertFalse("Unknown state must still block", allowed[1]);
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    for (AaosVehicleDiagnosticService service : services) if (service != null) service.close();
                });
                virtual.release();
            }
        }
    }
}
