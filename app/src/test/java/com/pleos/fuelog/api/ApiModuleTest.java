package com.pleos.fuelog.api;

import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiModuleTest {
    @Test public void releaseOnlyAllowsHttpsOrTheExactSubmissionEndpoint() {
        assertTrue(ApiModule.isReleaseUrlAllowed(HttpUrl.get("https://example.com/")));
        assertTrue(ApiModule.isReleaseUrlAllowed(HttpUrl.get("http://sheetnews.ddns.net:3001/")));
        for (String address : new String[]{"http://localhost:8080/", "http://example.com:3001/",
                "http://sheetnews.ddns.net/", "http://sub.sheetnews.ddns.net:3001/",
                "http://sheetnews.ddns.net.example.com:3001/"})
            assertFalse(address, ApiModule.isReleaseUrlAllowed(HttpUrl.get(address)));
    }
}
