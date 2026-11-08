package com.pleos.fuelog.diagnostics;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.VehicleGear;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent standard-AAOS comparison. Values are never merged with Pleos values. */
public final class AaosVehicleDiagnosticService {
    public interface UxObserver { void onState(boolean allowed, String detail); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Car car;
    private CarPropertyManager properties;
    private CarUxRestrictionsManager restrictions;
    private volatile boolean closed;
    private UxObserver observer;

    public AaosVehicleDiagnosticService(Context context) { this.context = context; }

    public void start(UxObserver observer) {
        this.observer = observer;
        observer.onState(false, "AAOS 서비스 연결 및 UX 제한 확인 중");
        main.postDelayed(() -> {
            if (!closed && restrictions == null) {
                observer.onState(false, "AAOS/UX 서비스 응답 없음 · Pleos 에뮬레이터와 API 수준 확인 후 연결 재시도");
            }
        }, 12000);
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connectedCar, ready) -> {
                if (closed) return;
                if (!ready) {
                    properties = null;
                    restrictions = null;
                    observer.onState(false, "AAOS 연결 끊김 · 현재 값과 UX 제한 미확인");
                    return;
                }
                try {
                    properties = (CarPropertyManager) connectedCar.getCarManager(Car.PROPERTY_SERVICE);
                    restrictions = (CarUxRestrictionsManager) connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    if (restrictions == null) {
                        observer.onState(false, "UX 제한 서비스 없음");
                        return;
                    }
                    restrictions.registerListener(this::onRestrictions);
                    onRestrictions(restrictions.getCurrentCarUxRestrictions());
                } catch (RuntimeException error) {
                    observer.onState(false, "AAOS 초기화 오류: " + error.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException error) {
            observer.onState(false, "AAOS 연결 오류: " + error.getClass().getSimpleName());
        }
    }

    private void onRestrictions(CarUxRestrictions current) {
        if (closed) return;
        boolean allowed = current != null && !current.isRequiresDistractionOptimization()
                && current.getActiveRestrictions() == CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
        observer.onState(allowed, current == null ? "차량 이용 제한 상태를 확인하지 못했습니다. 연결을 다시 시도하세요."
                : (allowed ? "정차 확인됨 · 차량 데이터 조회 가능"
                : "차량 이용 제한으로 조회를 중지했습니다.\n정차 후 연결 재시도를 눌러 상태를 확인하세요.")
                + "\nUX 제한 비트: 0x" + Integer.toHexString(current.getActiveRestrictions()));
    }

    public void readFuel(DiagnosticSession session) {
        readFuelLevels(session);
        readFuelEconomy(session);
    }

    public void readFuelEconomy(DiagnosticSession session) {
        read(session, "aaos.fuel.instant", VehiclePropertyIds.INSTANTANEOUS_FUEL_ECONOMY, "L/100km");
    }

    public void readFuelLevels(DiagnosticSession session) {
        DiagnosticSession.Request level = session.begin("aaos.fuel", "AAOS FUEL_LEVEL", "mL", false);
        DiagnosticSession.Request capacity = session.begin("aaos.capacity", "AAOS INFO_FUEL_CAPACITY", "mL", false);
        DiagnosticSession.Request percent = session.begin("aaos.fuel.percent",
                "AAOS 계산 · FUEL_LEVEL / INFO_FUEL_CAPACITY × 100", "%", false);
        CarPropertyManager manager = properties;
        if (manager == null) {
            for (DiagnosticSession.Request request : new DiagnosticSession.Request[]{level, capacity, percent}) {
                request.status("연결 안 됨", "CarPropertyManager 없음");
            }
            return;
        }
        io.execute(() -> {
            if (closed) return;
            CarPropertyValue<?> levelValue = readAndPublish(manager, VehiclePropertyIds.FUEL_LEVEL, level);
            CarPropertyValue<?> capacityValue = readAndPublish(manager, VehiclePropertyIds.INFO_FUEL_CAPACITY, capacity);
            if (levelValue == null || capacityValue == null) {
                percent.status("계산 불가", "표준 연료 잔량·탱크 용량을 모두 수신해야 계산할 수 있음");
                return;
            }
            try {
                double ratio = FuelLevelPercent.calculate(levelValue.getValue(), capacityValue.getValue());
                percent.dispatch(() -> {
                    percent.access("READ · 계산값 · sensor timestamps(ns since boot)="
                            + levelValue.getTimestamp() + "/" + capacityValue.getTimestamp());
                    percent.value(ratio);
                });
            } catch (IllegalArgumentException error) {
                percent.status("값 오류", "잔량·용량의 수치 범위 확인 필요 · 용량 0/유효하지 않은 값은 계산하지 않음");
            }
        });
    }

    public void readEvBattery(DiagnosticSession session) {
        read(session, "aaos.ev.energy", VehiclePropertyIds.EV_BATTERY_LEVEL, "Wh");
        read(session, "aaos.ev.usable", VehiclePropertyIds.EV_CURRENT_BATTERY_CAPACITY, "Wh");
        read(session, "aaos.ev.nominal", VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, "Wh");
    }

    public void readOdometer(DiagnosticSession session) {
        read(session, "aaos.odometer", VehiclePropertyIds.PERF_ODOMETER, "km");
    }

    public interface EvObserver { void onSample(String gear, double wh, double capacityWh); }

    /** Recording reads are independent of UI restrictions; no UI or permission requests here. */
    public void readEvRecording(EvObserver callback) {
        CarPropertyManager manager = properties;
        if (closed || manager == null) { callback.onSample("", Double.NaN, Double.NaN); return; }
        io.execute(() -> {
            double before = recordingValue(manager, VehiclePropertyIds.GEAR_SELECTION);
            double wh = recordingValue(manager, VehiclePropertyIds.EV_BATTERY_LEVEL);
            double capacity = recordingValue(manager, VehiclePropertyIds.EV_CURRENT_BATTERY_CAPACITY);
            if (!Double.isFinite(capacity) || capacity <= 0) capacity = recordingValue(manager, VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY);
            final double fullCapacity = capacity;
            double after = recordingValue(manager, VehiclePropertyIds.GEAR_SELECTION);
            String gear = before != after ? "" : before == VehicleGear.GEAR_DRIVE ? "D"
                    : before == VehicleGear.GEAR_REVERSE ? "R" : before == VehicleGear.GEAR_PARK ? "P" : "";
            main.post(() -> { if (!closed) callback.onSample(gear, wh, fullCapacity); });
        });
    }

    private double recordingValue(CarPropertyManager manager, int id) {
        try {
            CarPropertyConfig<?> config = manager.getCarPropertyConfig(id);
            if (config == null || (config.getAccess() != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                    && config.getAccess() != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE)) return Double.NaN;
            CarPropertyValue<?> value = manager.getProperty(id, 0);
            return value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                    && value.getValue() instanceof Number ? ((Number) value.getValue()).doubleValue() : Double.NaN;
        } catch (RuntimeException unavailable) { return Double.NaN; }
    }

    private void read(DiagnosticSession session, String key, int propertyId, String unit) {
        DiagnosticSession.Request request = session.begin(key, "AAOS 0x" + Integer.toHexString(propertyId), unit, false);
        CarPropertyManager manager = properties;
        if (manager == null) {
            request.status("연결 안 됨", "CarPropertyManager 없음");
            return;
        }
        io.execute(() -> {
            if (closed) return;
            readAndPublish(manager, propertyId, request);
        });
    }

    private CarPropertyValue<?> readAndPublish(CarPropertyManager manager, int propertyId,
                                               DiagnosticSession.Request request) {
        try {
            CarPropertyConfig<?> config = manager.getCarPropertyConfig(propertyId);
            if (config == null) {
                request.status("설정 없음", "미지원 또는 현재 권한으로 설정이 노출되지 않음");
                return null;
            }
            int access = config.getAccess();
            if (access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                    && access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE) {
                request.status("읽기 불가", "property access=" + access);
                return null;
            }
            CarPropertyValue<?> value = manager.getProperty(propertyId, 0);
            if (value == null) { request.value(null); return null; }
            if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
                request.status("현재 사용 불가", "property status=" + value.getStatus());
                return null;
            }
            request.dispatch(() -> {
                request.access("READ · sensor timestamp(ns since boot)=" + value.getTimestamp());
                request.value(value.getValue());
            });
            return value;
        } catch (RuntimeException error) {
            request.fail(error);
            return null;
        }
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (restrictions != null) {
            try { restrictions.unregisterListener(); } catch (RuntimeException ignored) { /* service may have died */ }
            restrictions = null;
        }
        if (car != null) {
            try { car.disconnect(); } catch (RuntimeException ignored) { /* already disconnected */ }
            car = null;
        }
        properties = null;
        io.shutdownNow();
    }
}
