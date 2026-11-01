package com.pleos.fuelog.diagnostics;

/** A ratio of standard AAOS fuel volume readings, not the OEM's calibrated gauge value. */
final class FuelLevelPercent {
    private FuelLevelPercent() { }

    static double calculate(Object levelMl, Object capacityMl) {
        if (!(levelMl instanceof Number) || !(capacityMl instanceof Number)) {
            throw new IllegalArgumentException("Fuel volume readings must be numeric");
        }
        double level = ((Number) levelMl).doubleValue();
        double capacity = ((Number) capacityMl).doubleValue();
        if (!Double.isFinite(level) || !Double.isFinite(capacity)
                || capacity <= 0 || level < 0 || level > capacity) {
            throw new IllegalArgumentException("Fuel volume readings are outside the valid range");
        }
        return level / capacity * 100d;
    }
}
