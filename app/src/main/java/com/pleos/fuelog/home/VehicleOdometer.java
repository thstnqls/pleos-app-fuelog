package com.pleos.fuelog.home;

import ai.pleos.playground.vehicle.api.Odometer;
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener;
import android.os.Handler;
import android.os.Looper;
import kotlin.Unit;

/** Foreground vehicle reading, independent of the paired user and recording interval. */
public final class VehicleOdometer {
    private final Odometer odometer;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DistanceDrivenListener listener;
    private boolean closed;
    private int readGeneration;
    private Runnable readTimeout;
    public Float km;
    public long observedAt;
    public String readStatus = "조회 전", subscriptionStatus = "구독 중지";

    public VehicleOdometer(Odometer odometer, Runnable changed) {
        this.odometer = odometer;
        this.changed = changed;
    }

    public boolean subscribed() { return listener != null; }

    public void read() {
        if (closed) return;
        cancelRead();
        int request = readGeneration;
        readStatus = "조회 중";
        readTimeout = () -> {
            readGeneration++;
            readStatus = "조회 시간 초과 · 다시 조회해 주세요";
            changed.run();
        };
        main.postDelayed(readTimeout, 12000);
        changed.run();
        try {
            odometer.getCurrentDistanceDriven(value -> {
                main.post(() -> {
                    if (closed || request != readGeneration) return;
                    cancelRead();
                    accept(value);
                });
                return Unit.INSTANCE;
            }, error -> {
                main.post(() -> {
                    if (closed || request != readGeneration) return;
                    cancelRead();
                    readStatus = "거리 조회 실패 · 다시 조회해 주세요";
                    changed.run();
                });
                return Unit.INSTANCE;
            });
        } catch (RuntimeException error) {
            cancelRead();
            readStatus = "거리 조회 실패 · 다시 조회해 주세요";
            changed.run();
        }
    }

    public void startUpdates() {
        if (closed || subscribed()) return;
        listener = new DistanceDrivenListener() {
            @Override public void onDistanceDrivenUpdated(Float value) {
                main.post(() -> {
                    if (closed || listener != this) return;
                    cancelRead();
                    subscriptionStatus = "실시간 구독 중";
                    accept(value);
                });
            }
            @Override public void onFailed(Exception error) {
                main.post(() -> {
                    if (closed || listener != this) return;
                    stopUpdates();
                    subscriptionStatus = subscribed() ? "구독 오류 · 중지 후 다시 시작해 주세요" : "구독 오류 · 다시 시작해 주세요";
                    changed.run();
                });
            }
        };
        subscriptionStatus = "실시간 구독 중 · 변경 수신 대기";
        try { odometer.registerDistanceDriven(listener); }
        catch (RuntimeException error) { listener.onFailed(error); }
        changed.run();
    }

    public void stopUpdates() {
        if (listener == null) return;
        try {
            odometer.unregisterDistanceDriven(listener);
            listener = null;
            subscriptionStatus = "구독 중지";
        } catch (RuntimeException error) { subscriptionStatus = "구독 해제 실패 · 다시 중지해 주세요"; }
        if (!closed) changed.run();
    }

    private void accept(Float value) {
        if (value == null || !Float.isFinite(value) || value < 0) {
            readStatus = "유효한 거리 없음 · 이전 수신값을 확인해 주세요";
        } else {
            km = value;
            observedAt = System.currentTimeMillis();
            readStatus = "수신 완료";
        }
        changed.run();
    }

    private void cancelRead() {
        readGeneration++;
        if (readTimeout != null) main.removeCallbacks(readTimeout);
    }

    public void close() {
        closed = true;
        cancelRead();
        stopUpdates();
        main.removeCallbacksAndMessages(null);
    }
}
