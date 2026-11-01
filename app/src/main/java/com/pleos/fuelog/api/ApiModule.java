package com.pleos.fuelog.api;

import android.content.Context;

import com.pleos.fuelog.BuildConfig;
import com.pleos.fuelog.auth.AuthModule;
import com.pleos.fuelog.auth.AuthInterceptor;
import com.pleos.fuelog.auth.SupabaseAuth;

import okhttp3.HttpUrl;

/** Creates a client from the build's API_BASE_URL. Keep and reuse the returned instance. */
public final class ApiModule {
    private ApiModule() {}

    public static ApiClient create() {
        return new ApiClient(baseUrl());
    }

    /** Uses the current session, refreshing before requests. Missing login fails without sending. */
    public static ApiClient createAuthenticated(Context context) {
        String baseUrl = baseUrl();
        SupabaseAuth auth = AuthModule.get(context);
        return new ApiClient(baseUrl, ApiClient.clientBuilder().addInterceptor(new AuthInterceptor(auth)).build());
    }

    private static String baseUrl() {
        String baseUrl = BuildConfig.API_BASE_URL;
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("Set API_BASE_URL in local.properties and sync Gradle.");
        }
        if (!BuildConfig.DEBUG && !HttpUrl.get(baseUrl).isHttps()) {
            throw new IllegalStateException("Release builds require an HTTPS API_BASE_URL.");
        }
        return baseUrl;
    }
}
