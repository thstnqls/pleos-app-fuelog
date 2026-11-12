package com.pleos.fuelog.home;

import com.pleos.fuelog.time.AppTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** Totals use known values only; missing measurements are counted separately. */
public final class RecordStatistics {
    public final double[] values;
    public final int[] measured, counts;
    public final LocalDate date;
    public final int period; // 0: day, 1: month, 2: year
    public final ZoneId zone;
    public double total;
    public BigDecimal amount = BigDecimal.ZERO;
    public int count, missingValues, missingAmounts;

    public RecordStatistics(JSONArray records, LocalDate date, int period, ZoneId zone) {
        if (period < 0 || period > 2) throw new IllegalArgumentException("Invalid period");
        this.date = date; this.period = period; this.zone = zone;
        int size = period == 0 ? 24 : period == 1 ? date.lengthOfMonth() : 12;
        values = new double[size]; measured = new int[size]; counts = new int[size];
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row == null) continue;
            int bucket = bucket(row.opt("statAt"));
            if (bucket < 0) continue;
            count++; counts[bucket]++;
            double value = row.optDouble("statValue", Double.NaN);
            if (Double.isFinite(value) && value >= 0) { values[bucket] += value; total += value; measured[bucket]++; }
            else missingValues++;
            double cost = row.optDouble("statAmount", Double.NaN);
            if (Double.isFinite(cost) && cost >= 0) amount = amount.add(TripHistory.won(cost));
            else missingAmounts++;
        }
    }

    public int bucket(Object timestamp) {
        try {
            long millis = AppTime.epochMillis(timestamp);
            if (millis <= 0) return -1;
            var observed = Instant.ofEpochMilli(millis).atZone(zone);
            LocalDate day = observed.toLocalDate();
            if (period == 0) return day.equals(date) ? observed.getHour() : -1;
            if (day.getYear() != date.getYear()) return -1;
            if (period == 1) return day.getMonth() == date.getMonth() ? day.getDayOfMonth() - 1 : -1;
            return day.getMonthValue() - 1;
        } catch (RuntimeException invalid) { return -1; }
    }
}
