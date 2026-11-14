package com.pleos.fuelog.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.api.ApiModule;
import com.pleos.fuelog.api.ApiStatus;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import okhttp3.Call;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-scoped session: rotation never creates a second registration request. */
public final class ConnectionSession {
    public final UserNotificationSettings notifications = new UserNotificationSettings(this);
    private static ConnectionSession instance;
    public String state = "IDLE", requestId = "", payload = "", message = "";
    public boolean linked;
    public String authenticatedSubject = "", authenticatedName = "";
    private okhttp3.Call userEmailCall;
    private int userEmailGeneration;
    private long userEmailCheckedAt;
    public String vehicleId = "", vehicleName = "";
    private long deadline;
    private boolean busy;
    private String operation;
    private String credential;
    private EncryptedJsonStore identity;
    private boolean restored;
    private final Context context;
    private boolean vinChecked, vinReading, vinSyncing;
    private String vin = "", vinSyncedVehicle = "";
    public String vinStatus = "차량 VIN 확인 중";
    public String receivedVin = "";
    public String confirmationCode = "", selectedVehicleName = "";
    private final ApiStatus unavailable = new ApiStatus();
    private ApiClient api;
    private final Handler main = new Handler(Looper.getMainLooper());
    public volatile JSONObject home = new JSONObject();
    public JSONObject syncStatus = new JSONObject(), queueStatus = new JSONObject();
    public Runnable onPersonalInvalid = () -> {};
    public String installationId = "";
    public String vehicleAlias = "";

    // 1. 앱 진입: 설치 인증과 진행 중인 차량 연결 요청을 복원한다.
    public static synchronized ConnectionSession get(Context context) {
        if (instance == null) instance = new ConnectionSession(context.getApplicationContext());
        return instance;
    }

    private ConnectionSession(Context context) { this(context, null); }

    ConnectionSession(Context context, ApiClient supplied) {
        this.context = context.getApplicationContext();
        vinChecked = supplied != null;
        try {
            File encrypted = new File(context.getNoBackupFilesDir(), "installation.bin");
            identity = new EncryptedJsonStore(encrypted);
            File previous = new File(context.getNoBackupFilesDir(), "connection.json");
            JSONObject saved = encrypted.exists() || !previous.exists() ? new JSONObject(identity.read())
                    : new JSONObject(new String(new AtomicFile(previous).readFully(), StandardCharsets.UTF_8));
            credential = saved.optString("credential");
            installationId = saved.optString("installationId");
            vehicleAlias = saved.optString("vehicleAlias");
            operation = saved.optString("operation", UUID.randomUUID().toString());
            requestId = saved.optString("requestId"); payload = saved.optString("payload");
            persist();
            // Remove the obsolete login cache, retaining the shared key used by device records.
            new AtomicFile(new File(context.getNoBackupFilesDir(), "supabase-auth.bin")).delete();
            api = supplied == null ? ApiModule.create() : supplied;
        } catch (Exception e) { message = "연결 서버 설정을 확인해 주세요"; state = "ERROR"; }
    }

    public void refreshIdentity(Runnable changed) {
        if (busy || api == null || !usesIssuedInstallation()) return;
        if (!prepareVin(changed)) return;
        pairingRequest("GET", "installations/me", null, changed);
    }

    public void recheckVin(Runnable changed) {
        if (vinReading) return;
        vinChecked = false; vinSyncedVehicle = ""; vinStatus = "차량 VIN 확인 중";
        prepareVin(changed);
    }

    private boolean prepareVin(Runnable changed) {
        if (vinChecked) return true;
        if (!vinReading) {
            vinReading = true;
            com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.readForIdentity(context, (value, status) -> {
                receivedVin = value; vin = com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.normalizeVin(value); vinChecked = true; vinReading = false;
                vinStatus = status;
                changed.run();
            });
        }
        return false;
    }

    public boolean checkingVehicle() {
        return vinReading || !vinChecked || vinSyncing
                || busy && installationId.isEmpty() && (credential == null || credential.isEmpty())
                || usesIssuedInstallation() && !restored && !state.equals("ERROR");
    }

    public String vehicleCheckMessage() {
        if(vinReading || !vinChecked) return "차량 VIN을 읽고 있습니다";
        if(vinSyncing) return "VIN과 등록된 차량 정보를 확인하고 있습니다";
        return "저장된 차량 연결 정보를 확인하고 있습니다";
    }

