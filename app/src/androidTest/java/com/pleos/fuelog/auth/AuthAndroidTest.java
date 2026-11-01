package com.pleos.fuelog.auth;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class AuthAndroidTest {
    @Test public void callbackResolvesToDedicatedActivity() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(AuthRedirect.URL + "?code=test"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
        assertNotNull(intent.resolveActivity(context.getPackageManager()));
        assertEquals(AuthActivity.class.getName(), intent.resolveActivity(context.getPackageManager()).getClassName());
        Intent invalid = new Intent(Intent.ACTION_VIEW, Uri.parse("com.pleos.fuelog://other/?code=test"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
        assertNull(invalid.resolveActivity(context.getPackageManager()));
    }

    @Test public void encryptedStateSurvivesRecreationAndRejectsTampering() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File location = new File(context.getNoBackupFilesDir(), "auth-instrumentation-test.bin");
        try {
            EncryptedAuthStore first = new EncryptedAuthStore(location);
            String state = "{\"access_token\":\"private-test-token\",\"verifier\":\"private-verifier\"}";
            first.write(state);
            byte[] encrypted = Files.readAllBytes(location.toPath());
            assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("private-test-token"));
            assertEquals(state, new EncryptedAuthStore(location).read());
            encrypted[encrypted.length - 1] ^= 1;
            Files.write(location.toPath(), encrypted);
            assertThrows(AuthException.class, () -> first.read());
            assertEquals("{}", first.read());
        } finally {
            Files.deleteIfExists(location.toPath());
        }
    }
}
