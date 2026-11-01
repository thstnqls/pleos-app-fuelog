package com.pleos.fuelog.diagnostics;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/** Screen-scoped requests. Every publication is serialized on the main thread. */
public final class DiagnosticSession {
    public interface Observer {
        void onReading(String key, String source, String state, String value, long receivedAt);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Request> requests = new HashMap<>();
    private final Observer observer;
    private boolean closed;

    public DiagnosticSession(Observer observer) { this.observer = observer; }

    public Request begin(String key, String source, String unit, boolean stream) {
        Request previous = requests.remove(key);
        if (previous != null) main.removeCallbacks(previous.timeout);
        Request request = new Request(key, source, unit, stream);
        if (closed) return request;
        requests.put(key, request);
        request.publish("대기", "응답 대기 중");
        main.postDelayed(request.timeout, 12000);
        return request;
    }

    public void stop(String key) {
        Request request = requests.remove(key);
        if (request != null) {
            main.removeCallbacks(request.timeout);
            if (!closed) observer.onReading(key, request.source, "중지", "구독 해제", System.currentTimeMillis());
        }
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        requests.clear();
    }

    public final class Request {
        private final String key, source, unit;
        private final boolean stream;
        private boolean finished;
        private String capability = "";
        private final Runnable timeout = () -> {
            if (isActive()) finish("시간 초과", "12초 내 응답 없음 · 구독 중이면 중지 후 재시작");
        };

        private Request(String key, String source, String unit, boolean stream) {
            this.key = key;
            this.source = source;
            this.unit = unit;
            this.stream = stream;
        }

        public void dispatch(Runnable action) {
            main.post(() -> {
                if (isActive()) {
                    try { action.run(); } catch (RuntimeException error) { fail(error); }
                }
            });
        }

        public boolean isActive() { return !closed && !finished && requests.get(key) == this; }

        public void access(String access) { capability = " · 접근 " + access; }

        public void status(String state, String detail) {
            dispatch(() -> finish(state, detail));
        }

        public void value(Object value) {
            dispatch(() -> {
                if (value == null) {
                    complete("값 없음", "null · 현재 유효한 값이 없음");
                } else if (value instanceof Number
                        && !Double.isFinite(((Number) value).doubleValue())) {
                    complete("값 오류", "유한한 수가 아님");
                } else if (value instanceof Number && (((Number) value).doubleValue() < 0
                        || (unit.equals("%") && ((Number) value).doubleValue() > 100))) {
                    complete("값 오류", value + " " + unit + " · 범위 확인 필요");
                } else {
                    complete("수신", value + (unit.isEmpty() ? "" : " " + unit));
                }
            });
        }

        public void fail(Exception error) {
            // VehicleException is obfuscated in the published AAR. Extract only symbolic
            // error codes from its public message rather than depend on a source-only type.
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("\\bERROR_[A-Z0-9_]+\\b")
                    .matcher(String.valueOf(error.getMessage()));
            String detail = code.find() ? code.group() : error.getClass().getSimpleName();
            String state = detail.contains("NOT_SUPPORTED") ? "미지원"
                    : error instanceof SecurityException || detail.contains("SECURITY_PERMISSION") ? "권한 오류"
                    : detail.contains("SERVICE_CONNECTION") ? "연결 오류" : "호출 오류";
            status(state, detail);
        }

        private void complete(String state, String value) {
            main.removeCallbacks(timeout);
            publish(state, value);
            if (!stream) finished = true;
        }

        private void finish(String state, String detail) {
            if (!isActive()) return;
            main.removeCallbacks(timeout);
            publish(state, detail);
            finished = true;
        }

        private void publish(String state, String value) {
            if (!closed) observer.onReading(key, source, state, value + capability, System.currentTimeMillis());
        }
    }
}
