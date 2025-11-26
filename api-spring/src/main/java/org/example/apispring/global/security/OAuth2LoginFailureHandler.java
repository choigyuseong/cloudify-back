package org.example.apispring.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.global.error.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper om;

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        ErrorCode ec = ErrorCode.UNAUTHORIZED;

        // OAuth 내부에서 발생할 수 있는 에러 분기 처리
        if (ex instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oae) {
            String code = (oae.getError() != null)
                    ? oae.getError().getErrorCode()
                    : null;

            if ("access_denied".equals(code)) ec = ErrorCode.OAUTH_CONSENT_REQUIRED;
            else if ("invalid_scope".equals(code)) ec = ErrorCode.OAUTH_SCOPES_MISSING;
            else if ("invalid_request".equals(code)) ec = ErrorCode.VALIDATION_ERROR;
            else if ("server_error".equals(code)
                    || "temporarily_unavailable".equals(code)) ec = ErrorCode.INTERNAL_SERVER_ERROR;
        }

        res.setStatus(ec.getHttpStatus().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        om.writeValue(res.getWriter(), ErrorResponse.of(ec));
    }
}