    private void syncVin(Runnable changed) {
        if (vin.isEmpty() || vehicleId.isEmpty() || vehicleId.equals(vinSyncedVehicle)) return;
        vinSyncedVehicle = vehicleId;
        vinSyncing = true;
        try {
            api.request("PUT", "vehicle/vin", new JSONObject().put("vin", vin).toString(), null, installationHeaders(),
                    new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {vinSyncing=false;vinStatus = "VIN과 기존 차량 연결 확인됨";changed.run();}); }
                public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> {vinSyncing=false;vinStatus = "VIN 연결 확인 실패 · 관리자 연결 또는 서버 배포 확인 필요";changed.run();}); }
                public void onFailure(IOException failure) { main.post(() -> {vinSyncing=false; vinSyncedVehicle = ""; vinStatus = "VIN 연결 미확인 · 네트워크 확인 필요";changed.run(); }); }
            });
        } catch (Exception failure) {vinSyncing=false; vinSyncedVehicle = ""; vinStatus = "VIN 연결 확인 실패";changed.run(); }
    }

    public boolean hasServer() { return api != null; }

    public ApiStatus apiStatus() { return api == null ? unavailable : api.status; }

    public boolean usesPairingApi() { return usesIssuedInstallation() || credential == null || credential.isEmpty(); }

    public boolean usesIssuedInstallation() { return !installationId.isEmpty(); }

    // 2. 차량 연결: 설치 등록 → QR 발급·조회 → 차량에서 확인.
    public void refresh(boolean online, Runnable changed) {
        if (!online || busy || api == null || linked) return;
        if (!prepareVin(changed)) return;
        // Server status wins over the QR countdown, including completion while the screen was away.
        if (usesIssuedInstallation()) { refreshPairing(false, changed); return; }
        if (state.equals("ERROR")) return;
        if (state.equals("WAITING") && remaining() == 0) {
            state = "EXPIRED"; payload = ""; changed.run(); return;
        }
        if (vehicleAlias.isBlank()) return;
        if (credential.isEmpty()) { issueInstallation(changed); return; }
        if (state.equals("IDLE")) send("connection/requests", changed);
        else if (state.equals("WAITING")) send("connection/requests/status", changed);
    }

    public void setVehicleAlias(String value) throws Exception {
        if (busy) throw new IllegalStateException("Connection request in progress");
        String alias = value.trim();
        if (alias.isEmpty() || alias.length() > 100) throw new IllegalArgumentException();
        String previous = vehicleAlias;
        vehicleAlias = alias;
        try { persist(); } catch (Exception failure) { vehicleAlias = previous; throw failure; }
    }

    public void renameVehicleAlias(String value, ApiClient.ResultCallback callback) throws Exception {
        String alias = value.trim();
        if (busy || api == null || alias.isEmpty() || alias.length() > 100) throw new IllegalArgumentException();
        busy = true;
        try {
            api.request("PUT", "vehicle/name", new JSONObject().put("vehicle_name", alias).toString(), credential, null,
                    new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                    busy = false;
                    try {
                        String name = new JSONObject(response.getBody()).getString("vehicle_name");
                        setVehicleAlias(name);
                        vehicleName = name;
                        home.put("vehicleAlias", name);
                        callback.onSuccess(response);
                    } catch (Exception failure) { callback.onFailure(new IOException("차량 이름 저장 확인 실패", failure)); }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> { busy = false; callback.onHttpError(response); }); }
                public void onFailure(IOException failure) { main.post(() -> { busy = false; callback.onFailure(failure); }); }
            });
        } catch (Exception failure) { busy = false; throw failure; }
    }

    public void retry(Runnable changed) {
        if (busy || api == null) return;
        if (!prepareVin(changed)) return;
        if (vehicleAlias.isBlank()) return;
        if (credential.isEmpty()) { issueInstallation(changed); return; }
        if (usesIssuedInstallation()) { refreshPairing(true, changed); return; }
        if (state.equals("EXPIRED") || state.equals("REVOKED")) {
            operation = UUID.randomUUID().toString(); requestId = ""; deadline = 0;
            try { persist(); } catch (Exception e) { message = "연결 요청을 저장하지 못했습니다"; changed.run(); return; }
        }
        // A transport failure retries the same idempotency key.
        send("connection/requests", changed);
    }

    private void issueInstallation(Runnable changed) {
        busy = true; message = "서버에서 설치 ID와 인증 정보 발급 중";
        JSONObject body = new JSONObject();
        try { body.put("device_alias", android.os.Build.MODEL); }
        catch (JSONException error) { connectionFailed("장치 별칭을 준비하지 못했습니다", changed); return; }
        api.request("POST", "installations", body.toString(), null, null, new ApiClient.ResultCallback() {
            public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                busy = false;
                try {
                    JSONObject value = new JSONObject(response.getBody());
                    InstallationResponse.validate(value);
                    String oldCredential = credential;
                    credential = value.getString("installation_secret"); installationId = value.getString("installation_id");
                    try { persist(); }
                    catch (Exception error) { credential = oldCredential; installationId = ""; throw error; }
                    state = "IDLE"; message = "설치 등록 완료 · 차량 연결 확인 중";
                    refreshPairing(false, changed);
                } catch (Exception error) { api.status.invalid("POST", "installations"); connectionFailed("설치 등록 응답 또는 안전한 저장을 확인하지 못했습니다", changed); }
            }); }
            public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> connectionFailed("설치 등록 연결 필요 · HTTP " + response.getStatusCode(), changed)); }
            public void onFailure(IOException error) { main.post(() -> connectionFailed("설치 등록 연결 필요 · 서버 주소와 네트워크를 확인해 주세요", changed)); }
        });
        changed.run();
    }

    private void refreshPairing(boolean retry, Runnable changed) {
        // A failed read must not permanently stop polling or issue a replacement QR.
        if (!restored || state.equals("ERROR")) { pairingRequest("GET", "installations/me", null, changed); return; }
        if (retry) {
            operation = UUID.randomUUID().toString();
            requestId = ""; payload = ""; deadline = 0; confirmationCode = ""; selectedVehicleName = "";
            pairingRequest("POST", "pairing-requests", null, changed);
            return;
        }
        if (state.equals("WAITING") || state.equals("AWAITING_CONFIRMATION")) {
            pairingRequest("GET", "pairing-requests/" + requestId, null, changed);
        } else if (state.equals("IDLE")) {
            pairingRequest("POST", "pairing-requests", null, changed);
        }
    }

    public void confirmPairing(Runnable changed) {
        if (!usesIssuedInstallation() || !state.equals("AWAITING_CONFIRMATION") || remaining() == 0) return;
        try { pairingRequest("POST", "pairing-requests/" + requestId + "/confirm",
                new JSONObject().put("confirmation_code", confirmationCode), changed); }
        catch (Exception error) { connectionFailed("확인 코드 요청을 만들지 못했습니다", changed); }
    }

    public long remaining() { return Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000); }

    private void pairingRequest(String method, String path, JSONObject body, Runnable changed) {
        Map<String, String> headers = new HashMap<>(installationHeaders());
        if (path.equals("pairing-requests") && method.equals("POST")) {
            if (vehicleAlias.isBlank()) return;
            try { body = new JSONObject().put("vehicle_name", vehicleAlias); if (!vin.isEmpty()) body.put("vin", vin); }
            catch (JSONException invalid) { connectionFailed("차량 별칭을 확인해 주세요", changed); return; }
            headers.put("Idempotency-Key", operation);
            try { persist(); } catch (Exception error) { connectionFailed("QR 재시도 키 저장 실패", changed); return; }
        }
        busy = true;
        api.request(method, path, method.equals("POST") ? body == null ? "" : body.toString() : null,
                null, headers, new ApiClient.ResultCallback() {
            public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                busy = false;
                android.util.Log.d("FUELOG_DEBUG", method + " " + path + " -> " + response.getBody());
                try {
                    JSONObject value = new JSONObject(response.getBody());
                    if (path.equals("installations/me")) {
                        if (!installationId.equals(value.getString("installation_id"))) throw new IllegalArgumentException();
                        restored = true;
                        boolean wasLinked = linked;
                        linked = value.getBoolean("paired");
                        vehicleId = value.isNull("vehicle_id") ? "" : UUID.fromString(value.getString("vehicle_id")).toString();
                        vehicleName = value.optString("vehicle_name", "등록된 차량");
                        if (linked && !value.isNull("vehicle_name") && value.has("vehicle_name") && !vehicleName.isBlank() && !vehicleName.equals(vehicleAlias)) setVehicleAlias(vehicleName);
                        String previousSubject = authenticatedSubject;
                        authenticatedSubject = linked && !value.isNull("authenticated_user_id")
                                ? UUID.fromString(value.getString("authenticated_user_id")).toString() : "";
                        if (!previousSubject.isEmpty() && !previousSubject.equals(authenticatedSubject)) {
                            home = new JSONObject();
                            onPersonalInvalid.run();
                        }
                        if (!previousSubject.equals(authenticatedSubject) || authenticatedSubject.isEmpty()) resetUserEmail();
                        if (!authenticatedSubject.isEmpty()) refreshUserEmail(changed);
                        notifications.refresh(changed);
                        if (linked) { syncVin(changed); state = "ALREADY_LINKED"; message = "차량 연결 확인됨"; }
                        else if (wasLinked) {
                            registrationReset();
                            onPersonalInvalid.run();
                            changed.run();
                            return;
                        } else {
                            if (requestId.isEmpty() && !vehicleAlias.isBlank()) pairingRequest("POST", "pairing-requests", null, changed);
                            else pairingRequest("GET", "pairing-requests/current", null, changed);
                            return;
                        }
                    } else if (path.equals("pairing-requests") && method.equals("POST")) {
                        payload = InstallationResponse.qr(value, System.currentTimeMillis());
                        requestId = value.getString("id");
                        deadline = SystemClock.elapsedRealtime() + Math.min(120000, Instant.parse(value.getString("expires_at")).toEpochMilli() - System.currentTimeMillis());
                        state = "WAITING"; message = ""; persist();
                    } else {
                        String id = value.getString("id"); UUID.fromString(id);
                        if (!path.equals("pairing-requests/current") && !requestId.equals(id)) throw new IllegalArgumentException();
                        if (!id.equals(requestId)) payload = "";
                        requestId = id;
                        long candidate = SystemClock.elapsedRealtime() + Math.max(0, Math.min(120000,
                                Instant.parse(value.getString("expires_at")).toEpochMilli() - System.currentTimeMillis()));
                        deadline = deadline == 0 ? candidate : Math.min(deadline, candidate);
                        String status = value.getString("status");
                        switch (status) {
                            case "pending": state = "WAITING"; break;
                            case "awaiting_vehicle_confirmation":
                                confirmationCode = value.getString("confirmation_code");
                                if (!confirmationCode.matches("[0-9]{6}")) throw new IllegalArgumentException();
                                selectedVehicleName = value.isNull("selected_vehicle_name") ? "선택된 차량" : value.getString("selected_vehicle_name");
                                state = "AWAITING_CONFIRMATION"; break;
                            case "completed": state = "COMPLETED"; linked = true; payload = ""; break;
                            case "expired": state = "EXPIRED"; payload = ""; break;
                            case "revoked": state = "REVOKED"; payload = ""; break;
                            default: throw new IllegalArgumentException();
                        }
                        if (state.equals("WAITING") && payload.isEmpty()) { state = "EXPIRED"; message = "QR 비밀값을 복원할 수 없습니다. 새 코드를 받아 주세요"; }
                        persist();
                        if (path.equals("pairing-requests/current") && !vehicleAlias.isBlank()
                                && (state.equals("EXPIRED") || state.equals("REVOKED"))) {
                            refreshPairing(true, changed);
                            return;
                        }
                    }
                    changed.run();
                } catch (Exception error) { api.status.invalid(method, path); connectionFailed("차량 연결 응답 확인 필요", changed); }
            }); }
            public void onHttpError(ApiClient.ApiResponse response) { main.post(() -> {
                busy = false;
                if (path.equals("pairing-requests/current") && response.getStatusCode() == 404) {
                    state = "IDLE"; pairingRequest("POST", "pairing-requests", null, changed); return;
                }
                if (path.equals("pairing-requests") && response.getStatusCode() == 409) {
                    operation = UUID.randomUUID().toString();
                    state = "EXPIRED"; message = "QR 발급 키가 이미 사용되었거나 연결된 설치입니다. 상태를 다시 확인해 주세요";
                    restored = false; changed.run(); return;
                }
                if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
                    clearPersonal(); onPersonalInvalid.run();
                }
                connectionFailed("차량 연결 필요 · HTTP " + response.getStatusCode(), changed);
            }); }
            public void onFailure(IOException error) { main.post(() -> connectionFailed("차량 연결 필요 · 서버 연결 실패", changed)); }
        });
    }

    // 이전 설치 연결: 기존 인증으로 진행 중인 등록을 마무리한다.
    private void send(String path, Runnable changed) {
        busy = true;
        try {
            String body = new JSONObject().put("operationId", operation).put("requestId", requestId.isEmpty() ? JSONObject.NULL : requestId).toString();
            api.create(path, body, credential, new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) {
                    main.post(() -> {
                        busy = false;
                        try {
                            JSONObject value = new JSONObject(response.getBody());
                            ConnectionResponse.validate(value);
                            String next = value.getString("status");
                            String id = value.getString("requestId");
                            if (path.endsWith("/status") && !requestId.equals(id)) throw new IllegalArgumentException();
                            payload = "";
                            if (next.equals("WAITING")) {
                                long ttl = value.getLong("remainingSeconds");
                                long candidate = SystemClock.elapsedRealtime() + ttl * 1000;
                                deadline = id.equals(requestId) && deadline > 0 ? Math.min(deadline, candidate) : candidate;
                                // QR is an app-scanned JSON envelope, never a URL.
                                payload = new JSONObject().put("type", "fuelog.vehicle-registration").put("version", 1)
                                        .put("requestId", id).put("nonce", value.getString("nonce")).toString();
                            }
                            requestId = id; state = next; message = "";
                            linked = next.equals("COMPLETED") || next.equals("ALREADY_LINKED");
                            persist();
                        } catch (Exception e) { api.status.invalid("POST", path); state = "ERROR"; payload = ""; message = "연결 응답을 확인하지 못했습니다"; }
                        changed.run();
                    });
                }
                public void onHttpError(ApiClient.ApiResponse response) { fail(); }
                public void onFailure(IOException error) { fail(); }
                private void fail() { main.post(() -> { busy = false; state = "ERROR"; payload = "";
                    message = "연결 요청에 실패했습니다. 다시 시도해 주세요"; changed.run(); }); }
            });
        } catch (Exception e) { busy = false; state = "ERROR"; message = "연결 요청을 만들지 못했습니다"; changed.run(); }
    }

    private void resetUserEmail() {
        userEmailGeneration++;
        if (userEmailCall != null) userEmailCall.cancel();
        userEmailCall = null;
        userEmailCheckedAt = 0;
        authenticatedName = authenticatedSubject.isEmpty() ? "" : "이메일 확인 중";
    }

    void refreshUserEmail(Runnable changed) {
        if (!linked || authenticatedSubject.isEmpty() || userEmailCall != null
                || userEmailCheckedAt > 0 && SystemClock.elapsedRealtime() - userEmailCheckedAt < 60000) return;
        final String subject = authenticatedSubject;
        final int version = ++userEmailGeneration;
        userEmailCheckedAt = SystemClock.elapsedRealtime();
        userEmailCall = api.request("GET", "installation/users/" + subject, null, credential, null, new ApiClient.ResultCallback() {
            public void onSuccess(ApiClient.ApiResponse response) { main.post(() -> {
                if (version != userEmailGeneration || !linked || !subject.equals(authenticatedSubject)) return;
                userEmailCall = null;
                try {
                    JSONObject value = new JSONObject(response.getBody());
                    if (!subject.equals(value.getString("user_id"))) throw new IllegalArgumentException();
                    String email = value.isNull("email") ? "" : value.getString("email").trim();
                    if (email.length() > 320 || email.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException();
                    authenticatedName = email.isEmpty() ? "이메일 미등록" : email;
                } catch (Exception invalid) { authenticatedName = "이메일 확인 불가"; }
                changed.run();
            }); }
            public void onHttpError(ApiClient.ApiResponse response) { failed(); }
            public void onFailure(IOException error) { failed(); }
            private void failed() { main.post(() -> {
                if (version != userEmailGeneration || !subject.equals(authenticatedSubject)) return;
                userEmailCall = null;
                authenticatedName = "이메일 확인 불가";
                changed.run();
            }); }
        });
    }

    // 3. 사용자 연결 해제: 개인 화면 데이터와 인증된 사용자 표시를 지운다.
    public void clearPersonal() {
        notifications.clear();
        authenticatedSubject = "";
        resetUserEmail();
        home = new JSONObject();
    }

    // 4. 연결 후 API 사용: 설치 인증과 요청별 사용자 증명을 전달한다.
    public Call request(String method, String path, JSONObject body, String candidate,
                                ApiClient.ResultCallback callback) {
        return requestWithSession(method, path, body, candidate, "", callback);
    }

    public Call requestWithSession(String method, String path, JSONObject body, String candidate,
                                           String sessionToken, ApiClient.ResultCallback callback) {
        if (api == null || !linked) throw new IllegalStateException("Vehicle connection required");
        Map<String, String> headers = new HashMap<>();
        if (candidate != null && !candidate.isEmpty()) headers.put("X-Trip-User-Candidate", candidate);
        String token = sessionToken;
        if (!token.isEmpty()) headers.put("X-Trip-User-Session", "Bearer " + token);
        return api.request(method, path, body == null ? null : body.toString(), credential, headers,
                new ApiClient.ResultCallback() {
                    public void onSuccess(ApiClient.ApiResponse response) { callback.onSuccess(response); }
                    public void onFailure(IOException error) { callback.onFailure(error); }
                    public void onHttpError(ApiClient.ApiResponse response) {
                        if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
                            main.post(() -> { clearPersonal(); onPersonalInvalid.run(); callback.onHttpError(response); });
                        } else callback.onHttpError(response);
                    }
                });
    }

    public Call requestUser(String path, String body, ApiClient.ResultCallback callback) {
        if (api == null || !linked) throw new IllegalStateException("Vehicle connection required");
        try { return request("POST", path, new JSONObject(body), "", callback); }
        catch (JSONException error) { throw new IllegalArgumentException("Invalid JSON", error); }
    }

    public Call requestMaintenance(String method, String path, JSONObject body, String revision, ApiClient.ResultCallback callback) {
        return requestMaintenance(method, path, body, revision, "", callback);
    }

    public Call requestMaintenance(String method, String path, JSONObject body, String revision, String idempotencyKey, ApiClient.ResultCallback callback) {
        if (api == null || !linked || authenticatedSubject.isEmpty() || vehicleId.isEmpty())
            throw new IllegalStateException("Verified vehicle owner required");
        Map<String, String> headers = new HashMap<>();
        if (revision != null && !revision.isEmpty()) headers.put("If-Match", revision);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) headers.put("Idempotency-Key", idempotencyKey);
        return api.request(method, path, body == null ? null : body.toString(), credential, headers, callback);
    }

    public Call readMaintenance(ApiClient.ResultCallback callback) {
        if (api == null || vehicleId.isEmpty()) throw new IllegalStateException("Vehicle registration required");
        return api.request("GET", "maintenance/summary", null, credential, null, callback);
    }

    public Call manage(String path, String body, ApiClient.ResultCallback callback) {
        if (api == null) throw new IllegalStateException("Server unavailable");
        return api.create(path, body, credential, callback);
    }

    // 5. 차량 등록 초기화: 개인 세션과 연결 화면 상태를 정리한다.
    public void registrationReset() throws Exception {
        operation = UUID.randomUUID().toString();
        restored = false; clearPersonal(); linked = false; state = "IDLE"; requestId = ""; payload = ""; deadline = 0;
        persist();
    }

    // 공통 처리: 설치 인증 저장과 연결 오류 안내.
    private void persist() throws Exception {
        identity.write(new JSONObject().put("credential", credential).put("installationId", installationId)
                .put("vehicleAlias", vehicleAlias).put("operation", operation).put("requestId", requestId).put("payload", payload).toString());
    }

    private Map<String, String> installationHeaders() {
        return Map.of("X-Installation-Id", installationId, "X-Installation-Secret", credential);
    }

    private void connectionFailed(String text, Runnable changed) {
        // Hide the QR through ERROR, but retain its proof so a failed poll can resume the same request.
        busy = false; state = "ERROR"; message = text; changed.run();
    }
}
