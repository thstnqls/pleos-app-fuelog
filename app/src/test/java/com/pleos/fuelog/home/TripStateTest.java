package com.pleos.fuelog.home;
import org.junit.Test;
import static org.junit.Assert.*;
public class TripStateTest {
    @Test public void driveStartsInDAndEndsOnlyInPark() {
        TripState trip = new TripState();
        assertFalse(trip.shouldStart("P"));
        assertFalse(trip.shouldStart("R"));
        assertTrue(trip.shouldStart("D"));
        trip.start();
        trip.sample(100, 1000);
        assertFalse(trip.shouldStart("D"));
        assertFalse(trip.shouldEnd("R"));
        assertFalse(trip.shouldEnd(""));
        trip.sample(112, 2000);
        assertTrue(trip.shouldEnd("P"));
        assertEquals(100, trip.startKm, 0);
        assertEquals(112, trip.endKm, 0);
        assertEquals(12, trip.distance, 0.001);
        trip.stop();
        assertFalse(trip.shouldEnd("P"));
        assertTrue(trip.shouldStart("D"));
    }

    @Test public void distanceUsesSameMeterPrecisionAsWireSamples() {
        TripState trip = new TripState(); trip.start();
        trip.sample(1000f, 1000); trip.sample(1000.1f, 2000); trip.sample(1000.2f, 3000);
        assertEquals(.200, trip.distance, 0);
        trip.sample(3000, 4000);
        assertTrue(trip.missing);
        assertEquals(.200, trip.distance, 0);
    }
    @Test public void countsOnlyObservedIntervalAndDoesNotBridgeGaps() {
        TripState trip = new TripState(); trip.start();
        trip.sample(100, 1000); trip.sample(102, 2000);
        trip.interrupt(); trip.sample(120, 3000); trip.sample(121, 4000);
        assertEquals(3, trip.distance, 0.001);
        assertEquals("일부 누락", trip.status(4000));
    }
    @Test public void resetAndInvalidReadingsNeverProduceNegativeDistance() {
        TripState trip = new TripState(); trip.start();
        trip.sample(100, 1000); trip.sample(2, 2000); trip.sample(Double.NaN, 3000);
        assertEquals(0, trip.distance, 0.001);
        assertEquals(2000, trip.sampledAt);
        assertTrue(trip.missing);
    }
    @Test public void recordingRequiresFreshSampleAndStopsAcceptingSamples() {
        TripState trip = new TripState(); trip.start();
        assertEquals("기록 준비", trip.status(1000));
        trip.sample(100, 1000);
        assertEquals("기록 중", trip.status(2000));
        assertEquals("일부 누락", trip.status(17000));
        trip.stop(); trip.sample(150, 18000);
        assertEquals(0, trip.distance, 0.001);
    }
}
