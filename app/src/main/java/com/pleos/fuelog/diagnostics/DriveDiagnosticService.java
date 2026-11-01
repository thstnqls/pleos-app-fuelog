package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.Brake;
import ai.pleos.playground.vehicle.api.DrivingMode;
import ai.pleos.playground.vehicle.api.Odometer;
import ai.pleos.playground.vehicle.api.SteeringWheel;
import ai.pleos.playground.vehicle.constant.control.CruiseControl;
import ai.pleos.playground.vehicle.constant.control.DrivingModeType;
import kotlin.Unit;

/** The thirteen requested CAR_DRIVE getters, with independent screen-scoped requests. */
public final class DriveDiagnosticService extends PleosReadSupport {
    public enum Reading {
        MODE("현재 드라이브 모드", "getCurrentDrivingModeState", ""),
        TRIP_CUMULATIVE_DISTANCE("Trip 누적 주행거리", "getTripCumulativeDrivingDistance", "m"),
        TRIP_CUMULATIVE_TIME("Trip 누적 주행시간", "getTripCumulativeDrivingTime", "분"),
        TRIP_CURRENT_DISTANCE("현재 Trip 주행거리", "getTripCurrentDrivingDistance", "m"),
        TRIP_CURRENT_TIME("현재 Trip 주행시간", "getTripCurrentDrivingTime", "분"),
        TRIP_INSTANT_DISTANCE("현재 Trip 순간 주행거리", "getTripInstantaneousDrivingDistance", "m"),
        TRIP_REFILL_DISTANCE("주유 이후 주행거리", "getTripSinceRefillDrivingDistance", "m"),
        TRIP_REFILL_TIME("주유 이후 주행시간", "getTripSinceRefillDrivingTime", "분"),
        CRUISE_SPEED("크루즈 목표 속도", "getCruiseControlTargetSpeed", "m/s"),
        BRAKE("브레이크 페달 눌림", "getCurrentBrakePedalStatus", ""),
        CRUISE_STATE("크루즈 컨트롤 상태", "getCruiseControlState", ""),
        ODOMETER("총 누적 주행거리", "getCurrentDistanceDriven", "km"),
        STEERING("전륜 조향각", "getCurrentSteeringWheelAngle", "°");

        public final String label, api, unit, key;
        Reading(String label, String api, String unit) {
            this.label = label;
            this.api = api;
            this.unit = unit;
            this.key = "drive." + api;
        }
    }

    private final DrivingMode drivingMode;
    private final Odometer odometer;
    private final Brake brake;
    private final SteeringWheel steering;

    public DriveDiagnosticService(DrivingMode drivingMode, Odometer odometer, Brake brake,
                                  SteeringWheel steering, DiagnosticSession session) {
        super(session);
        this.drivingMode = drivingMode;
        this.odometer = odometer;
        this.brake = brake;
        this.steering = steering;
    }

    public void readAll() {
        for (Reading reading : Reading.values()) read(reading);
    }

    @SuppressWarnings("deprecation") // Explicitly requested legacy instantaneous Trip getter.
    public void read(Reading item) {
        switch (item) {
            case MODE:
                this.<DrivingModeType>read(item.key, item.unit, drivingMode::checkDrivingModeStateCapabilities,
                        drivingMode::getCurrentDrivingModeState);
                break;
            case TRIP_CUMULATIVE_DISTANCE:
                this.<Float>read(item.key, item.unit, odometer::checkTripCumulativeDrivingDistanceCapabilities,
                        odometer::getTripCumulativeDrivingDistance);
                break;
            case TRIP_CUMULATIVE_TIME:
                this.<Integer>read(item.key, item.unit, odometer::checkTripCumulativeDrivingTimeCapabilities,
                        odometer::getTripCumulativeDrivingTime);
                break;
            case TRIP_CURRENT_DISTANCE:
                this.<Float>read(item.key, item.unit, odometer::checkTripCurrentDrivingDistanceCapabilities,
                        odometer::getTripCurrentDrivingDistance);
                break;
            case TRIP_CURRENT_TIME:
                this.<Integer>read(item.key, item.unit, odometer::checkTripCurrentDrivingTimeCapabilities,
                        odometer::getTripCurrentDrivingTime);
                break;
            case TRIP_INSTANT_DISTANCE:
                this.<Float>read(item.key, item.unit, odometer::checkTripInstantaneousDrivingDistanceCapabilities,
                        odometer::getTripInstantaneousDrivingDistance);
                break;
            case TRIP_REFILL_DISTANCE:
                this.<Float>read(item.key, item.unit, odometer::checkTripSinceRefillDrivingDistanceCapabilities,
                        odometer::getTripSinceRefillDrivingDistance);
                break;
            case TRIP_REFILL_TIME:
                this.<Integer>read(item.key, item.unit, odometer::checkTripSinceRefillDrivingTimeCapabilities,
                        odometer::getTripSinceRefillDrivingTime);
                break;
            case CRUISE_SPEED:
                this.<Float>read(item.key, item.unit, drivingMode::checkCruiseControlTargetSpeedCapabilities,
                        drivingMode::getCruiseControlTargetSpeed);
                break;
            case BRAKE:
                this.<Boolean>read(item.key, item.unit, brake::checkBrakePedalStatusCapabilities,
                        brake::getCurrentBrakePedalStatus);
                break;
            case CRUISE_STATE:
                this.<CruiseControl>read(item.key, item.unit, drivingMode::checkCruiseControlStateCapabilities,
                        drivingMode::getCruiseControlState);
                break;
            case ODOMETER:
                this.<Float>read(item.key, item.unit, odometer::checkCurrentDistanceDrivenCapabilities,
                        odometer::getCurrentDistanceDriven);
                break;
            case STEERING:
                // The steering capability includes bounds; this screen needs its access config.
                this.<Float>read(item.key, item.unit, true, (success, failure) ->
                        steering.checkSteeringWheelAngleCapabilities((config, bounds) -> {
                            success.invoke(config);
                            return Unit.INSTANCE;
                        }, failure), steering::getCurrentSteeringWheelAngle);
                break;
        }
    }
}
