package com.pleos.fuelog.storage;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EncryptedJsonStoreAndroidTest {
    @Test public void deviceDataUsesExistingKeyAndSurvivesRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File location = new File(context.getCacheDir(), "encrypted-device-test.bin");
        try {
            EncryptedJsonStore first = new EncryptedJsonStore(location);
            String state = "{\"credential\":\"private-installation-secret\",\"operation\":\"connect-1\"}";
            first.write(state);
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            assertTrue("Existing installation/outbox key must remain usable", keys.containsAlias("fuelog.supabase.auth.v1"));
            byte[] encrypted = Files.readAllBytes(location.toPath());
            assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("private-installation-secret"));
            assertEquals(state, new EncryptedJsonStore(location).read());
            encrypted[encrypted.length - 1] ^= 1;
            Files.write(location.toPath(), encrypted);
            assertThrows(IOException.class, first::read);
            assertTrue("Damaged device records must be preserved", location.exists());
        } finally { Files.deleteIfExists(location.toPath()); }
    }
    @Test public void obsoleteLoginCallbackIsNotExposed() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent callback = new Intent(Intent.ACTION_VIEW, Uri.parse("com.pleos.fuelog://login-callback/?code=test"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
        assertNull(callback.resolveActivity(context.getPackageManager()));
    }
}
