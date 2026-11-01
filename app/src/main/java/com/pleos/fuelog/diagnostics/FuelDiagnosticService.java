package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.Fuel;
import ai.pleos.playground.vehicle.listener.FuelPercentListener;

/** Fuel reads and a user-controlled fuel-percent subscription; no transaction inference. */
public final class FuelDiagnosticService extends PleosReadSupport {
    private final Fuel fuel;
    private FuelPercentListener listener;

    public FuelDiagnosticService(Fuel fuel, DiagnosticSession session) {
        super(session);
        this.fuel = fuel;
    }

    public void readAll() {
        this.<Integer>read("fuel.percent", "%", fuel::checkFuelPercentCapabilities, fuel::getFuelPercent);
        this.<Float>read("fuel.current", "L/100km", fuel::checkCurrentFuelEconomyCapabilities, fuel::getCurrentFuelEconomy);
        this.<Float>read("fuel.cumulative", "L/100km", fuel::checkCumulativeFuelEconomyCapabilities, fuel::getCumulativeFuelEconomy);
        this.<Float>read("fuel.instant", "L/100km", fuel::checkInstantaneousFuelEconomyCapabilities, fuel::getInstantaneousFuelEconomy);
        this.<Float>read("fuel.refill", "L/100km", fuel::checkSinceChargeFuelEconomyCapabilities, fuel::getSinceChargeFuelEconomy);
    }

    public void startUpdates() {
        stopUpdates();
        DiagnosticSession.Request request = session.begin("fuel.stream", "Pleos registerFuelPercent", "%", true);
        check(request, fuel::checkFuelPercentCapabilities, () -> {
            listener = new FuelPercentListener() {
                @Override public void onFuelPercentUpdated(Integer value) { request.value(value); }
                @Override public void onFailed(Exception error) { request.fail(error); }
            };
            fuel.registerFuelPercent(listener);
        });
    }

    public void stopUpdates() {
        session.stop("fuel.stream");
        if (listener != null) {
            try { fuel.unregisterFuelPercent(listener); }
            catch (RuntimeException error) {
                session.begin("fuel.cleanup", "Pleos unregisterFuelPercent", "", false).fail(error);
            }
            listener = null;
        }
    }
}
