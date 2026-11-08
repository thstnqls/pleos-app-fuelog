package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.home.HomeRecorder;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionSettingsAndroidTest {
    @Test public void missingApiExplainsFailureAndRetryKeepsTheOriginalOperation() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "disconnect-test-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
        };
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ConnectionSession connection = new ConnectionSession(isolated, new ApiClient(server.url("/").toString()));
            connection.installationId = UUID.randomUUID().toString();
            connection.vehicleId = UUID.randomUUID().toString();
            String installation = connection.installationId, vehicle = connection.vehicleId;
            ConnectionSettings[] settings = new ConnectionSettings[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    var constructor = HomeRecorder.class.getDeclaredConstructor(Context.class);
                    constructor.setAccessible(true);
                    HomeRecorder recorder = constructor.newInstance(isolated);
                    settings[0] = new ConnectionSettings(isolated, connection, recorder);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            server.enqueue(new MockResponse().setResponseCode(404));
            CountDownLatch failed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> settings[0].begin("USER", () -> {
                if (!settings[0].busy) failed.countDown();
            }));
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals("USER", settings[0].pending());
            assertTrue(settings[0].message.contains("HTTP 404"));
            assertTrue(settings[0].message.contains("배포"));
            assertFalse(settings[0].busy);
            var first = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/connection/disconnect", first.getPath());
            String operation = new JSONObject(first.getBody().readUtf8()).getString("operationId");

            server.enqueue(new MockResponse().setBody(new JSONObject().put("operationId", operation)
                    .put("status", "COMPLETED").toString()));
            CountDownLatch completed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> settings[0].retry(() -> {
                if (!settings[0].busy) completed.countDown();
            }));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            var retry = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("/connection/disconnect", retry.getPath());
            assertEquals(operation, new JSONObject(retry.getBody().readUtf8()).getString("operationId"));
            assertEquals("", settings[0].pending());
            assertEquals("서버 연결 해제 완료", settings[0].message);
            assertEquals(installation, connection.installationId);
            assertEquals(vehicle, connection.vehicleId);
            assertFalse(isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).getBoolean("disconnectPending", true));
        } finally {
            target.deleteSharedPreferences(prefix + "home_recording");
            target.deleteSharedPreferences(prefix + "connection_settings");
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
