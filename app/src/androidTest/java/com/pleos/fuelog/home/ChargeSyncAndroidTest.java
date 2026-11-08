package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChargeSyncAndroidTest {
    @Test public void offlineChargeRetryKeepsIdAndWhitelistsEvidenceWithoutCoordinates() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "charge-outbox-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        try {
            ConnectionSession connection = ConnectionSession.get(isolated);
            RecordingSync sync = new RecordingSync(isolated, connection);
            sync.start("local", 1000, "owner");
            JSONObject candidate = new JSONObject().put("id", UUID.randomUUID().toString())
                    .put("startAt", 1000).put("endAt", 2000).put("estimatedKwh", 12).put("basis", "AAOS_WH")
                    .put("latitude", 37).put("longitude", 127);
            sync.charge(candidate, "owner", "local");
            sync.charge(candidate, "owner", "local");
            RecordingSync restored = new RecordingSync(isolated, connection);
            restored.charge(candidate, "owner", "local");
            assertEquals(2, restored.pendingCount());
            JSONObject body = new JSONObject(new EncryptedJsonStore(new File(directory, "recording-outbox.bin")).read())
                    .getJSONArray("jobs").getJSONObject(1).getJSONObject("body");
            assertEquals(candidate.getString("id"), body.getString("id"));
            assertEquals("owner", body.getString("owner"));
            assertFalse(body.has("latitude")); assertFalse(body.has("longitude"));
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
