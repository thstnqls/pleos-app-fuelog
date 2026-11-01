package com.pleos.fuelog.auth;

import java.io.IOException;

/** Messages are safe for display; never include response bodies, credentials or callback URLs. */
public final class AuthException extends IOException {
    private final int status;

    public AuthException(String message) { this(message, 0); }

    public AuthException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
