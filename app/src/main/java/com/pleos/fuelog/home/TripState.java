package com.pleos.fuelog.home;

/** A drive starts in D and ends in P. */
public final class TripState {
    public boolean active, missing;
    public double distance;
    public double startKm = Double.NaN, endKm = Double.NaN;

    public boolean shouldStart(String gear) { return !active && "D".equals(gear); }
    public boolean shouldEnd(String gear) { return active && "P".equals(gear); }
    public long sampledAt, savedAt;
    public int sampleCount;
    private double previous = Double.NaN;

    // 1. 기록 시작: 이전 구간의 거리와 수신 기준을 초기화한다.
    public void start() {
        active = true;
        startKm = endKm = Double.NaN;
        sampleCount = 0;
        missing = false;
        distance = 0;
        sampledAt = 0;
        previous = Double.NaN;
    }

    // 2. 거리 수신: 유효한 누적거리 사이의 차이만 이번 구간에 합산한다.
    public double previousKm() { return previous; }

    public void sample(double km, long now) {
        if (!active) return;
        if (!Double.isFinite(km) || km < 0) { interrupt(); return; }
        if (Double.isFinite(previous)) {
            if (km >= previous && km - previous <= 1000) {
                double delta = RecordingPayloads.decimalKm(km - previous).doubleValue();
                distance = RecordingPayloads.decimalKm(distance + delta).doubleValue();
            }
            else missing = true;
        }
        if (sampleCount == 0) startKm = km;
        endKm = km;
        previous = km;
        sampledAt = now;
        sampleCount++;
    }

    // 3. 수집 중단·기록 종료: 다음 수신값이 누락 구간을 연결하지 않게 한다.
    public void interrupt() {
        if (active) missing = true;
        endKm = Double.NaN;
        previous = Double.NaN;
    }

    public void stop() {
        active = false;
        previous = Double.NaN;
    }

    // 4. 화면 확인: 최근 수신 시각과 누락 여부를 상태 문구로 표시한다.
    public String status(long now) {
        if (!active) return "기록 준비";
        if (missing || sampledAt > 0 && now - sampledAt > 15000) return "일부 누락";
        return sampledAt == 0 ? "기록 준비" : "기록 중";
    }
}
