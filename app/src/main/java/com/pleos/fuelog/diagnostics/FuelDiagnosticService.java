package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.Fuel;
import ai.pleos.playground.vehicle.listener.FuelPercentListener;

/** Fuel reads and a user-controlled fuel-percent subscription; no transaction inference. */
public final class FuelDiagnosticService extends PleosReadSupport {
    public enum Reading {
        PERCENT("fuel.percent", "연료 잔량 비율", "getFuelPercent", "%"),
        CURRENT("fuel.current", "현재 운행 연비", "getCurrentFuelEconomy", "L/100km"),
        CUMULATIVE("fuel.cumulative", "누적 연비", "getCumulativeFuelEconomy", "L/100km"),
        INSTANT("fuel.instant", "순간 연비", "getInstantaneousFuelEconomy", "L/100km"),
        REFILL("fuel.refill", "주유 이후 연비", "getSinceChargeFuelEconomy", "L/100km");

        public final String key, label, api, unit;
        Reading(String key, String label, String api, String unit) {
            this.key = key;
            this.label = label;
            this.api = api;
            this.unit = unit;
        }
    }

    private final Fuel fuel;
    private FuelPercentListener listener;

    public FuelDiagnosticService(Fuel fuel, DiagnosticSession session) {
        super(session);
        this.fuel = fuel;
    }

    public void readAll() {
        for (Reading item : Reading.values()) read(item);
    }

    public void read(Reading item) {
        switch (item) {
            case PERCENT:
                this.<Integer>read(item.key, item.unit, fuel::checkFuelPercentCapabilities, fuel::getFuelPercent);
                break;
            case CURRENT:
                this.<Float>read(item.key, item.unit, fuel::checkCurrentFuelEconomyCapabilities, fuel::getCurrentFuelEconomy);
                break;
            case CUMULATIVE:
                this.<Float>read(item.key, item.unit, fuel::checkCumulativeFuelEconomyCapabilities, fuel::getCumulativeFuelEconomy);
                break;
            case INSTANT:
                this.<Float>read(item.key, item.unit, fuel::checkInstantaneousFuelEconomyCapabilities, fuel::getInstantaneousFuelEconomy);
                break;
            case REFILL:
                this.<Float>read(item.key, item.unit, fuel::checkSinceChargeFuelEconomyCapabilities, fuel::getSinceChargeFuelEconomy);
                break;
        }
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
