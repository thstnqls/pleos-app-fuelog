package com.pleos.fuelog.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.api.ApiModule;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Process-scoped session: rotation never creates a second registration request. */
public final class ConnectionSession {
    private static ConnectionSession instance;
    public static synchronized ConnectionSession get(Context context) {
        if (instance == null) instance = new ConnectionSession(context.getApplicationContext());
        return instance;
    }
    public String state = "IDLE", requestId = "", payload = "", message = "";
    public boolean linked;
    private long deadline;
    private boolean busy;
    private String operation;
    private String credential;
    private File identity;
    private ApiClient api;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectionSession(Context context) {
        try {
            identity = new File(context.getNoBackupFilesDir(), "connection.json");
            JSONObject saved = identity.exists() ? new JSONObject(new String(Files.readAllBytes(identity.toPath()), java.nio.charset.StandardCharsets.UTF_8)) : new JSONObject();
            credential = saved.optString("credential", UUID.randomUUID().toString() + UUID.randomUUID());
            operation = saved.optString("operation", UUID.randomUUID().toString());
            persist();
            api = ApiModule.create();
        } catch (Exception e) { message = "연결 서버 설정을 확인해 주세요"; state = "ERROR"; }
    }
    private void persist() throws Exception {
        Files.write(identity.toPath(), new JSONObject().put("credential", credential)
                .put("operation", operation).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public okhttp3.Call requestUser(String path, String body, ApiClient.ResultCallback callback) {
        if (api == null || !linked) throw new IllegalStateException("Vehicle connection required");
        return api.create(path, body, credential, callback);
    }
    public long remaining() { return Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000); }
    public void refresh(boolean online, Runnable changed) {
        if (!online || busy || api == null || linked) return;
        if (state.equals("WAITING") && remaining() == 0) {
            state = "EXPIRED"; payload = ""; changed.run(); return;
        }
        if (state.equals("IDLE")) send("connection/requests", changed);
        else if (state.equals("WAITING")) send("connection/requests/status", changed);
    }
    public void retry(Runnable changed) {
        if (busy || api == null) return;
        if (state.equals("EXPIRED") || state.equals("REVOKED")) {
            operation = UUID.randomUUID().toString();
            try { persist(); } catch (Exception e) { message = "연결 요청을 저장하지 못했습니다"; changed.run(); return; }
        }
        // A transport failure retries the same idempotency key.
        send("connection/requests", changed);
    }
    private void send(String path, Runnable changed) {
        busy = true;
        try {
            String body = new JSONObject().put("operationId", operation).put("requestId", requestId).toString();
            api.create(path, body, credential, new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) {
                    main.post(() -> {
                        busy = false;
                        try {
                            JSONObject value = new JSONObject(response.getBody());
                            String next = value.getString("status");
                            String id = value.getString("requestId");
                            if (path.endsWith("/status") && !requestId.equals(id)) throw new IllegalArgumentException();
                            if (!java.util.Arrays.asList("WAITING", "EXPIRED", "REVOKED", "COMPLETED", "ALREADY_LINKED").contains(next)) throw new IllegalArgumentException();
                            payload = "";
                            if (next.equals("WAITING")) {
                                long ttl = value.getLong("remainingSeconds");
                                if (ttl <= 0 || ttl > 120) throw new IllegalArgumentException();
                                long candidate = SystemClock.elapsedRealtime() + ttl * 1000;
                                deadline = id.equals(requestId) && deadline > 0 ? Math.min(deadline, candidate) : candidate;
                                // QR is an app-scanned JSON envelope, never a URL.
                                payload = new JSONObject().put("type", "fuelog.vehicle-registration").put("version", 1)
                                        .put("requestId", id).put("nonce", value.getString("nonce")).toString();
                            }
                            requestId = id; state = next; message = "";
                            linked = next.equals("COMPLETED") || next.equals("ALREADY_LINKED");
                        } catch (Exception e) { state = "ERROR"; payload = ""; message = "연결 응답을 확인하지 못했습니다"; }
                        changed.run();
                    });
                }
                public void onHttpError(ApiClient.ApiResponse response) { fail(); }
                public void onFailure(java.io.IOException error) { fail(); }
                private void fail() { main.post(() -> { busy = false; state = "ERROR"; payload = "";
                    message = "연결 요청에 실패했습니다. 다시 시도해 주세요"; changed.run(); }); }
            });
        } catch (Exception e) { busy = false; state = "ERROR"; message = "연결 요청을 만들지 못했습니다"; changed.run(); }
    }
}
