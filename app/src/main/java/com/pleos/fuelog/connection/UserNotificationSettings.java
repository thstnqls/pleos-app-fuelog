package com.pleos.fuelog.connection;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pleos.fuelog.api.ApiClient;
import java.io.IOException;
import okhttp3.Call;
import org.json.JSONObject;

/** Only server-confirmed preferences for the currently paired user can enable notifications. */
public final class UserNotificationSettings {
    private final ConnectionSession connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String owner = "";
    private boolean enabled, loaded;
    public boolean busy;
    public String error = "";
    private int generation;
    private long checkedAt;
    private Call call;
    UserNotificationSettings(ConnectionSession connection) { this.connection = connection; }
    public boolean loaded() { return loaded && !owner.isEmpty() && owner.equals(connection.authenticatedSubject) && connection.linked; }
    public boolean enabled() { return loaded() && enabled; }
    public void clear() {
        generation++; if (call != null) call.cancel(); call = null;
        owner = ""; loaded = enabled = busy = false; checkedAt = 0; error = "";
    }
    public void refresh(Runnable changed) {
        if (!owner.equals(connection.authenticatedSubject)) clear();
        if (!connection.linked || connection.authenticatedSubject.isEmpty() || busy
                || checkedAt > 0 && SystemClock.elapsedRealtime() - checkedAt < 60000) return;
        request(null, changed);
    }
    public void save(boolean value, Runnable changed) {
        if (!loaded() || busy) return;
        request(value, changed);
    }
    private void request(Boolean value, Runnable changed) {
        owner = connection.authenticatedSubject;
        final String subject = owner;
        final int version = ++generation;
        busy = true; error = ""; checkedAt = SystemClock.elapsedRealtime();
        try {
            JSONObject body = value == null ? null : new JSONObject().put("chargeCompleteEnabled", value);
            call = connection.request(value == null ? "GET" : "PUT", "installation/users/" + subject + "/notification-settings", body, "", new ApiClient.ResultCallback() {
                private boolean current() { return version == generation && subject.equals(connection.authenticatedSubject) && connection.linked; }
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                    if (!current()) return;
                    busy = false; call = null;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        if (!subject.equals(result.getString("user_id"))) throw new IllegalArgumentException();
                        enabled = result.getBoolean("chargeCompleteEnabled"); loaded = true;
                    } catch (Exception invalid) { error = "알림 설정 응답을 확인하지 못했습니다"; }
                    changed.run();
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { fail(); }
                public void onFailure(IOException failure) { fail(); }
                private void fail() { main.post(() -> {
                    if (!current()) return;
                    busy = false; call = null; error = "알림 설정을 저장·조회하지 못했습니다. 다시 시도해 주세요"; changed.run();
                }); }
            });
        } catch (Exception failure) { busy = false; error = "알림 설정 서버에 연결할 수 없습니다"; }
        changed.run();
    }
}
