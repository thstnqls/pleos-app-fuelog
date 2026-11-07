package com.pleos.fuelog.connection;

import org.json.JSONObject;

/** Validates legacy vehicle-registration responses before the connection state changes. */
public final class ConnectionResponse {
    private ConnectionResponse() {}
    public static void validate(JSONObject value) throws Exception {
        String status = value.getString("status");
        if (!java.util.Arrays.asList("WAITING", "EXPIRED", "REVOKED", "COMPLETED", "ALREADY_LINKED").contains(status)
                || value.getString("requestId").trim().isEmpty()) throw new IllegalArgumentException();
        if (status.equals("WAITING")) {
            long ttl = value.getLong("remainingSeconds");
            if (ttl <= 0 || ttl > 120 || value.getString("nonce").trim().isEmpty()) throw new IllegalArgumentException();
        }
    }
}
