package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InstallationFlowAndroidTest {
    @Test public void completedRequestIsReadEvenAfterLocalQrDeadline() throws Exception {
        try (PendingConnection fixture = new PendingConnection()) {
            java.lang.reflect.Field deadline = ConnectionSession.class.getDeclaredField("deadline");
            deadline.setAccessible(true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { deadline.setLong(fixture.session, SystemClock.elapsedRealtime() - 1); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            fixture.server.enqueue(json(200, fixture.status("completed")
                    .put("expires_at", Instant.now().minusSeconds(10).toString())));
            fixture.refresh();
            assertTrue(fixture.session.linked);
            assertEquals("COMPLETED", fixture.session.state);
            assertEquals("", fixture.session.payload);
            assertEquals("/pairing-requests/" + fixture.requestId,
                    fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void failedPollRecoversExistingConnectionWithoutIssuingAnotherQr() throws Exception {
        try (PendingConnection fixture = new PendingConnection()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(503));
            fixture.refresh();
            assertFalse(fixture.session.linked);
            assertEquals("ERROR", fixture.session.state);
            fixture.server.takeRequest(5, TimeUnit.SECONDS);

            fixture.server.enqueue(json(200, new JSONObject().put("installation_id", fixture.installationId)
                    .put("paired", true)));
            fixture.refresh();
            assertTrue(fixture.session.linked);
            assertEquals("ALREADY_LINKED", fixture.session.state);
            assertEquals("/installations/me", fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals(4, fixture.server.getRequestCount());
        }
    }

    @Test public void failedPollCanResumePendingRequestThenObserveCompletion() throws Exception {
        try (PendingConnection fixture = new PendingConnection()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(503));
            fixture.refresh();
            fixture.server.takeRequest(5, TimeUnit.SECONDS);
            fixture.server.enqueue(json(200, new JSONObject().put("installation_id", fixture.installationId)
                    .put("paired", false)));
            fixture.server.enqueue(json(200, fixture.status("pending")));
            fixture.refresh();
            assertEquals("WAITING", fixture.session.state);
            assertEquals("saved-test-qr", fixture.session.payload);
            assertEquals("/installations/me", fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals("/pairing-requests/current", fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath());

            fixture.server.enqueue(json(200, fixture.status("completed")));
            fixture.refresh();
            assertTrue(fixture.session.linked);
            assertEquals("COMPLETED", fixture.session.state);
            assertEquals("/pairing-requests/" + fixture.requestId,
                    fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals(6, fixture.server.getRequestCount());
        }
    }

    @Test public void serverIssuesIdentityBeforeQrAndDeviceConfirmsThenRestores() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "installation-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) { @Override public File getNoBackupFilesDir() { return directory; } };
        String installationId = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String expires = Instant.now().plusSeconds(120).toString();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(json(201, new JSONObject().put("installation_id", installationId).put("installation_secret", secret)));
            JSONObject me = new JSONObject().put("installation_id", installationId).put("paired", false);
            server.enqueue(json(200, me)); server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(json(201, new JSONObject().put("id", requestId).put("expires_at", expires)
                    .put("qr", new JSONObject().put("v", 1).put("type", "fuelog.vehicle_pairing").put("request_id", requestId).put("proof", "temporary-proof"))));
            ApiClient api = new ApiClient(server.url("/").toString());
            ConnectionSession session = new ConnectionSession(isolated, api);
            CountDownLatch waiting = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session.refresh(true, () -> { if (session.state.equals("WAITING")) waiting.countDown(); }));
            assertTrue(waiting.await(10, TimeUnit.SECONDS));
            assertEquals(installationId, session.installationId);
            RecordedRequest installation = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/installations", installation.getPath()); assertNull(installation.getHeader("Authorization"));
            JSONObject installationBody = new JSONObject(installation.getBody().readUtf8());
            assertEquals(android.os.Build.MODEL, installationBody.getString("device_alias"));
            assertEquals(1, installationBody.length());
            assertEquals("/installations/me", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals("/pairing-requests/current", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            RecordedRequest qr = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/pairing-requests", qr.getPath());
            assertEquals(installationId, qr.getHeader("X-Installation-Id"));
            assertEquals(secret, qr.getHeader("X-Installation-Secret")); assertNull(qr.getHeader("Authorization"));
            assertNotNull(qr.getHeader("Idempotency-Key")); assertFalse(session.payload.contains(secret));
            JSONObject pending = new JSONObject().put("id", requestId).put("status", "awaiting_vehicle_confirmation")
                    .put("expires_at", expires).put("confirmation_code", "123456").put("selected_vehicle_name", "선택 차량");
            server.enqueue(json(200, pending));
            CountDownLatch selected = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session.refresh(true, selected::countDown));
            assertTrue(selected.await(10, TimeUnit.SECONDS)); assertFalse(session.linked);
            assertEquals("AWAITING_CONFIRMATION", session.state); server.takeRequest(5, TimeUnit.SECONDS);
            server.enqueue(json(200, new JSONObject(pending.toString()).put("status", "completed")));
            CountDownLatch confirmed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session.confirmPairing(confirmed::countDown));
            assertTrue(confirmed.await(10, TimeUnit.SECONDS)); assertTrue(session.linked);
            RecordedRequest confirm = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/pairing-requests/" + requestId + "/confirm", confirm.getPath());
            assertEquals("123456", new JSONObject(confirm.getBody().readUtf8()).getString("confirmation_code"));
            String pairedUser = UUID.randomUUID().toString();
            server.enqueue(json(200, new JSONObject(me.toString()).put("paired", true)
                    .put("authenticated_user_id", pairedUser).put("authenticated_user_name", "Paired driver")));
            ConnectionSession restored = new ConnectionSession(isolated, api);
            CountDownLatch restoredLatch = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> restored.refresh(true, restoredLatch::countDown));
            assertTrue(restoredLatch.await(10, TimeUnit.SECONDS)); assertTrue(restored.linked);
            assertEquals(pairedUser, restored.authenticatedSubject);
            assertEquals("Paired driver", restored.authenticatedName);
            assertEquals("/installations/me", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals(7, server.getRequestCount());
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
    private static MockResponse json(int code, JSONObject body) { return new MockResponse().setResponseCode(code).setBody(body.toString()); }

    private static final class PendingConnection implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final String installationId = UUID.randomUUID().toString();
        final String requestId = UUID.randomUUID().toString();
        final File directory;
        final ConnectionSession session;

        PendingConnection() throws Exception {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            directory = new File(target.getCacheDir(), "pending-test-" + UUID.randomUUID());
            assertTrue(directory.mkdirs());
            Context isolated = new ContextWrapper(target) {
                @Override public File getNoBackupFilesDir() { return directory; }
            };
            new EncryptedJsonStore(new File(directory, "installation.bin")).write(new JSONObject()
                    .put("installationId", installationId).put("credential", "test-installation-secret")
                    .put("requestId", requestId).put("payload", "saved-test-qr").toString());
            server.start();
            server.enqueue(json(200, new JSONObject().put("installation_id", installationId).put("paired", false)));
            server.enqueue(json(200, status("pending")));
            session = new ConnectionSession(isolated, new ApiClient(server.url("/").toString()));
            refresh();
            assertEquals("WAITING", session.state);
            server.takeRequest(5, TimeUnit.SECONDS);
            server.takeRequest(5, TimeUnit.SECONDS);
        }

        JSONObject status(String status) throws Exception {
            return new JSONObject().put("id", requestId).put("status", status)
                    .put("expires_at", Instant.now().plusSeconds(120).toString());
        }

        void refresh() throws Exception {
            CountDownLatch changed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> session.refresh(true, changed::countDown));
            assertTrue("Connection status callback timed out", changed.await(5, TimeUnit.SECONDS));
        }

        @Override public void close() throws Exception {
            server.close();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
