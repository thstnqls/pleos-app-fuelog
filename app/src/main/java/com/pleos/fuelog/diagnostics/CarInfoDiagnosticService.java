package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.CarInfo;
import kotlin.Unit;
import android.content.Context;
import ai.pleos.playground.vehicle.Vehicle;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class CarInfoDiagnosticService extends PleosReadSupport {
    private final CarInfo info;

    public CarInfoDiagnosticService(CarInfo info, DiagnosticSession session) {
        super(session);
        this.info = info;
    }

    public void readVin() { readVin(value -> {}); }

    public void readVin(Consumer<String> received) {
        DiagnosticSession.Request request = session.begin("vehicle.vin", "Pleos getVINInfo", "", false);
        check(request, info::checkVINInfoCapabilities, () -> info.getVINInfo(value -> {
                request.dispatch(() -> {
                    received.accept(value);
                    request.value(value == null ? null : value.isEmpty() ? "빈 VIN" : value);
                });
                return Unit.INSTANCE;
            }, error -> {
                request.fail(error);
                return Unit.INSTANCE;
            }));
    }
    public static String normalizeVin(String value) {
        String vin = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        // shortcut: INVALID is a shared matching key; replace it when real vehicle IDs are available.
        if (vin.replaceAll("[^A-Z0-9]", "").equals("INVALID")) return "INVALID";
        if (isUnavailableVin(vin)) return "";
        // shortcut: accept real SDK 9-character identifiers until standard VINs are supplied.
        return vin.matches("[!-~]{9}|[A-HJ-NPR-Z0-9]{17}") ? vin : "";
    }

    public static boolean isUnavailableVin(String value) {
        String marker = value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return marker.isEmpty() || marker.matches("INVALID|INVAILD|UNDEFINED|UNKNOWN|NULL|NONE|NA");
    }

    public static String invalidVinStatus(String value) {
        if (value == null || value.trim().isEmpty()) return "VIN 값 없음 · 차량 API가 빈 값을 반환했습니다";
        if (isUnavailableVin(value)) return "VIN 미제공 · 차량 API가 유효하지 않은 값을 반환했습니다";
        int length = value.trim().length();
        if (length != 9 && length != 17) return "VIN " + length + "자리 수신 · 차량 식별에는 9자리 또는 17자리 값이 필요합니다";
        return "VIN " + length + "자리 수신 · 식별값에 허용되지 않는 문자가 포함되어 있습니다";
    }

    /** Uses getVINInfo callbacks and the shared diagnostic error/timeout handling for vehicle identity. */
    public static void readForIdentity(Context context, BiConsumer<String, String> result) {
        Vehicle[] vehicle = {null};
        DiagnosticSession[] session = {null};
        String[] raw = {""};
        session[0] = new DiagnosticSession((key, source, state, detail, time) -> {
            if (state.equals("대기")) return;
            String vin = state.equals("수신") ? normalizeVin(raw[0]) : "";
            String invalid = invalidVinStatus(raw[0]);
            String received = state.equals("수신") && raw[0] != null ? raw[0] : "";
            raw[0] = "";
            session[0].close();
            if (vehicle[0] != null) try { vehicle[0].release(); } catch (RuntimeException ignored) { }
            String status = vin.equals("INVALID") ? "공통 식별키 invalid 사용 · 차량별 고유값 아님"
                    : !vin.isEmpty() ? "VIN 확인됨 · 기존 차량 식별에 사용"
                    : state.equals("수신") ? invalid
                    : "VIN " + state + " · " + detail;
            result.accept(received, status);
        });
        try {
            vehicle[0] = new Vehicle(context);
            vehicle[0].initialize();
            new CarInfoDiagnosticService(vehicle[0].getCarInfo(), session[0]).readVin(value -> raw[0] = value);
        } catch (RuntimeException failure) {
            session[0].begin("vehicle.vin", "Pleos getVINInfo", "", false).fail(failure);
        }
    }

}
