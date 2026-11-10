package com.pleos.fuelog.home;

import com.pleos.fuelog.time.AppTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only, shared vehicle maintenance data. Private notes and expenses are not parsed. */
public final class MaintenanceSummary {
    private static final long MAX_ODOMETER_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    public final Double odometerKm;
    public final long odometerObservedAt;
    public final List<Item> items;
    public final boolean canEdit;
    public final String editorSubjectId;

    public MaintenanceSummary(JSONObject response) throws JSONException {
        canEdit = response.optBoolean("canEdit");
        editorSubjectId = response.optString("editorSubjectId", "");
        JSONObject odometer = response.optJSONObject("odometer");
        Double km = odometer == null || odometer.isNull("km") ? null : odometer.optDouble("km", Double.NaN);
        if (km != null && (!Double.isFinite(km) || km < 0)) km = null;
        odometerKm = km;
        odometerObservedAt = odometer == null ? 0 : AppTime.epochMillis(odometer.opt("observedAt"));
        JSONArray list = response.optJSONArray("items");
        if (list == null) throw new JSONException("items missing");
        List<Item> parsed = new ArrayList<>();
        for (int i = 0; i < Math.min(2, list.length()); i++) {
            parsed.add(new Item(list.getJSONObject(i)));
        }
        items = Collections.unmodifiableList(parsed);
    }

    public boolean hasRecentOdometer(long now) {
        return odometerKm != null && odometerObservedAt > 0
                && odometerObservedAt <= now && now - odometerObservedAt <= MAX_ODOMETER_AGE_MS;
    }

    public static final class Item {
        public final String name;
        public final LocalDate lastDate, nextDate;
        public final Double lastKm, nextKm;
        public final Double intervalKm;
        public final Integer intervalMonths;
        public final String basis;
        public final String riskLevel;

        private Item(JSONObject value) throws JSONException {
            name = value.getString("name").trim();
            riskLevel = value.optString("riskLevel", "UNKNOWN");
            if (name.isEmpty()) throw new JSONException("item name missing");
            lastDate = date(value, "lastServiceDate");
            nextDate = date(value, "nextDueDate");
            lastKm = distance(value, "lastServiceKm");
            nextKm = distance(value, "nextDueKm");
            intervalKm = distance(value, "intervalKm");
            if (intervalKm != null && intervalKm <= 0) throw new JSONException("invalid intervalKm");
            if (value.has("intervalMonths") && !value.isNull("intervalMonths")) {
                double months = value.optDouble("intervalMonths", Double.NaN);
                if (!Double.isFinite(months) || months < 1 || months > 1200 || months != Math.rint(months))
                    throw new JSONException("invalid intervalMonths");
                intervalMonths = (int) months;
            } else intervalMonths = null;
            String source = value.optString("basis");
            basis = source.equals("USER") ? "차량 설정" : source.equals("MANUFACTURER")
                    ? "제조사 기준" : "기준 확인 필요";
        }

        public long daysUntil(LocalDate today) {
            return nextDate == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(today, nextDate);
        }

        public String replacementDday(LocalDate today) {
            if (nextDate == null) return "교체 D-day · 마지막 교체일과 기간 주기 확인 필요";
            long days = daysUntil(today);
            return days > 0 ? "교체 D-" + days + " · " + days + "일 남음"
                    : days == 0 ? "교체 D-DAY · 오늘 교체 예정"
                    : "교체 D+" + (-days) + " · " + (-days) + "일 지남";
        }

        public Double kmUntil(MaintenanceSummary summary, long now) {
            return nextKm == null || !summary.hasRecentOdometer(now) ? null : nextKm - summary.odometerKm;
        }

        public String status(MaintenanceSummary summary, LocalDate today, long now) {
            if ("DANGER".equals(riskLevel)) return "위험 · 설정한 교체 주기의 150% 이상 경과";
            Double distance = kmUntil(summary, now);
            boolean dateDue = daysUntil(today) <= 0;
            boolean distanceDue = distance != null && distance <= 0;
            if (dateDue && distanceDue) return "날짜·거리 기준 도래";
            if (dateDue) return "날짜 기준 도래";
            if (distanceDue) return "거리 기준 도래";
            if (nextDate == null && nextKm == null) return intervalKm != null || intervalMonths != null
                    ? "마지막 정비 기록을 확인해 주세요" : "모바일에서 정비 주기를 설정해 주세요";
            return "날짜 또는 거리 중 먼저 도달하면 정비 시기입니다";
        }

        private static LocalDate date(JSONObject value, String key) throws JSONException {
            if (value.isNull(key) || !value.has(key)) return null;
            try { return LocalDate.parse(value.getString(key)); }
            catch (RuntimeException error) { throw new JSONException("invalid " + key); }
        }

        private static Double distance(JSONObject value, String key) throws JSONException {
            if (value.isNull(key) || !value.has(key)) return null;
            double km = value.optDouble(key, Double.NaN);
            if (!Double.isFinite(km) || km < 0) throw new JSONException("invalid " + key);
            return km;
        }
    }
}
