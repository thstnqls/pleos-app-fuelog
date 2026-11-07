package com.pleos.fuelog.api;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiClientTest {
    private MockWebServer server;
    private ApiClient api;

    @Before public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        api = new ApiClient(server.url("/api/v1").toString());
    }

    @After public void tearDown() throws IOException { server.shutdown(); }

    @Test public void crudPreservesPrefixMethodsJsonAndAuthentication() throws Exception {
        String[] methods = {"POST", "GET", "PUT", "PATCH", "DELETE"};
        for (String method : methods) {
            server.enqueue(new MockResponse().setResponseCode(method.equals("DELETE") ? 204 : 200)
                    .setBody(method.equals("DELETE") ? "" : "{\"id\":1}"));
            Result result = new Result();
            switch (method) {
                case "POST": api.create("records", "{\"value\":1}", "token", result); break;
                case "GET": api.read("records", Collections.singletonMap("q", "서울 & 주유"), "token", result); break;
                case "PUT": api.update("records/1", "{\"value\":2}", "token", result); break;
                case "PATCH": api.patch("records/1", "{\"value\":3}", "token", result); break;
                default: api.delete("records/1", "token", result);
            }
            result.await();
            assertNull(result.failure);
            assertFalse(result.httpError);
            assertTrue(result.response.isSuccessful());
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals("Bearer token", request.getHeader("Authorization"));
            assertTrue(request.getPath().startsWith("/api/v1/records"));
            if (method.equals("GET")) {
                assertEquals("서울 & 주유", request.getRequestUrl().queryParameter("q"));
            } else if (!method.equals("DELETE")) {
                assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
                assertEquals(method.equals("POST") ? "{\"value\":1}"
                        : method.equals("PUT") ? "{\"value\":2}" : "{\"value\":3}",
                        request.getBody().readUtf8());
            } else {
                assertEquals("", result.response.getBody());
            }
        }
    }

    @Test public void installationAndScopedProofsUseSeparateHeadersAtBackendRoot() throws Exception {
        ApiClient root = new ApiClient(server.url("/").toString());
        server.enqueue(new MockResponse().setBody("{}"));
        Result result = new Result();
        root.request("POST", "recording-intervals/id/samples", "{}", "installation",
                java.util.Map.of("X-Trip-User-Candidate", "candidate"), result);
        result.await();
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/recording-intervals/id/samples", request.getPath());
        assertEquals("Bearer installation", request.getHeader("Authorization"));
        assertEquals("candidate", request.getHeader("X-Trip-User-Candidate"));
        assertNull(request.getHeader("X-Trip-User-Session"));
        server.enqueue(new MockResponse().setBody("{}"));
        Result personal = new Result();
        root.request("DELETE", "trip-user/session", null, "installation",
                java.util.Map.of("X-Trip-User-Session", "Bearer scoped"), personal);
        personal.await();
        RecordedRequest released = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("DELETE", released.getMethod());
        assertEquals("Bearer scoped", released.getHeader("X-Trip-User-Session"));
        assertThrows(IllegalArgumentException.class, () -> root.request("GET", "vehicle/home", null,
                "installation", java.util.Map.of("Authorization", "Bearer mobile"), new Result()));
    }

    @Test public void httpErrorsRetainStatusAndBodyWithoutAuthorization() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"error\":\"invalid\"}"));
        Result result = new Result();
        api.create("records", "{}", null, result);
        result.await();
        assertTrue(result.httpError);
        assertNull(result.failure);
        assertEquals(422, result.response.getStatusCode());
        assertEquals("{\"error\":\"invalid\"}", result.response.getBody());
        assertNull(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization"));
    }

    @Test public void cancellationReportsTransportFailure() throws Exception {
        server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS));
        Result result = new Result();
        Call call = api.read("records", null, null, result);
        call.cancel();
        result.await();
        assertNotNull(result.failure);
        assertNull(result.response);
    }

    @Test public void redirectsAreNotFollowed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/other"));
        Result result = new Result();
        api.read("records", null, "token", result);
        result.await();
        assertTrue(result.httpError);
        assertEquals(302, result.response.getStatusCode());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void rejectsPathsThatEscapeBaseUrl() {
        for (String path : new String[]{"/records", "../records", "%2e%2e/records",
                "https://other.example/", "//other.example/", "..\\records"}) {
            assertThrows(IllegalArgumentException.class, () -> api.read(path, null, "token", new Result()));
        }
        assertEquals(0, server.getRequestCount());
        assertThrows(IllegalArgumentException.class, () -> new ApiClient(""));
        assertThrows(IllegalArgumentException.class, () -> new ApiClient("https://user:pass@example.com/"));
    }

    private static final class Result implements ApiClient.ResultCallback {
        final CountDownLatch completed = new CountDownLatch(1);
        ApiClient.ApiResponse response;
        IOException failure;
        boolean httpError;

        @Override public void onSuccess(ApiClient.ApiResponse response) {
            this.response = response;
            completed.countDown();
        }
        @Override public void onHttpError(ApiClient.ApiResponse response) {
            this.httpError = true;
            this.response = response;
            completed.countDown();
        }
        @Override public void onFailure(IOException error) {
            failure = error;
            completed.countDown();
        }
        void await() throws InterruptedException {
            assertTrue("No callback received", completed.await(5, TimeUnit.SECONDS));
        }
    }
}
