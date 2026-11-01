package com.pleos.fuelog.diagnostics;

import android.car.Car;
import android.car.VehiclePropertyIds;
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
        observer.onState(allowed, current == null ? "UX 제한 상태 미확인"
                : "UX 제한 비트: 0x" + Integer.toHexString(current.getActiveRestrictions())
                + (allowed ? " · 정차 진단 가능" : " · 진단 중지, 정차 후 이용"));
    }

    public void readAll(DiagnosticSession session) {
        read(session, "aaos.fuel", VehiclePropertyIds.FUEL_LEVEL, "mL");
        read(session, "aaos.capacity", VehiclePropertyIds.INFO_FUEL_CAPACITY, "mL");
        read(session, "aaos.odometer", VehiclePropertyIds.PERF_ODOMETER, "km");
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
            try {
                CarPropertyConfig<?> config = manager.getCarPropertyConfig(propertyId);
                if (config == null) {
                    request.status("설정 없음", "미지원 또는 현재 권한으로 설정이 노출되지 않음");
                    return;
                }
                int access = config.getAccess();
                if (access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                        && access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE) {
                    request.status("읽기 불가", "property access=" + access);
                    return;
                }
                CarPropertyValue<?> value = manager.getProperty(propertyId, 0);
                if (value == null) { request.value(null); return; }
                if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
                    request.status("현재 사용 불가", "property status=" + value.getStatus());
                    return;
                }
                request.dispatch(() -> {
                    request.access("READ · sensor timestamp(ns since boot)=" + value.getTimestamp());
                    request.value(value.getValue());
                });
            } catch (RuntimeException error) { request.fail(error); }
        });
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
