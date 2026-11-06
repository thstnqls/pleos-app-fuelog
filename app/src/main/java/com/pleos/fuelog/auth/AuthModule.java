package com.pleos.fuelog.auth;

import android.content.Context;

import com.pleos.fuelog.BuildConfig;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.HttpUrl;

/** Application-wide client, independent of Activity/browser lifecycles. */
public final class AuthModule {
    private static SupabaseAuth instance;

    private AuthModule() {}
    public static synchronized void clearLocal(Context context, SupabaseAuth.Callback<Void> callback) {
        if (instance != null) { instance.clearLocal(callback); return; }
        new Thread(() -> {
            try { new EncryptedAuthStore(context.getApplicationContext()).write("{}"); callback.onSuccess(null); }
            catch (java.io.IOException e) { callback.onError(e); }
        }, "clear-local-auth").start();
    }

    public static synchronized SupabaseAuth get(Context context) {
        if (instance == null) {
            HttpUrl project = projectUrl(BuildConfig.SUPABASE_URL);
            validatePublicKey(BuildConfig.SUPABASE_PUBLISHABLE_KEY);
            instance = SupabaseAuth.create(project.newBuilder().addPathSegments("auth/v1/").build(),
                    BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    new EncryptedAuthStore(context.getApplicationContext()));
        }
        return instance;
    }

    static HttpUrl projectUrl(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.isHttps() || !url.encodedPath().equals("/")
                || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalStateException("local.properties에 HTTPS SUPABASE_URL을 설정하고 다시 빌드하세요.");
        }
        return url;
    }

    static void validatePublicKey(String key) {
        if (key.startsWith("sb_publishable_") && key.length() > "sb_publishable_".length()) return;
        // Legacy anon JWTs are also public. Reject service_role/secret keys in the client app.
        try {
            String[] parts = key.split("\\.");
            if (parts.length == 3 && "anon".equals(new JSONObject(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).optString("role"))) return;
        } catch (Exception ignored) { /* invalid/non-public key */ }
        throw new IllegalStateException("SUPABASE_PUBLISHABLE_KEY에 publishable 키 또는 legacy anon 키를 설정하세요.");
    }
}
