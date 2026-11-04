package com.pleos.fuelog.home;
import org.junit.Test;
import static org.junit.Assert.*;
public class TripStateTest {
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
