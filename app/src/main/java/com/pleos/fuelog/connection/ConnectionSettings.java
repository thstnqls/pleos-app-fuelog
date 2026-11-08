package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.home.HomeRecorder;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Durable revocation intent. Remote completion is never inferred from local logout. */
public final class ConnectionSettings {
    private final SharedPreferences store;
    private final ConnectionSession connection;
    private final HomeRecorder recorder;
    private final Handler main = new Handler(Looper.getMainLooper());
    public JSONObject snapshot = new JSONObject();
    public boolean busy;
    public String message = "";

    // 1. 설정 진입: 보관된 연결 해제 요청과 서버 상태를 확인한다.
    public ConnectionSettings(Context context, ConnectionSession connection, HomeRecorder recorder) {
        this.store = context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE);
        this.connection = connection; this.recorder = recorder; message = "서버 상태 확인 필요";
    }

    public String pending() { return store.getString("pending", ""); }

    public void refresh(Runnable changed) { if (!busy) request("connection/settings", false, changed); }

    // 2. 연결 해제: 의도를 먼저 저장하고 개인 화면 잠금 후 서버에 요청한다.
    public void begin(String kind, Runnable changed) {
        if (busy || !pending().isEmpty()) return;
        if (!store.edit().putString("pending", kind).putString("operation", UUID.randomUUID().toString()).commit()) {
            message = "해제 요청 저장 실패 · 다시 시도해 주세요"; changed.run(); return;
        }
        snapshot = new JSONObject();
        retry(changed);
    }

    public void retry(Runnable changed) {
        if (busy || pending().isEmpty()) return;
        try { connection.clearPersonal(); recorder.lockPersonalSession(); }
        catch (Exception e) { message = "개인 화면 잠금 또는 기록 저장 실패 · 다시 시도해 주세요"; changed.run(); return; }
        request("connection/disconnect", true, changed);
    }

    // 공통 요청: 서버가 완료를 확인할 때까지 해제 의도를 보존한다.
    private void request(String path, boolean revoke, Runnable changed) {
        busy = true;
        try {
            JSONObject body = new JSONObject().put("kind", pending().isEmpty() ? "USER" : pending())
                    .put("operationId", store.getString("operation", "settings"));
            connection.manage(path, body.toString(), new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse result) { main.post(() -> {
                    busy = false;
                    try {
                        JSONObject value = new JSONObject(result.getBody());
                        if (revoke) {
                            if (!store.getString("operation", "").equals(value.getString("operationId"))) throw new IllegalArgumentException();
                            if (value.getString("status").equals("COMPLETED")) {
                                connection.registrationReset();
                                recorder.remoteDisconnected();
                                if (!store.edit().remove("pending").remove("operation").commit()) throw new IOException();
                                snapshot = new JSONObject(); message = "서버 연결 해제 완료";
                            } else message = pending().equals("VEHICLE") ? "관리자 모바일 재인증 또는 서버 초기화 대기 중" : "로컬 잠금 완료 · 서버 연결 해제 대기 중";
                        } else {
                            snapshot = value;
                            if (!value.isNull("installationId")) connection.installationId = value.optString("installationId");
                            message = "서버 상태 확인됨";
                        }
                    } catch (Exception e) { connection.apiStatus().invalid("POST", path); message = "연결 필요 · 서버 응답 확인 실패 · 요청을 유지합니다"; }
                    changed.run();
                }); }
                public void onHttpError(ApiClient.ApiResponse result) {
                    fail(result.getStatusCode() == 404
                            ? "서버 API 없음 · HTTP 404 · 백엔드 배포를 확인해 주세요 · 해제 요청은 보관됩니다"
                            : "서버 요청 실패 · HTTP " + result.getStatusCode() + " · 해제 요청은 보관됩니다");
                }
                public void onFailure(IOException error) { fail("서버 연결 실패 · 해제 요청은 보관됩니다"); }
                private void fail(String detail) { main.post(() -> { busy = false; message = detail; changed.run(); }); }
            });
        } catch (Exception e) { busy = false; message = "서버 설정을 확인해 주세요 · 해제 요청은 보관됩니다"; changed.run(); }
        changed.run();
    }
}
