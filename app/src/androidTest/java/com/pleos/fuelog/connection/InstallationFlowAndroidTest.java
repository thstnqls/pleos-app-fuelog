package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.api.ApiClient;
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
            assertEquals(0, installation.getBodySize());
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
            server.enqueue(json(200, new JSONObject(me.toString()).put("paired", true)));
            ConnectionSession restored = new ConnectionSession(isolated, api);
            CountDownLatch restoredLatch = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> restored.refresh(true, restoredLatch::countDown));
            assertTrue(restoredLatch.await(10, TimeUnit.SECONDS)); assertTrue(restored.linked);
            assertEquals("/installations/me", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals(7, server.getRequestCount());
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
    private MockResponse json(int code, JSONObject body) { return new MockResponse().setResponseCode(code).setBody(body.toString()); }
}
