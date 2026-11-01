package com.pleos.fuelog.auth;

import java.net.URI;

import okhttp3.HttpUrl;

/** Only the fixed callback is accepted. Tokens supplied directly in links are never trusted. */
public final class AuthRedirect {
    public static final String URL = "com.pleos.fuelog://login-callback/";

    private AuthRedirect() {}

    public static String authorizationCode(String value) throws AuthException {
        try {
            URI uri = new URI(value);
            if (!"com.pleos.fuelog".equals(uri.getScheme())
                    || !"login-callback".equals(uri.getRawAuthority())
                    || !"/".equals(uri.getRawPath())) {
                throw new AuthException("올바른 인증 복귀 주소가 아닙니다.");
            }
            HttpUrl params = HttpUrl.get("https://callback.invalid/?"
                    + (uri.getRawQuery() == null ? "" : uri.getRawQuery()));
            // Error callbacks can use query parameters or a fragment. Never display raw URL contents.
            if (params.queryParameter("error") != null || params.queryParameter("error_code") != null
                    || uri.getRawFragment() != null) {
                throw new AuthException("인증이 완료되지 않았습니다. 로그인 링크를 다시 요청하세요.");
            }
            if (params.queryParameterValues("code").size() != 1
                    || params.queryParameter("code").trim().isEmpty()) {
                throw new AuthException("인증 코드가 없습니다. 앱에서 로그인을 다시 시작하세요.");
            }
            return params.queryParameter("code");
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw new AuthException("인증 주소를 확인할 수 없습니다.");
        }
    }
}
