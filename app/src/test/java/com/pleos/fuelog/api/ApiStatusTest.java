package com.pleos.fuelog.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiStatusTest {
    @Test public void onlySuccessfulResponseConfirmsConnection() {
        ApiStatus status = new ApiStatus();
        assertFalse(status.get("GET", "vehicle/home").connected);
        long attempt = status.begin("GET", "vehicle/home");
        assertFalse(status.get("GET", "vehicle/home").connected);
        status.complete("GET", "vehicle/home", attempt, 200);
        assertTrue(status.get("GET", "vehicle/home").connected);
        status.invalid("GET", "vehicle/home");
        assertTrue(status.get("GET", "vehicle/home").failed);
        assertFalse(status.get("GET", "vehicle/home").connected);
    }
    @Test public void failureAndRecoveryKeepActualStateDuringRetry() {
        ApiStatus status = new ApiStatus();
        long failed = status.begin("GET", "maintenance/summary");
        status.complete("GET", "maintenance/summary", failed, 404);
        assertTrue(status.get("GET", "maintenance/summary").detail.contains("HTTP 404"));
        long retry = status.begin("GET", "maintenance/summary");
        assertFalse(status.get("GET", "maintenance/summary").connected);
        status.complete("GET", "maintenance/summary", retry, 200);
        assertTrue(status.get("GET", "maintenance/summary").connected);
    }
    @Test public void oldResponsesCannotOverrideNewerAttempt() {
        ApiStatus status = new ApiStatus();
        long first = status.begin("GET", "sync/status");
        long second = status.begin("GET", "sync/status");
        status.complete("GET", "sync/status", second, 403);
        status.complete("GET", "sync/status", first, 200);
        assertTrue(status.get("GET", "sync/status").failed);
    }
    @Test public void tripIdsAndCursorAreNotStoredInStatusKey() {
        assertEquals("GET recording-intervals/{id}/summary", ApiStatus.key("GET", "recording-intervals/11111111-1111-4111-8111-111111111111/summary"));
        assertEquals("GET trip-user/events", ApiStatus.key("GET", "/trip-user/events?cursor=99"));
    }
}
