package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.CarInfo;
import kotlin.Unit;

public final class CarInfoDiagnosticService extends PleosReadSupport {
    private final CarInfo info;

    public CarInfoDiagnosticService(CarInfo info, DiagnosticSession session) {
        super(session);
        this.info = info;
    }

    public void readVin() {
        DiagnosticSession.Request request = session.begin("vehicle.vin", "Pleos getVINInfo", "", false);
        check(request, info::checkVINInfoCapabilities, () -> info.getVINInfo(value -> {
            // Display only the last four characters. Do not persist the original VIN.
            request.value(value == null ? null : value.isEmpty() ? "빈 VIN"
                    : "•••• " + value.substring(Math.max(0, value.length() - 4)));
            return Unit.INSTANCE;
        }, error -> {
            request.fail(error);
            return Unit.INSTANCE;
        }));
    }
}
