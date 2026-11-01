package com.pleos.fuelog.location;

import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PleosLocationClientTest {
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void drain() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private LocationData fix(double lat, double lon, long time) { return new LocationData(lat, lon, 0d, 0f, 0f, time); }

    @Test public void permissionAndSupportAreCheckedBeforeInitializing() {
        FakeFactory denied = new FakeFactory(); denied.permission = false;
        Result first = new Result();
        main(() -> new PleosLocationClient(denied, new Handler(Looper.getMainLooper()), 2000).getCurrentLocation(first));
        assertEquals(PleosLocationClient.ErrorCode.PERMISSION_DENIED, first.error);
        assertTrue(denied.backends.isEmpty());
        FakeFactory absent = new FakeFactory(); absent.supported = false;
        Result second = new Result();
        main(() -> new PleosLocationClient(absent, new Handler(Looper.getMainLooper()), 2000).getCurrentLocation(second));
        assertEquals(PleosLocationClient.ErrorCode.UNSUPPORTED, second.error);
        assertEquals(0, absent.latest().initialized);
    }

    @Test public void currentIgnoresOldCacheAndReturnsExactlyOneFreshSignedCoordinate() {
        FakeFactory factory = new FakeFactory(); Result result = new Result();
        main(() -> new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 2000).getCurrentLocation(result));
        FakeBackend backend = factory.latest();
        backend.listener.onChanged(fix(10, 20, System.currentTimeMillis() - 60000));
        drain();
        assertEquals(0, result.calls);
        backend.listener.onChanged(fix(-30, -170, System.currentTimeMillis()));
        drain();
        assertEquals(1, result.calls);
        assertEquals(-30d, result.position.getLatitude(), 0d);
        assertEquals(-170d, result.position.getLongitude(), 0d);
        assertTrue(result.position.isFromUpdate());
        assertEquals(1, backend.unregistered);
        assertEquals(1, backend.released);
        backend.listener.onChanged(fix(0, 0, System.currentTimeMillis()));
        drain();
        assertEquals(1, result.calls);
    }

    @Test public void lastKnownPreservesZeroCoordinatesAndOriginalOldTimestamp() {
        FakeFactory factory = new FakeFactory(); Result result = new Result();
        main(() -> new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 2000).getLastKnownLocation(result));
        FakeBackend backend = factory.latest();
        backend.listener.onChanged(fix(0, 0, 1));
        drain();
        assertEquals(0d, result.position.getLatitude(), 0d);
        assertEquals(0d, result.position.getLongitude(), 0d);
        assertEquals(1, result.position.getTimestampMillis());
        assertFalse(result.position.isFromUpdate());
        assertEquals(0, backend.registered);
        assertEquals(0, backend.unregistered);
        assertEquals(1, backend.released);
    }

    @Test public void nullAndInvalidCoordinatesNeverBecomeAZeroFix() {
        LocationData[] samples = {null, fix(91, 0, 1), fix(0, -181, 1), fix(Double.NaN, 0, 1)};
        for (LocationData sample : samples) {
            FakeFactory factory = new FakeFactory(); Result result = new Result();
            main(() -> new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 2000).getLastKnownLocation(result));
            factory.latest().listener.onChanged(sample);
            drain();
            assertNull(result.position);
            assertEquals(sample == null ? PleosLocationClient.ErrorCode.NO_DATA : PleosLocationClient.ErrorCode.INVALID_DATA, result.error);
            assertEquals(1, factory.latest().released);
        }
    }

    @Test public void timeoutUnregistersAndIgnoresLateCallbacks() throws Exception {
        FakeFactory factory = new FakeFactory(); Result result = new Result();
        main(() -> new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 30).getCurrentLocation(result));
        assertTrue(result.finished.await(2, TimeUnit.SECONDS));
        assertEquals(PleosLocationClient.ErrorCode.TIMEOUT, result.error);
        assertEquals(1, factory.latest().unregistered);
        assertEquals(1, factory.latest().released);
        factory.latest().listener.onChanged(fix(0, 0, System.currentTimeMillis()));
        drain();
        assertEquals(1, result.calls);
    }

    @Test public void replacedRequestsAndClosedScreenCannotDeliverCoordinates() {
        FakeFactory factory = new FakeFactory(); Result first = new Result(), second = new Result();
        PleosLocationClient[] client = new PleosLocationClient[1];
        main(() -> {
            client[0] = new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 2000);
            client[0].getCurrentLocation(first);
            client[0].getCurrentLocation(second);
        });
        assertEquals(1, factory.backends.get(0).unregistered);
        assertEquals(1, factory.backends.get(0).released);
        factory.backends.get(0).listener.onChanged(fix(1, 1, System.currentTimeMillis()));
        main(() -> client[0].close());
        factory.backends.get(1).listener.onChanged(fix(2, 2, System.currentTimeMillis()));
        drain();
        assertEquals(0, first.calls);
        assertEquals(0, second.calls);
        assertEquals(1, factory.backends.get(1).unregistered);
        assertEquals(1, factory.backends.get(1).released);
        Result afterClose = new Result();
        main(() -> client[0].getCurrentLocation(afterClose));
        assertEquals(PleosLocationClient.ErrorCode.CLOSED, afterClose.error);
        assertEquals(2, factory.backends.size());
    }

    @Test public void permissionRevocationStillUnregistersAndReleases() {
        FakeFactory factory = new FakeFactory(); Result result = new Result();
        main(() -> new PleosLocationClient(factory, new Handler(Looper.getMainLooper()), 2000).getCurrentLocation(result));
        factory.latest().listener.onError(new SecurityException("permission revoked"));
        drain();
        assertEquals(PleosLocationClient.ErrorCode.PERMISSION_DENIED, result.error);
        assertEquals(1, factory.latest().unregistered);
        assertEquals(1, factory.latest().released);
    }

    private static final class Result implements PleosLocationClient.Callback {
        int calls;
        PleosLocationClient.Position position;
        PleosLocationClient.ErrorCode error;
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public void onSuccess(PleosLocationClient.Position value) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            calls++; position = value; finished.countDown();
        }
        @Override public void onError(PleosLocationClient.ErrorCode value, Exception cause) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            calls++; error = value; finished.countDown();
        }
    }

    private static final class FakeFactory implements PleosLocationClient.Factory {
        boolean permission = true, supported = true;
        final List<FakeBackend> backends = new ArrayList<>();
        @Override public boolean hasPermission() { return permission; }
        @Override public PleosLocationClient.Backend create() {
            FakeBackend backend = new FakeBackend(); backend.supported = supported; backends.add(backend); return backend;
        }
        FakeBackend latest() { return backends.get(backends.size() - 1); }
    }

    private static final class FakeBackend implements PleosLocationClient.Backend {
        boolean supported;
        int initialized, registered, unregistered, released;
        FusedLocationListener listener;
        @Override public boolean isSupported() { return supported; }
        @Override public void initialize() { initialized++; }
        @Override public void getLastKnownLocation(FusedLocationListener value) { listener = value; }
        @Override public void register(FusedLocationListener value) { registered++; listener = value; }
        @Override public void unregister(FusedLocationListener value) { assertSame(listener, value); unregistered++; }
        @Override public void release() { released++; }
    }
}
