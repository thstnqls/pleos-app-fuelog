package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import com.pleos.fuelog.connection.ConnectionSession;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingSyncAndroidTest {
    @Test public void offlineRestartKeepsKeysSequenceAndFinishBody() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "outbox-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        File file = new File(directory, "recording-outbox.bin");
        try {
            ConnectionSession connection = ConnectionSession.get(isolated);
            RecordingSync first = new RecordingSync(isolated, connection);
            first.start("local-test", 1794013200000L, "");
            first.sample("local-test", RecordingPayloads.sample(48200.625f, 48200.5, 1794013203000L), "candidate-test");
            JSONObject before = new JSONObject(new EncryptedJsonStore(file).read());
            String batch = before.getJSONArray("jobs").getJSONObject(1).getJSONObject("body").getString("batchId");
            assertFalse(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("candidate-test"));
            RecordingSync restored = new RecordingSync(isolated, connection);
            restored.start("local-test", 1794013200000L, "");
            assertEquals(2, restored.pendingCount());
            restored.sample("local-test", RecordingPayloads.sample(null, 48200.625, 1794013206000L), "candidate-test");
            JSONObject snapshot = new JSONObject().put("intervalId", "local-test").put("candidateId", "candidate-test")
                    .put("startedAt", 1794013200000L).put("endedAt", 1794013210000L).put("distanceKm", .125);
            restored.finish(snapshot);
            restored.finish(snapshot.put("endedAt", 1794013220000L));
            JSONObject after = new JSONObject(new EncryptedJsonStore(file).read());
            JSONArray jobs = after.getJSONArray("jobs");
            assertEquals(6, jobs.length());
            assertEquals(batch, jobs.getJSONObject(1).getJSONObject("body").getString("batchId"));
            assertEquals(2, jobs.getJSONObject(2).getJSONObject("body").getJSONArray("samples").getJSONObject(0).getLong("sequence"));
            assertEquals(2, jobs.getJSONObject(3).getJSONObject("body").getLong("lastSequence"));
            assertEquals("2026-11-07T01:00:10Z", jobs.getJSONObject(3).getJSONObject("body").getString("endedAt"));
            assertThrows(IllegalStateException.class, () -> restored.sample("local-test", new JSONObject(), ""));
            byte[] corrupt = Files.readAllBytes(file.toPath());
            corrupt[corrupt.length - 1] ^= 1;
            Files.write(file.toPath(), corrupt);
            assertThrows(Exception.class, () -> new RecordingSync(isolated, connection));
            assertTrue(file.exists());
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
