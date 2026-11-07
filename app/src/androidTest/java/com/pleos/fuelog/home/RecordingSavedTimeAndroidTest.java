package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingSavedTimeAndroidTest {
    @Test public void openingAndClosingIdleRecorderPreservesPreviousSaveTime() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "saved-time-test-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
        };
        SharedPreferences store = isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE);
        long previous = 1794013200000L;
        assertTrue(store.edit().putLong("savedAt", previous).commit());
        HomeRecorder[] recorder = new HomeRecorder[1];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0] = new HomeRecorder(isolated));
            assertEquals(previous, recorder[0].trip.savedAt);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0].pause());
            assertEquals(previous, store.getLong("savedAt", 0));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recorder[0] = new HomeRecorder(isolated));
            assertEquals(previous, recorder[0].trip.savedAt);
        } finally {
            target.deleteSharedPreferences(prefix + "home_recording");
            File[] files = directory.listFiles();
            if (files != null) for (File entry : files) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
