package com.pleos.fuelog.auth;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class SupabaseAuthTest {
    private MockWebServer server;
    private MemoryStore store;
    private ExecutorService executor;
    private SupabaseAuth auth;

    @Before public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new MemoryStore();
        executor = Executors.newSingleThreadExecutor();
        auth = client();
    }

    private SupabaseAuth client() {
        return new SupabaseAuth(server.url("/auth/v1/"), "public-key", store,
                new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(), executor);
    }

    @After public void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        server.shutdown();
    }

    @Test public void localDisconnectClearsTokensAndPendingLoginWithoutNetwork() throws Exception {
        store.value = new JSONObject().put("session", new JSONObject().put("access_token", "old")
                .put("refresh_token", "old-refresh")).put("verifier", "pending-login").toString();
        Result<Void> result = new Result<>();
        auth.clearLocal(result);
        result.value();
        assertFalse(new JSONObject(store.value).has("session"));
        assertFalse(new JSONObject(store.value).has("verifier"));
        assertEquals(0, server.getRequestCount());
        auth = client();
        assertFalse(new JSONObject(store.value).has("session"));
        assertFalse(new JSONObject(store.value).has("verifier"));
    }

    @Test public void pkceChallengeMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                SupabaseAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String verifier = SupabaseAuth.verifier();
        assertTrue(verifier.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(verifier, SupabaseAuth.verifier());
    }

    @Test public void emailLinkSurvivesClientRecreationAndOnlyExchangesOnce() throws Exception {
        sendLink();
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/auth/v1/otp", sent.getRequestUrl().encodedPath());
        assertEquals(AuthRedirect.URL, sent.getRequestUrl().queryParameter("redirect_to"));
        assertEquals("public-key", sent.getHeader("apikey"));
        assertNull(sent.getHeader("Authorization"));
        JSONObject requestBody = new JSONObject(sent.getBody().readUtf8());
        String verifier = new JSONObject(store.value).getString("verifier");
        assertEquals("test@example.com", requestBody.getString("email"));
        assertEquals("s256", requestBody.getString("code_challenge_method"));
        assertEquals(SupabaseAuth.challenge(verifier), requestBody.getString("code_challenge"));
        assertFalse(requestBody.has("code_verifier"));

        auth = client(); // Only persisted data survives a new client/process.
        server.enqueue(session("access-1", "refresh-1", 3600));
        Result<SupabaseAuth.Session> result = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=one-time", result);
        assertEquals("test@example.com", result.value().getEmail());
        RecordedRequest exchange = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("pkce", exchange.getRequestUrl().queryParameter("grant_type"));
        JSONObject exchangeBody = new JSONObject(exchange.getBody().readUtf8());
        assertEquals("one-time", exchangeBody.getString("auth_code"));
        assertEquals(verifier, exchangeBody.getString("code_verifier"));
        assertFalse(new JSONObject(store.value).has("verifier"));
        assertEquals("access-1", auth.accessTokenBlocking());
        Result<SupabaseAuth.Session> replay = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=one-time", replay);
        assertNotNull(replay.error());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void rejectsUntrustedUrlsErrorsAndUnsolicitedCodesWithoutNetwork() throws Exception {
        for (String url : new String[]{"https://login-callback/?code=x",
                "com.pleos.fuelog://evil/?code=x", "com.pleos.fuelog://login-callback/other?code=x",
                "com.pleos.fuelog://user@login-callback/?code=x",
                "com.pleos.fuelog://login-callback:80/?code=x", AuthRedirect.URL,
                AuthRedirect.URL + "?code=x&code=y", AuthRedirect.URL + "?code=",
                AuthRedirect.URL + "#access_token=injected&refresh_token=injected",
                AuthRedirect.URL + "?error=access_denied&error_description=secret",
                AuthRedirect.URL + "?code=unsolicited"}) {
            Result<SupabaseAuth.Session> result = new Result<>();
            auth.handleRedirect(url, result);
            assertNotNull(url, result.error());
            assertFalse(result.error().getMessage().contains("secret"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void concurrentCallersRefreshOnlyOnceAndPersistRotatedToken() throws Exception {
        login(1);
        server.enqueue(session("access-2", "refresh-2", 3600));
        List<Result<SupabaseAuth.Session>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Result<SupabaseAuth.Session> result = new Result<>();
            results.add(result);
            auth.getSession(result);
        }
        for (Result<SupabaseAuth.Session> result : results) assertNotNull(result.value());
        assertEquals("access-2", auth.accessTokenBlocking());
        assertEquals("refresh-2", new JSONObject(store.value).getJSONObject("session").getString("refresh_token"));
        server.takeRequest(); // otp
        server.takeRequest(); // exchange
        RecordedRequest refresh = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("refresh_token", refresh.getRequestUrl().queryParameter("grant_type"));
        assertEquals("refresh-1", new JSONObject(refresh.getBody().readUtf8()).getString("refresh_token"));
        assertEquals(3, server.getRequestCount());
    }

    @Test public void rejectedRefreshClearsSessionButServerFailureRetainsIt() throws Exception {
        login(1);
        server.enqueue(new MockResponse().setResponseCode(503).setBody("sensitive server output"));
        Result<SupabaseAuth.Session> offline = new Result<>();
        auth.getSession(offline);
        assertNotNull(offline.error());
        assertFalse(offline.error().getMessage().contains("sensitive"));
        assertTrue(new JSONObject(store.value).has("session"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error_code\":\"refresh_token_not_found\"}"));
        Result<SupabaseAuth.Session> rejected = new Result<>();
        auth.getSession(rejected);
        assertNotNull(rejected.error());
        assertFalse(new JSONObject(store.value).has("session"));
        assertThrows(IOException.class, () -> auth.accessTokenBlocking());
    }

    @Test public void signOutClearsLocalStateEvenWhenRevocationFails() throws Exception {
        login(3600);
        server.enqueue(new MockResponse().setResponseCode(503));
        Result<Void> logout = new Result<>();
        auth.signOut(logout);
        assertNotNull(logout.error());
        assertFalse(new JSONObject(store.value).has("session"));
        Result<SupabaseAuth.Session> restored = new Result<>();
        auth.getSession(restored);
        assertNull(restored.value());
        server.takeRequest();
        server.takeRequest();
        RecordedRequest revoke = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("local", revoke.getRequestUrl().queryParameter("scope"));
        assertEquals("Bearer access-1", revoke.getHeader("Authorization"));
    }

    @Test public void stalePendingFlowAndChangedProjectCannotExchangeCodes() throws Exception {
        sendLink();
        JSONObject state = new JSONObject(store.value);
        state.put("pending_at", System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2));
        store.value = state.toString();
        Result<SupabaseAuth.Session> expired = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=old", expired);
        assertNotNull(expired.error());
        state.put("pending_at", System.currentTimeMillis()).put("project", "https://different.supabase.co/auth/v1/");
        store.value = state.toString();
        Result<SupabaseAuth.Session> changed = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=other-project", changed);
        assertNotNull(changed.error());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void redirectsAndInvalidTokenResponsesNeverEstablishASession() throws Exception {
        sendLink();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/untrusted")));
        Result<SupabaseAuth.Session> redirect = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=x", redirect);
        assertNotNull(redirect.error());
        assertEquals(2, server.getRequestCount());
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"incomplete\"}"));
        Result<SupabaseAuth.Session> invalid = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=y", invalid);
        assertNotNull(invalid.error());
        assertFalse(new JSONObject(store.value).has("session"));
    }

    @Test public void backendUsesRefreshedTokenAndDoesNotSendUnauthenticatedRequests() throws Exception {
        try (MockWebServer backend = new MockWebServer()) {
            backend.start();
            OkHttpClient api = new OkHttpClient.Builder().addInterceptor(new AuthInterceptor(auth)).build();
            Request request = new Request.Builder().url(backend.url("/records"))
                    .header("Authorization", "Bearer stale-explicit-token").build();
            assertThrows(IOException.class, () -> api.newCall(request).execute());
            assertEquals(0, backend.getRequestCount());
            login(1);
            server.enqueue(session("fresh-access", "fresh-refresh", 3600));
            backend.enqueue(new MockResponse().setBody("[]"));
            try (Response response = api.newCall(request).execute()) { assertEquals(200, response.code()); }
            assertEquals("Bearer fresh-access", backend.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization"));
        }
    }

    @Test public void configurationRejectsPrivateKeysAndNonProjectUrls() throws Exception {
        AuthModule.validatePublicKey("sb_publishable_example");
        String anon = "header." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"role\":\"anon\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".signature";
        AuthModule.validatePublicKey(anon);
        assertThrows(IllegalStateException.class, () -> AuthModule.validatePublicKey("sb_secret_example"));
        assertThrows(IllegalStateException.class, () -> AuthModule.validatePublicKey(
                "header.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.signature"));
        assertThrows(IllegalStateException.class, () -> AuthModule.validatePublicKey(""));
        assertEquals("https://test.supabase.co/", AuthModule.projectUrl("https://test.supabase.co").toString());
        for (String url : new String[]{"", "http://test.supabase.co", "https://test.supabase.co/auth/v1/",
                "https://user:pass@test.supabase.co", "https://test.supabase.co?key=hidden"}) {
            assertThrows(IllegalStateException.class, () -> AuthModule.projectUrl(url));
        }
    }

    private void sendLink() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        Result<Void> result = new Result<>();
        auth.sendMagicLink("test@example.com", result);
        result.value();
    }

    private void login(int expiresIn) throws Exception {
        sendLink();
        server.enqueue(session("access-1", "refresh-1", expiresIn));
        Result<SupabaseAuth.Session> result = new Result<>();
        auth.handleRedirect(AuthRedirect.URL + "?code=x", result);
        assertNotNull(result.value());
    }

    private MockResponse session(String access, String refresh, int expiresIn) throws Exception {
        return new MockResponse().setBody(new JSONObject().put("access_token", access)
                .put("refresh_token", refresh).put("token_type", "bearer").put("expires_in", expiresIn)
                .put("user", new JSONObject().put("email", "test@example.com")).toString());
    }

    private static final class MemoryStore implements AuthStore {
        volatile String value = "{}";
        public String read() { return value; }
        public void write(String value) { this.value = value; }
    }

    private static final class Result<T> implements SupabaseAuth.Callback<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private T value;
        private IOException error;
        public void onSuccess(T value) { this.value = value; done.countDown(); }
        public void onError(IOException error) { this.error = error; done.countDown(); }
        T value() throws Exception {
            assertTrue("Auth callback timed out", done.await(5, TimeUnit.SECONDS));
            if (error != null) throw error;
            return value;
        }
        IOException error() throws Exception {
            assertTrue("Auth callback timed out", done.await(5, TimeUnit.SECONDS));
            return error;
        }
    }
}
