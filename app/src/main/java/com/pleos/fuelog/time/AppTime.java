package com.pleos.fuelog.time;

import org.json.JSONObject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Preserve the instant in storage/wire values; apply the device zone only for display. */
public final class AppTime {
    private AppTime() {}
    public static long epochMillis(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number < 0 || number != Math.rint(number))
                throw new IllegalArgumentException("Invalid epoch milliseconds");
            return ((Number) value).longValue();
        }
        // An offset is required: interpreting a server's timezone-free value as local is ambiguous.
        return OffsetDateTime.parse(value.toString()).toInstant().toEpochMilli();
    }
    public static String label(Object value) { return label(value, ZoneId.systemDefault()); }
    public static String label(Object value, ZoneId zone) {
        try {
            long millis = epochMillis(value);
            return millis <= 0 ? "아직 없음" : DateTimeFormatter.ofPattern("MM.dd HH:mm:ss z", Locale.KOREA)
                    .withZone(zone).format(Instant.ofEpochMilli(millis));
        } catch (RuntimeException error) { return "시간 확인 필요"; }
    }
}
