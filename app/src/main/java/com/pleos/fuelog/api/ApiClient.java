package com.pleos.fuelog.api;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Reuse one instance. All result callbacks run on OkHttp's background threads. */
public final class ApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HttpUrl baseUrl;
    private final OkHttpClient client;

    public ApiClient(String baseUrl) {
        this(baseUrl, clientBuilder().build());
    }

    static OkHttpClient.Builder clientBuilder() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                // Do not automatically repeat writes or follow redirects to another server.
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false);
    }

    public ApiClient(String baseUrl, OkHttpClient client) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Set API_BASE_URL in local.properties first.");
        }
        HttpUrl parsed = HttpUrl.get(baseUrl.trim());
        if (!parsed.username().isEmpty() || !parsed.password().isEmpty()
                || parsed.query() != null || parsed.fragment() != null) {
            throw new IllegalArgumentException("Base URL must not contain credentials, query or fragment.");
        }
        this.baseUrl = parsed.encodedPath().endsWith("/")
                ? parsed : parsed.newBuilder().addPathSegment("").build();
        this.client = Objects.requireNonNull(client, "client");
    }

    public Call read(String path, Map<String, String> query, String token, ResultCallback callback) {
        HttpUrl.Builder url = resolve(path).newBuilder();
        if (query != null) {
            query.forEach(url::addQueryParameter);
        }
        return enqueue("GET", url.build(), null, token, callback);
    }

    public Call create(String path, String json, String token, ResultCallback callback) {
        return enqueue("POST", resolve(path), Objects.requireNonNull(json, "json"), token, callback);
    }

    public Call update(String path, String json, String token, ResultCallback callback) {
        return enqueue("PUT", resolve(path), Objects.requireNonNull(json, "json"), token, callback);
    }

    public Call patch(String path, String json, String token, ResultCallback callback) {
        return enqueue("PATCH", resolve(path), Objects.requireNonNull(json, "json"), token, callback);
    }

    public Call delete(String path, String token, ResultCallback callback) {
        return enqueue("DELETE", resolve(path), null, token, callback);
    }

    private HttpUrl resolve(String path) {
        Objects.requireNonNull(path, "path");
        if (path.startsWith("/") || path.contains(":") || path.contains("\\")) {
            throw new IllegalArgumentException("Use a relative API path, for example records/123.");
        }
        HttpUrl url = baseUrl.resolve(path);
        if (url == null || !url.scheme().equals(baseUrl.scheme())
                || !url.host().equals(baseUrl.host()) || url.port() != baseUrl.port()
                || !url.encodedPath().startsWith(baseUrl.encodedPath())
                || url.fragment() != null) {
            throw new IllegalArgumentException("API path must stay within the configured base URL.");
        }
        return url;
    }

    private Call enqueue(String method, HttpUrl url, String json, String token, ResultCallback callback) {
        Objects.requireNonNull(callback, "callback");
        RequestBody body = json == null ? null : RequestBody.create(json, JSON);
        Request.Builder request = new Request.Builder().url(url)
                .header("Accept", "application/json").method(method, body);
        if (token != null && !token.trim().isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        Call call = client.newCall(request.build());
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onFailure(error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                ApiResponse result;
                try (Response closedResponse = response) {
                    ResponseBody responseBody = closedResponse.body();
                    result = new ApiResponse(closedResponse.code(), closedResponse.headers(),
                            responseBody == null ? "" : responseBody.string());
                } catch (IOException error) {
                    callback.onFailure(error);
                    return;
                }
                if (result.isSuccessful()) {
                    callback.onSuccess(result);
                } else {
                    callback.onHttpError(result);
                }
            }
        });
        return call;
    }

    public interface ResultCallback {
        void onSuccess(ApiResponse response);
        void onHttpError(ApiResponse response);
        void onFailure(IOException error);
    }

    /** Body is raw text so the backend may return an object, array, or an empty 204 response. */
    public static final class ApiResponse {
        private final int statusCode;
        private final Headers headers;
        private final String body;

        private ApiResponse(int statusCode, Headers headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        public int getStatusCode() { return statusCode; }
        public Headers getHeaders() { return headers; }
        public String getBody() { return body; }
        public boolean isSuccessful() { return statusCode >= 200 && statusCode < 300; }
    }
}
