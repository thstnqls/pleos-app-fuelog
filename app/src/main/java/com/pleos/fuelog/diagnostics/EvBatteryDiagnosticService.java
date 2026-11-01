package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.EvBattery;
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener;

/** EV reads and a user-controlled state-of-charge subscription. */
public final class EvBatteryDiagnosticService extends PleosReadSupport {
    private final EvBattery battery;
    private EvBatteryStateOfChargeListener listener;

    public EvBatteryDiagnosticService(EvBattery battery, DiagnosticSession session) {
        super(session);
        this.battery = battery;
    }

    public void readAll() {
        this.<Integer>read("ev.soc", "%", battery::checkEvBatteryStateOfChargeCapabilities,
                battery::getEvBatteryStateOfCharge);
        this.<Float>read("ev.current", "km/kWh", battery::checkCurrentEvEfficiencyCapabilities,
                battery::getCurrentEvEfficiency);
        this.<Float>read("ev.cumulative", "km/kWh", battery::checkCumulativeEvEfficiencyCapabilities,
                battery::getCumulativeEvEfficiency);
        this.<Float>read("ev.instant", "km/kWh", battery::checkInstantaneousEvEfficiencyCapabilities,
                battery::getInstantaneousEvEfficiency);
        this.<Float>read("ev.charge", "km/kWh", battery::checkSinceChargeEvEfficiencyCapabilities,
                battery::getSinceChargeEvEfficiency);
    }

    public void startUpdates() {
        stopUpdates();
        DiagnosticSession.Request request = session.begin("ev.stream",
                "Pleos registerEvBatteryStateOfCharge", "%", true);
        check(request, battery::checkEvBatteryStateOfChargeCapabilities, () -> {
            listener = new EvBatteryStateOfChargeListener() {
                @Override public void onEvBatteryStateOfChargeUpdated(Integer value) { request.value(value); }
                @Override public void onFailed(Exception error) { request.fail(error); }
            };
            battery.registerEvBatteryStateOfCharge(listener);
        });
    }

    public void stopUpdates() {
        session.stop("ev.stream");
        if (listener != null) {
            try { battery.unregisterEvBatteryStateOfCharge(listener); }
            catch (RuntimeException error) {
                session.begin("ev.cleanup", "Pleos unregisterEvBatteryStateOfCharge", "", false).fail(error);
            }
            listener = null;
        }
    }
}
