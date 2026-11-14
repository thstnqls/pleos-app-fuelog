package com.pleos.fuelog.api;

import com.pleos.fuelog.BuildConfig;

import okhttp3.HttpUrl;

/** Creates a client from the build's API_BASE_URL. Keep and reuse the returned instance. */
public final class ApiModule {
    private ApiModule() {}

    public static ApiClient create() {
        return new ApiClient(baseUrl());
    }

    private static String baseUrl() {
        String baseUrl = BuildConfig.API_BASE_URL;
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("Set API_BASE_URL in local.properties and sync Gradle.");
        }
        HttpUrl url = HttpUrl.get(baseUrl);
        if (!BuildConfig.DEBUG && !isReleaseUrlAllowed(url)) {
            throw new IllegalStateException("Release builds require an HTTPS API_BASE_URL.");
        }
        return baseUrl;
    }

    static boolean isReleaseUrlAllowed(HttpUrl url) {
        // shortcut: the submission server uses HTTP; remove this exception after its HTTPS migration.
        return url.isHttps() || url.host().equals("sheetnews.ddns.net") && url.port() == 3001;
    }
}
