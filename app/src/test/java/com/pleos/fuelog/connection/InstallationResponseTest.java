package com.pleos.fuelog.connection;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstallationResponseTest {
    @Test public void checksIssuedInstallationAndNeverCopiesSecretIntoQr() throws Exception {
        String id = "11111111-1111-4111-8111-111111111111";
        String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        JSONObject issued = new JSONObject().put("installation_id", id).put("installation_secret", secret);
        InstallationResponse.validate(issued);
        JSONObject pairing = new JSONObject().put("id", id).put("expires_at", "2026-11-07T01:02:00Z")
                .put("qr", new JSONObject().put("v", 1).put("type", "fuelog.vehicle_pairing")
                        .put("request_id", id).put("proof", "temporary-proof").put("installation_secret", secret));
        String qr = InstallationResponse.qr(pairing, 1794013200000L);
        assertFalse(qr.contains(secret));
        assertEquals("temporary-proof", new JSONObject(qr).getString("proof"));
        assertThrows(Exception.class, () -> InstallationResponse.qr(pairing, 1794013320000L));
        issued.put("installation_secret", "short");
        assertThrows(Exception.class, () -> InstallationResponse.validate(issued));
    }
}
