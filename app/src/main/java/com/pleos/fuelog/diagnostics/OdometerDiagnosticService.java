package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.Odometer;
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener;
import kotlin.Unit;

public final class OdometerDiagnosticService extends PleosReadSupport {
    private final Odometer odometer;
    private DistanceDrivenListener listener;

    public OdometerDiagnosticService(Odometer odometer, DiagnosticSession session) {
        super(session);
        this.odometer = odometer;
    }

    public void read() {
        this.<Float>read("distance.total", "km", odometer::checkCurrentDistanceDrivenCapabilities,
                odometer::getCurrentDistanceDriven);
    }

    public void readTripCumulativeDrive() {
        // Skip the app's capability precheck; the SDK getter still performs its own check.
        DiagnosticSession.Request request = session.begin("cumulative.total",
                "Pleos getTripCumulativeDrivingDistance · 직접 조회", "m", false);
        try {
            odometer.getTripCumulativeDrivingDistance(value -> {
                request.value(value);
                return Unit.INSTANCE;
            }, error -> {
                request.fail(error);
                return Unit.INSTANCE;
            });
        } catch (RuntimeException error) { request.fail(error); }
    }

    public void startUpdates() {
        stopUpdates();
        DiagnosticSession.Request request = session.begin("distance.stream", "Pleos registerDistanceDriven", "km", true);
        check(request, odometer::checkCurrentDistanceDrivenCapabilities, () -> {
            listener = new DistanceDrivenListener() {
                @Override public void onDistanceDrivenUpdated(Float value) { request.value(value); }
                @Override public void onFailed(Exception error) { request.fail(error); }
            };
            odometer.registerDistanceDriven(listener);
        });
    }

    public void stopUpdates() {
        session.stop("distance.stream");
        if (listener != null) {
            try { odometer.unregisterDistanceDriven(listener); }
            catch (RuntimeException error) {
                session.begin("distance.cleanup", "Pleos unregisterDistanceDriven", "", false).fail(error);
            }
            listener = null;
        }
    }
}
