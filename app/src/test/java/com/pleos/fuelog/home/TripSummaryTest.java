package com.pleos.fuelog.home;
import org.junit.Test;
import static org.junit.Assert.*;
public class TripSummaryTest {
    @Test public void acceptanceExample() {
        double liters = TripSummary.consumption(40, 36, true, false, false, false);
        assertEquals(4, liters, 0);
        assertEquals(15, TripSummary.efficiency(60, liters), 0);
        assertEquals(6800, TripSummary.cost(liters, 1700, true), 0);
    }
    @Test public void zeroFuelAndInvalidPriceAreUnavailable() {
        assertTrue(Double.isNaN(TripSummary.efficiency(60, 0)));
        assertTrue(Double.isNaN(TripSummary.cost(0, 1700, true)));
        assertTrue(Double.isNaN(TripSummary.cost(4, 1700, false)));
    }
    @Test public void cannotCombineRefuelingMissingOrDifferentOwners() {
        assertTrue(Double.isNaN(TripSummary.consumption(40, 36, true, false, true, false)));
        assertTrue(Double.isNaN(TripSummary.consumption(40, 36, true, true, false, false)));
        assertTrue(Double.isNaN(TripSummary.consumption(40, 36, true, false, false, true)));
        assertTrue(Double.isNaN(TripSummary.consumption(40, 36, false, false, false, false)));
    }
}
