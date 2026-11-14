package com.pleos.fuelog.connection;

import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.api.ApiClient;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class UserNotificationSettingsAndroidTest {
    @Test public void serverValuesAreRestoredAndNeverAppliedToAnotherUser() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ConnectionSession session = new ConnectionSession(instrumentation.getTargetContext(), new ApiClient(server.url("/").toString()));
            session.linked = true; session.authenticatedSubject = UUID.randomUUID().toString();
            String user = session.authenticatedSubject;
            server.enqueue(new MockResponse().setBody("{\"user_id\":\"" + user + "\",\"chargeCompleteEnabled\":false}"));
            CountDownLatch read = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> session.notifications.refresh(() -> { if (!session.notifications.busy) read.countDown(); }));
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertTrue(session.notifications.loaded()); assertFalse(session.notifications.enabled());
            assertEquals("/installation/users/" + user + "/notification-settings", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            server.enqueue(new MockResponse().setBody("{\"user_id\":\"" + user + "\",\"chargeCompleteEnabled\":true}"));
            CountDownLatch saved = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> session.notifications.save(true, () -> { if (!session.notifications.busy) saved.countDown(); }));
            assertTrue(saved.await(5, TimeUnit.SECONDS)); assertTrue(session.notifications.enabled());
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("PUT", request.getMethod());
            assertTrue(new org.json.JSONObject(request.getBody().readUtf8()).getBoolean("chargeCompleteEnabled"));
            session.authenticatedSubject = UUID.randomUUID().toString();
            assertFalse(session.notifications.enabled()); assertFalse(session.notifications.loaded());
            String second = session.authenticatedSubject;
            server.enqueue(new MockResponse().setBody("{\"user_id\":\"" + user + "\",\"chargeCompleteEnabled\":true}"));
            CountDownLatch mismatched = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> session.notifications.refresh(() -> { if (!session.notifications.busy) mismatched.countDown(); }));
            assertTrue(mismatched.await(5, TimeUnit.SECONDS));
            assertFalse(session.notifications.enabled()); assertFalse(session.notifications.loaded());
            assertEquals("/installation/users/" + second + "/notification-settings", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            instrumentation.runOnMainSync(session.notifications::clear);
        }
    }
}
