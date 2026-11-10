package com.pleos.fuelog.home;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.storage.EncryptedJsonStore;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingUserAndroidTest {
    @Test public void pairedUserSurvivesIntervalsAndRestartAndDisconnectPreservesOldOwner() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "paired-user-test-" + UUID.randomUUID();
        File directory = new File(target.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
        };
        ConnectionSession connection = ConnectionSession.get(isolated);
        String previousUser = connection.authenticatedSubject, previousName = connection.authenticatedName;
        boolean previousLinked = connection.linked;
        HomeRecorder[] recorder = new HomeRecorder[1];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                connection.linked = false;
                connection.authenticatedSubject = "user-a";
                connection.authenticatedName = "Driver A";
                recorder[0] = new HomeRecorder(isolated);
                recorder[0].toggle();
                assertTrue(recorder[0].trip.active);
                assertEquals("user-a", recorder[0].verifiedOwner());
                assertFalse(recorder[0].locationAssistDecisionMade());
                try { recorder[0].setLocationAssistConsent(true); }
                catch (Exception error) { throw new AssertionError(error); }
                recorder[0].toggle();
                recorder[0] = new HomeRecorder(isolated);
                assertTrue(recorder[0].locationAssistDecisionMade());
                assertTrue(recorder[0].locationAssistConsent());
                try { recorder[0].setLocationAssistConsent(false); }
                catch (Exception error) { throw new AssertionError(error); }
                recorder[0] = new HomeRecorder(isolated);
                assertTrue(recorder[0].locationAssistDecisionMade());
                assertFalse(recorder[0].locationAssistConsent());
                recorder[0].toggle();
                assertEquals("user-a", recorder[0].verifiedOwner());
                try {
                    recorder[0].lockPersonalSession();
                    recorder[0].remoteDisconnected();
                } catch (Exception error) { throw new AssertionError(error); }
                connection.authenticatedSubject = "user-b";
                connection.authenticatedName = "Driver B";
                recorder[0].toggle();
                assertEquals("user-b", recorder[0].verifiedOwner());
                assertFalse(recorder[0].locationAssistDecisionMade());
                assertFalse(recorder[0].locationAssistConsent());
            });
            JSONArray jobs = new JSONObject(new EncryptedJsonStore(new File(directory, "recording-outbox.bin")).read()).getJSONArray("jobs");
            int startsA = 0, startsB = 0;
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                if (!"start".equals(job.getString("kind"))) continue;
                String owner = job.getJSONObject("body").getString("subjectId");
                if (owner.equals("user-a")) startsA++;
                if (owner.equals("user-b")) startsB++;
            }
            assertEquals(2, startsA);
            assertEquals(1, startsB);
            JSONArray history = new JSONArray(isolated.getSharedPreferences("home_recording", Context.MODE_PRIVATE).getString("history", "[]"));
            assertEquals(2, history.length());
            for (int i = 0; i < history.length(); i++) assertEquals("user-a", history.getJSONObject(i).getString("owner"));
        } finally {
            connection.authenticatedSubject = previousUser;
            connection.authenticatedName = previousName;
            connection.linked = previousLinked;
            target.deleteSharedPreferences(prefix + "home_recording");
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
