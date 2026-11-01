package com.pleos.fuelog.diagnostics;

import android.content.Context;

import java.util.Locale;
import com.pleos.fuelog.location.PleosLocationClient;

/** Screen adapter for the typed, cancellable one-shot location API. */
public final class LocationDiagnosticService {
    private final DiagnosticSession session;
    private final PleosLocationClient client;
    private String pendingKey;

    public LocationDiagnosticService(Context context, DiagnosticSession session) {
        this.session = session;
        client = new PleosLocationClient(context);
    }

    public void readCurrentLocation() { read(true); }
    public void readLastLocation() { read(false); }

    private void read(boolean current) {
        String key = current ? "location.current" : "location.last";
        if (pendingKey != null && !pendingKey.equals(key)) session.stop(pendingKey);
        pendingKey = key;
        DiagnosticSession.Request request = session.begin(key, current
                ? "Pleos FusedLocation · 다음 위치 업데이트 1건" : "Pleos FusedLocation · getLastKnownLocation", "", false);
        PleosLocationClient.Callback callback = new PleosLocationClient.Callback() {
            @Override public void onSuccess(PleosLocationClient.Position value) {
                pendingKey = null;
                long age = System.currentTimeMillis() - value.getTimestampMillis();
                request.value(String.format(Locale.KOREA,
                        "위도 %.6f\n경도 %.6f\n고도 %.1f / 방향 %.1f / confidence(raw) %.2f\n"
                                + "측정 timestamp(ms): %d · 경과: %s\n%s",
                        value.getLatitude(), value.getLongitude(), value.getAltitude(), value.getHeading(),
                        value.getConfidence(), value.getTimestampMillis(),
                        value.getTimestampMillis() > 0 && age >= 0 ? (age / 1000) + "초" : "기기 시각 확인 필요",
                        value.isFromUpdate() ? "요청 이후 측정된 위치 업데이트 1건 · 수신 해제됨" : "마지막 저장 위치 · 새 측정이 아님"));
            }
            @Override public void onError(PleosLocationClient.ErrorCode code, Exception cause) {
                pendingKey = null;
                switch (code) {
                    case PERMISSION_DENIED: request.status("권한 오류", "FUSED_LOCATION 권한을 허용한 뒤 다시 조회하세요"); break;
                    case UNSUPPORTED: request.status("미지원", "이 환경에 Pleos FusedLocation 서비스가 없음"); break;
                    case NO_DATA: request.status("값 없음", "사용 가능한 위치 없음 · 차량 위치 공유 설정과 위치 공급 상태를 확인하세요"); break;
                    case INVALID_DATA: request.status("값 오류", "위도·경도 범위 또는 유효한 숫자 확인 필요"); break;
                    case TIMEOUT: request.status("시간 초과", "10초 내 유효한 위치 없음 · 위치 공유 설정·기기 시각·위치 공급 상태 확인 후 재조회"); break;
                    case SDK_ERROR: request.fail(cause == null ? new IllegalStateException() : cause); break;
                    case CLOSED: request.status("중지", "위치 API가 종료됨"); break;
                }
            }
        };
        if (current) client.getCurrentLocation(callback); else client.getLastKnownLocation(callback);
    }

    public void close() { pendingKey = null; client.close(); }
}
