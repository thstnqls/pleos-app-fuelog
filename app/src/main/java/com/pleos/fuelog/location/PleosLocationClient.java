package com.pleos.fuelog.location;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.MainThread;
import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;

/** One-shot foreground location API. Call on the main thread and close with the owning screen. */
public final class PleosLocationClient implements AutoCloseable {
    public static final String PERMISSION = "pleos.car.permission.FUSED_LOCATION";
    public static final long TIMEOUT_MILLIS = 10000;

    public enum ErrorCode { PERMISSION_DENIED, UNSUPPORTED, NO_DATA, INVALID_DATA, TIMEOUT, SDK_ERROR, CLOSED }
    public interface Callback {
        void onSuccess(Position position);
        void onError(ErrorCode code, Exception cause);
    }

    /** Original SDK fields; no coordinate-bearing toString(), persistence, or logging. */
    public static final class Position {
        private final double latitude, longitude, altitude;
        private final float heading, confidence;
        private final long timestampMillis;
        private final boolean fromUpdate;
        private Position(LocationData data, boolean fromUpdate) {
            latitude = data.getLatitude(); longitude = data.getLongitude(); altitude = data.getAltitude();
            heading = data.getHeading(); confidence = data.getConfidence();
            timestampMillis = data.getTimestampMillis(); this.fromUpdate = fromUpdate;
        }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public double getAltitude() { return altitude; }
        public float getHeading() { return heading; }
        public float getConfidence() { return confidence; }
        public long getTimestampMillis() { return timestampMillis; }
        public boolean isFromUpdate() { return fromUpdate; }
    }

    interface Backend {
        boolean isSupported();
        void initialize();
        void getLastKnownLocation(FusedLocationListener listener);
        void register(FusedLocationListener listener);
        void unregister(FusedLocationListener listener);
        void release();
    }
    interface Factory { boolean hasPermission(); Backend create(); }

    private final Factory factory;
    private final Handler main;
    private final long timeoutMillis;
    private Request active;
    private boolean closed;

    public PleosLocationClient(Context context) {
        this(new Factory() {
            private final Context application = context.getApplicationContext();
            @Override public boolean hasPermission() {
                return application.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
            }
            @Override public Backend create() {
                FusedLocation sdk = new FusedLocation(application);
                return new Backend() {
                    @Override public boolean isSupported() { return sdk.isSupported(); }
                    @Override public void initialize() { sdk.initialize(); }
                    @Override public void getLastKnownLocation(FusedLocationListener listener) { sdk.getLastKnownLocation(listener); }
                    @Override public void register(FusedLocationListener listener) { sdk.registerFusedLocationCallback(listener); }
                    @Override public void unregister(FusedLocationListener listener) { sdk.unregisterFusedLocationCallback(listener); }
                    @Override public void release() { sdk.release(); }
                };
            }
        }, new Handler(Looper.getMainLooper()), TIMEOUT_MILLIS);
    }

    PleosLocationClient(Factory factory, Handler main, long timeoutMillis) {
        this.factory = factory; this.main = main; this.timeoutMillis = timeoutMillis;
    }

    /** One valid update measured at/after this call; older cached fixes are ignored. */
    @MainThread public Request getCurrentLocation(Callback callback) { return start(callback, true); }
    /** Last cached fix with its original timestamp, or NO_DATA. */
    @MainThread public Request getLastKnownLocation(Callback callback) { return start(callback, false); }

    private Request start(Callback callback, boolean update) {
        requireMainThread();
        if (active != null) active.cancel();
        Request request = new Request(callback, update);
        if (closed) { request.fail(ErrorCode.CLOSED, null); return request; }
        active = request;
        try {
            if (!factory.hasPermission()) { request.fail(ErrorCode.PERMISSION_DENIED, null); return request; }
            request.backend = factory.create();
            if (!request.backend.isSupported()) { request.fail(ErrorCode.UNSUPPORTED, null); return request; }
            request.initializationAttempted = true;
            request.backend.initialize();
            main.postDelayed(request.timeout, timeoutMillis);
            if (update) {
                request.registrationAttempted = true;
                request.backend.register(request.listener);
            } else {
                request.backend.getLastKnownLocation(request.listener);
            }
        } catch (SecurityException error) { request.fail(ErrorCode.PERMISSION_DENIED, error); }
        catch (RuntimeException error) { request.fail(ErrorCode.SDK_ERROR, error); }
        return request;
    }

    @Override @MainThread public void close() {
        requireMainThread();
        closed = true;
        if (active != null) active.cancel();
    }

    private void requireMainThread() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Call location API on the main thread");
    }

    public final class Request {
        private final Callback callback;
        private final boolean update;
        private final long requestedAtMillis = System.currentTimeMillis();
        private boolean done, initializationAttempted, registrationAttempted;
        private Backend backend;
        private final Runnable timeout = () -> fail(ErrorCode.TIMEOUT, null);
        private final FusedLocationListener listener = new FusedLocationListener() {
            @Override public void onChanged(LocationData value) { main.post(() -> accept(value)); }
            @Override public void onError(Exception error) {
                main.post(() -> fail(error instanceof SecurityException ? ErrorCode.PERMISSION_DENIED : ErrorCode.SDK_ERROR, error));
            }
        };
        private Request(Callback callback, boolean update) { this.callback = callback; this.update = update; }

        private void accept(LocationData value) {
            if (done || active != this) return;
            if (value == null) { fail(ErrorCode.NO_DATA, null); return; }
            double latitude = value.getLatitude(), longitude = value.getLongitude();
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                fail(ErrorCode.INVALID_DATA, null); return;
            }
            if (update && (value.getTimestampMillis() < requestedAtMillis
                    || value.getTimestampMillis() > System.currentTimeMillis() + 5000)) return;
            Position position = new Position(value, update);
            finish();
            callback.onSuccess(position);
        }

        private void fail(ErrorCode code, Exception cause) {
            if (done) return;
            finish();
            callback.onError(code, cause);
        }

        /** Silent cancellation; queued/late SDK callbacks cannot deliver a result afterward. */
        @MainThread public void cancel() { requireMainThread(); if (!done) finish(); }
        private void finish() {
            done = true;
            main.removeCallbacks(timeout);
            if (active == this) active = null;
            if (backend == null) return;
            if (registrationAttempted) {
                try { backend.unregister(listener); } catch (RuntimeException ignored) { /* release still runs */ }
            }
            if (initializationAttempted) {
                try { backend.release(); } catch (RuntimeException ignored) { /* request already invalid */ }
            }
            backend = null;
        }
    }
}
