package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.home.HomeRecorder;
import org.json.JSONObject;
import java.util.UUID;

/** Durable revocation intent. Remote completion is never inferred from local logout. */
public final class ConnectionSettings {
    private final SharedPreferences store;
    private final Context context;
    private final ConnectionSession connection;
    private final HomeRecorder recorder;
    private final Handler main = new Handler(Looper.getMainLooper());
    public JSONObject snapshot = new JSONObject();
    public boolean busy;
    public String message = "";
    public ConnectionSettings(Context context, ConnectionSession connection, HomeRecorder recorder) {
        this.context = context.getApplicationContext();
        this.store = context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE);
        this.connection = connection; this.recorder = recorder; message = "서버 상태 확인 필요";
    }
    public String pending() { return store.getString("pending", ""); }
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
        try { recorder.lockPersonalSession(); }
        catch (Exception e) { message = "개인 화면 잠금 또는 기록 저장 실패 · 다시 시도해 주세요"; changed.run(); return; }
        busy = true;
        com.pleos.fuelog.auth.AuthModule.clearLocal(context, new com.pleos.fuelog.auth.SupabaseAuth.Callback<Void>() {
            public void onSuccess(Void ignored) { main.post(() -> { busy = false; request("connection/disconnect", true, changed); }); }
            public void onError(java.io.IOException error) { main.post(() -> { busy = false; message = "접근 정보 삭제 실패 · 로컬 잠금 유지, 재시도 필요"; changed.run(); }); }
        });
        changed.run();
    }
    public void refresh(Runnable changed) { if (!busy) request("connection/settings", false, changed); }
    private void request(String path, boolean revoke, Runnable changed) {
        busy = true;
        try {
            JSONObject body = new JSONObject().put("kind", pending()).put("operationId", store.getString("operation", ""));
            connection.manage(path, body.toString(), new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse result) { main.post(() -> {
                    busy = false;
                    try {
                        JSONObject value = new JSONObject(result.getBody());
                        if (revoke) {
                            if (!store.getString("operation", "").equals(value.getString("operationId"))) throw new IllegalArgumentException();
                            if (value.getString("status").equals("COMPLETED")) {
                                if (pending().equals("VEHICLE")) connection.registrationReset();
                                recorder.remoteDisconnected();
                                if (!store.edit().remove("pending").remove("operation").commit()) throw new java.io.IOException();
                                snapshot = new JSONObject(); message = "서버 연결 해제 완료";
                            } else message = pending().equals("VEHICLE") ? "관리자 모바일 재인증 또는 서버 초기화 대기 중" : "로컬 잠금 완료 · 서버 연결 해제 대기 중";
                        } else { snapshot = value; message = "서버 상태 확인됨"; }
                    } catch (Exception e) { message = "서버 상태 확인 실패 · 요청을 유지합니다"; }
                    changed.run();
                }); }
                public void onHttpError(ApiClient.ApiResponse result) { fail(); }
                public void onFailure(java.io.IOException error) { fail(); }
                private void fail() { main.post(() -> { busy = false; message = "서버 연결 실패 · 해제 요청은 보관됩니다"; changed.run(); }); }
            });
        } catch (Exception e) { busy = false; message = "서버 설정을 확인해 주세요 · 해제 요청은 보관됩니다"; changed.run(); }
        changed.run();
    }
}
