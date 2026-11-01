package com.pleos.fuelog.auth;

import java.io.IOException;

/** Access only on the auth executor. Session and pending PKCE state are committed together. */
interface AuthStore {
    String read() throws IOException;
    void write(String value) throws IOException;
}
