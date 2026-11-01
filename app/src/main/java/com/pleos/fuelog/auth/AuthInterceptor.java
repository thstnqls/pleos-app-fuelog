package com.pleos.fuelog.auth;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

/** Attach only to the app's configured backend client; do not reuse for arbitrary URLs. */
public final class AuthInterceptor implements Interceptor {
    private final SupabaseAuth auth;

    public AuthInterceptor(SupabaseAuth auth) { this.auth = auth; }

    @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer " + auth.accessTokenBlocking()).build());
    }
}
