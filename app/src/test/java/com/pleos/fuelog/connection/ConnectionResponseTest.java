package com.pleos.fuelog.connection;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConnectionResponseTest {
    @Test public void onlyValidConnectionResponseLeavesPreview() throws Exception {
        ConnectionResponse.validate(new JSONObject("{\"status\":\"COMPLETED\",\"requestId\":\"r1\"}"));
        ConnectionResponse.validate(new JSONObject("{\"status\":\"WAITING\",\"requestId\":\"r1\",\"nonce\":\"one-time\",\"remainingSeconds\":120}"));
    }
    @Test public void arbitrarySuccessAndMalformedRegistrationCannotLeavePreview() throws Exception {
        for (String json : new String[]{"{}", "{\"status\":\"OK\",\"requestId\":\"r1\"}",
                "{\"status\":\"COMPLETED\",\"requestId\":\"\"}",
                "{\"status\":\"WAITING\",\"requestId\":\"r1\",\"nonce\":\"n\",\"remainingSeconds\":0}",
                "{\"status\":\"WAITING\",\"requestId\":\"r1\",\"nonce\":\"\",\"remainingSeconds\":120}"}) {
            boolean rejected = false;
            try { ConnectionResponse.validate(new JSONObject(json)); } catch (Exception e) { rejected = true; }
            assertTrue(json, rejected);
        }
    }
}
