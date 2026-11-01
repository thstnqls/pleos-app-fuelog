package com.pleos.fuelog.diagnostics;

import android.content.Context;

import java.util.Locale;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;

/** Initialized only after an explicit request and permission grant. Never records a route. */
public final class LocationDiagnosticService {
    private final Context context;
    private final DiagnosticSession session;
    private FusedLocation location;

    public LocationDiagnosticService(Context context, DiagnosticSession session) {
        this.context = context;
        this.session = session;
    }

    public void readLastLocation() {
        DiagnosticSession.Request request = session.begin("location.last", "Pleos FusedLocation 1.1.3", "", false);
        try {
            if (location == null) {
                FusedLocation candidate = new FusedLocation(context);
                if (!candidate.isSupported()) {
                    request.status("미지원", "FusedLocation.isSupported() = false");
                    return;
                }
                location = candidate;
                location.initialize();
            }
            location.getLastKnownLocation(new FusedLocationListener() {
                @Override public void onChanged(LocationData value) {
                    if (value == null) { request.value(null); return; }
                    long age = System.currentTimeMillis() - value.getTimestampMillis();
                    request.value(String.format(Locale.KOREA,
                            "위도 %.6f / 경도 %.6f\n고도 %.1f / 방향 %.1f / confidence(raw) %.2f\n"
                                    + "측정 timestamp(ms): %d · 경과: %s\n마지막 저장 위치이며 새 측정이 아님",
                            value.getLatitude(), value.getLongitude(), value.getAltitude(),
                            value.getHeading(), value.getConfidence(), value.getTimestampMillis(),
                            age >= 0 ? (age / 1000) + "초" : "기기 시각 확인 필요"));
                }
                @Override public void onError(Exception error) { request.fail(error); }
            });
        } catch (RuntimeException error) { request.fail(error); }
    }

    public void close() {
        if (location != null) {
            try { location.release(); } finally { location = null; }
        }
    }
}
