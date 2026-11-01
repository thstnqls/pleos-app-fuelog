package com.pleos.fuelog.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public class FuelLevelPercentTest {
    @Test public void volumesProducePercentWithoutUnitConversion() {
        assertEquals(50d, FuelLevelPercent.calculate(25000f, 50000f), 0.00001d);
        assertEquals(25d, FuelLevelPercent.calculate(7500, 30000d), 0.00001d);
    }

    @Test public void emptyAndFullAreValidReadings() {
        assertEquals(0d, FuelLevelPercent.calculate(0f, 50000f), 0d);
        assertEquals(100d, FuelLevelPercent.calculate(50000f, 50000f), 0d);
    }

    @Test public void zeroCapacityAndImpossibleVolumesAreRejected() {
        for (double[] pair : new double[][]{{0, 0}, {1, 0}, {-1, 100}, {101, 100}, {0, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> FuelLevelPercent.calculate(pair[0], pair[1]));
        }
    }

    @Test public void invalidOrMissingSensorsCannotBecomeZeroPercent() {
        for (Object invalid : new Object[]{null, "0", Float.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> FuelLevelPercent.calculate(invalid, 100));
            assertThrows(IllegalArgumentException.class, () -> FuelLevelPercent.calculate(0, invalid));
        }
    }
}
