package com.pleos.fuelog.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Records real attempts only; starting a request never implies a working connection. */
public final class ApiStatus {
    public static final class Entry {
        public final String key, detail;
        public final boolean connected, failed;
        private final long attempt;
        Entry(String key, String detail, boolean connected, boolean failed, long attempt) {
            this.key = key; this.detail = detail; this.connected = connected; this.failed = failed; this.attempt = attempt;
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    public volatile Runnable changed = () -> {};
    public static String key(String method, String path) {
        String clean = path.split("\\?", 2)[0].replaceFirst("^/", "");
        return method + " " + clean.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}");
    }
    public synchronized long begin(String method, String path) {
        String key = key(method, path);
        Entry previous = entries.get(key);
        long attempt = ++sequence;
        entries.put(key, new Entry(key, previous == null ? "연결 확인 중" : previous.detail,
                previous != null && previous.connected, previous != null && previous.failed, attempt));
        return attempt;
    }
    public void complete(String method, String path, long attempt, int status) {
        String key = key(method, path);
        synchronized (this) {
            Entry current = entries.get(key);
            if (current == null || current.attempt != attempt) return;
            boolean ok = status >= 200 && status < 300;
            String reason;
            switch (status) {
                case -1: reason = "네트워크 또는 서버 주소를 확인해 주세요"; break;
                case -2: reason = "서버 응답 형식을 확인해 주세요"; break;
                case 401: reason = "설치 인증을 확인해 주세요"; break;
                case 403: reason = "사용자 동의와 접근 권한을 확인해 주세요"; break;
                case 404: reason = "서버에 API가 없거나 요청 대상을 찾을 수 없습니다"; break;
                case 409: reason = "요청 상태가 충돌했습니다. 현재 연결 상태를 확인해 주세요"; break;
                case 410: reason = "요청이 만료되었거나 폐기되었습니다"; break;
                default: reason = "서버 요청을 처리하지 못했습니다";
            }
            entries.put(key, new Entry(key, ok ? "연결됨" : "연결 필요 · " + (status > 0 ? "HTTP " + status + " · " : "") + reason,
                    ok, !ok, attempt));
        }
        changed.run();
    }
    public synchronized void invalid(String method, String path) {
        String key = key(method, path);
        Entry current = entries.get(key);
        entries.put(key, new Entry(key, "연결 필요 · 서버 응답 형식을 확인해 주세요", false, true,
                current == null ? ++sequence : current.attempt));
        changed.run();
    }
    public synchronized Entry get(String method, String path) {
        String key = key(method, path);
        Entry entry = entries.get(key);
        return entry == null ? new Entry(key, "연결 확인 전 · 실제 요청 후 확인됩니다", false, false, 0) : entry;
    }
}
