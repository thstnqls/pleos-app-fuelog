package com.pleos.fuelog.time;

import org.json.JSONObject;
import org.junit.Test;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class AppTimeTest {
    private final ZoneId seoul = ZoneId.of("Asia/Seoul");
    @Test public void utcIsoOffsetIsoAndEpochRepresentTheSameInstant() {
        long at = 1794013200000L;
        assertEquals(at, AppTime.epochMillis("2026-11-07T01:00:00Z"));
        assertEquals(at, AppTime.epochMillis("2026-11-07T10:00:00+09:00"));
        assertEquals(AppTime.label(at, seoul), AppTime.label("2026-11-07T01:00:00Z", seoul));
        assertTrue(AppTime.label(at, seoul).startsWith("11.07 10:00:00 "));
    }
    @Test public void conversionCrossesMidnightOnceWithoutChangingWireTimestamp() {
        String wire = "2026-11-07T18:30:00Z";
        assertTrue(AppTime.label(wire, seoul).startsWith("11.08 03:30:00 "));
        assertTrue(AppTime.label(wire, ZoneId.of("UTC")).startsWith("11.07 18:30:00 "));
        assertEquals(wire, com.pleos.fuelog.home.RecordingPayloads.iso(AppTime.epochMillis(wire)));
    }
    @Test public void absentAndTimezoneFreeValuesDoNotInventASaveTime() {
        assertEquals("아직 없음", AppTime.label(JSONObject.NULL, seoul));
        assertEquals("시간 확인 필요", AppTime.label("2026-11-07T10:00:00", seoul));
        assertEquals("시간 확인 필요", AppTime.label(Double.NaN, seoul));
    }
}
