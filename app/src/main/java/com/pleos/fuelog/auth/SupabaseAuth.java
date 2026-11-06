package com.pleos.fuelog.auth;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Java Auth REST client. One executor serializes PKCE exchange, refresh rotation and logout.
 * Callbacks run on that executor. This client never follows HTTP redirects or retries token writes.
 */
public final class SupabaseAuth {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long PENDING_TTL = TimeUnit.HOURS.toMillis(1);
    private final HttpUrl base;
    private final String key;
    private final AuthStore store;
    private final OkHttpClient http;
    private final ExecutorService executor;

    SupabaseAuth(HttpUrl base, String key, AuthStore store, OkHttpClient http, ExecutorService executor) {
        this.base = base;
        this.key = key;
        this.store = store;
        this.http = http;
        this.executor = executor;
    }

    static SupabaseAuth create(HttpUrl base, String key, AuthStore store) {
        return new SupabaseAuth(base, key, store, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build(),
                Executors.newSingleThreadExecutor());
    }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(IOException error);
    }

    /** Sends a sign-in link; new emails also create an account (Supabase's default behavior). */
    public void sendMagicLink(String email, Callback<Void> callback) {
        submit(() -> {
            JSONObject state = readState();
            String verifier = verifier();
            state.put("verifier", verifier).put("pending_at", System.currentTimeMillis());
            save(state); // Persist before sending; a killed app can still finish the flow.
            JSONObject body = new JSONObject().put("email", email.trim()).put("create_user", true)
                    .put("code_challenge", challenge(verifier)).put("code_challenge_method", "s256");
            request(base.newBuilder().addPathSegment("otp")
                    .addQueryParameter("redirect_to", AuthRedirect.URL).build(), body, null);
            return null;
        }, callback);
    }

    /** Callback must originate from a flow started on this installation, including after process death. */
    public void handleRedirect(String url, Callback<Session> callback) {
        submit(() -> {
            String code = AuthRedirect.authorizationCode(url);
            JSONObject state = readState();
            String verifier = state.optString("verifier");
            long age = System.currentTimeMillis() - state.optLong("pending_at");
            if (verifier.isEmpty() || age < 0 || age > PENDING_TTL) {
                throw new AuthException("이 앱에서 로그인을 다시 시작하세요. 최근 요청한 링크를 같은 기기에서 여세요.");
            }
            JSONObject result = request(tokenUrl("pkce"), new JSONObject()
                    .put("auth_code", code).put("code_verifier", verifier), null);
            Session session = Session.fromResponse(result);
            state.put("session", session.json()).remove("verifier");
            state.remove("pending_at");
            save(state);
            return session;
        }, callback);
    }

    /** Restores the session and refreshes shortly before expiry. No session returns null. */
    public void getSession(Callback<Session> callback) { submit(this::validSession, callback); }

    /** For an OkHttp interceptor only; never call on the main thread or inside an Auth callback. */
    public String accessTokenBlocking() throws IOException {
        try {
            return executor.submit(() -> {
                Session session = validSession();
                if (session == null) throw new AuthException("로그인이 필요합니다.");
                return session.accessToken;
            }).get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("인증 요청이 취소되었습니다.");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw new AuthException("인증 정보를 확인할 수 없습니다.");
        }
    }

    /** Always clears local state first. A failed server revocation is reported to the caller. */
    public void clearLocal(Callback<Void> callback) {
        submit(() -> { save(new JSONObject()); return null; }, callback);
    }

    public void signOut(Callback<Void> callback) {
        submit(() -> {
            JSONObject state = readState();
            JSONObject session = state.optJSONObject("session");
            save(new JSONObject());
            if (session != null) {
                try {
                    request(base.newBuilder().addPathSegment("logout")
                            .addQueryParameter("scope", "local").build(), new JSONObject(),
                            session.optString("access_token"));
                } catch (IOException | JSONException error) {
                    throw new AuthException("이 기기에서 로그아웃했습니다. 서버 세션 종료는 확인하지 못했습니다.");
                }
            }
            return null;
        }, callback);
    }

    private Session validSession() throws IOException, JSONException {
        JSONObject state = readState();
        JSONObject stored = state.optJSONObject("session");
        if (stored == null) return null;
        Session session = Session.fromStored(stored);
        if (session.expiresAt > System.currentTimeMillis() / 1000 + 60) return session;
        try {
            JSONObject result = request(tokenUrl("refresh_token"), new JSONObject()
                    .put("refresh_token", session.refreshToken), null);
            session = Session.fromResponse(result);
            state.put("session", session.json());
            save(state); // Persist rotated refresh token before any caller can use it.
            return session;
        } catch (AuthException error) {
            if (error.getStatus() == 400 || error.getStatus() == 401 || error.getStatus() == 403) {
                state.remove("session");
                save(state);
            }
            throw error; // Offline/5xx retain the session for a later explicit attempt.
        }
    }

    private HttpUrl tokenUrl(String grant) {
        return base.newBuilder().addPathSegment("token").addQueryParameter("grant_type", grant).build();
    }

    private JSONObject request(HttpUrl url, JSONObject body, String token) throws IOException, JSONException {
        Request.Builder builder = new Request.Builder().url(url).header("apikey", key)
                .header("Accept", "application/json").post(RequestBody.create(body.toString(), JSON));
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                String message = response.code() == 429 ? "요청이 많습니다. 잠시 후 다시 시도하세요."
                        : "인증 요청에 실패했습니다. 다시 시도하거나 인증 설정을 확인하세요. (HTTP "
                        + response.code() + ")";
                throw new AuthException(message, response.code());
            }
            String text = response.body() == null ? "" : response.body().string();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        }
    }

    private JSONObject readState() throws IOException, JSONException {
        JSONObject state = new JSONObject(store.read());
        // A different Supabase project/key must never reuse another project's verifier/session.
        if (!base.toString().equals(state.optString("project"))
                || !challenge(key).equals(state.optString("key_id"))) return new JSONObject();
        return state;
    }

    private void save(JSONObject state) throws IOException, JSONException {
        state.put("project", base.toString()).put("key_id", challenge(key));
        store.write(state.toString());
    }

    private interface Job<T> { T run() throws IOException, JSONException; }

    private <T> void submit(Job<T> job, Callback<T> callback) {
        executor.execute(() -> {
            T result;
            try { result = job.run(); }
            catch (JSONException error) {
                callback.onError(new AuthException("인증 응답 또는 저장된 정보를 읽을 수 없습니다."));
                return;
            } catch (IOException error) {
                callback.onError(error instanceof AuthException ? error
                        : new AuthException("인증 서버 연결 또는 저장에 실패했습니다. 연결을 확인한 뒤 다시 시도하세요."));
                return;
            }
            callback.onSuccess(result);
        });
    }

    static String verifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Session {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresAt;
        private final String email;

        private Session(String accessToken, String refreshToken, long expiresAt, String email) throws AuthException {
            if (accessToken.isEmpty() || refreshToken.isEmpty() || expiresAt <= 0) {
                throw new AuthException("유효한 인증 세션을 받지 못했습니다.");
            }
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.email = email;
        }

        public String getEmail() { return email; }

        private static Session fromResponse(JSONObject json) throws JSONException, AuthException {
            if (!"bearer".equalsIgnoreCase(json.optString("token_type")) || json.optLong("expires_in") <= 0) {
                throw new AuthException("유효한 인증 세션을 받지 못했습니다.");
            }
            JSONObject user = json.optJSONObject("user");
            return new Session(json.getString("access_token"), json.getString("refresh_token"),
                    System.currentTimeMillis() / 1000 + json.getLong("expires_in"),
                    user == null ? "" : user.optString("email"));
        }

        private static Session fromStored(JSONObject json) throws JSONException, AuthException {
            return new Session(json.getString("access_token"), json.getString("refresh_token"),
                    json.getLong("expires_at"), json.optString("email"));
        }

        private JSONObject json() throws JSONException {
            return new JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
                    .put("expires_at", expiresAt).put("email", email);
        }
    }
}
