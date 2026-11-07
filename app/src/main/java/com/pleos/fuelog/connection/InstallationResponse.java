package com.pleos.fuelog.connection;

import org.json.JSONObject;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The installation secret authenticates the device; QR proof is a different, temporary value. */
public final class InstallationResponse {
    private InstallationResponse() {}
    public static void validate(JSONObject value) throws Exception {
        UUID.fromString(value.getString("installation_id"));
        if (Base64.getUrlDecoder().decode(value.getString("installation_secret")).length < 32)
            throw new IllegalArgumentException("Installation secret is too short");
    }
    public static String qr(JSONObject issued, long now) throws Exception {
        UUID.fromString(issued.getString("id"));
        long expires = Instant.parse(issued.getString("expires_at")).toEpochMilli();
        if (expires <= now || expires - now > 125000) throw new IllegalArgumentException("Expired pairing QR");
        JSONObject qr = issued.getJSONObject("qr");
        if (!"fuelog.vehicle_pairing".equals(qr.getString("type")) || qr.getInt("v") != 1
                || !issued.getString("id").equals(qr.getString("request_id")) || qr.getString("proof").isEmpty())
            throw new IllegalArgumentException("Invalid pairing QR");
        return new JSONObject().put("type", qr.getString("type")).put("v", 1)
                .put("request_id", qr.getString("request_id")).put("proof", qr.getString("proof")).toString();
    }
}
