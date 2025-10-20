package org.example.apispring.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        // TODO 예외 처리: 인증 실패 원인 매핑(BusinessException: OAUTH_AUTHENTICATION_FAILED 등)
        // 주의: 여기서 던진 예외는 @RestControllerAdvice가 바로 처리하지 못할 수 있음.
        // 임시: 실패 시 특정 경로로 리다이렉트 또는 JSON 바디 전송
        try {
            response.sendRedirect("/?oauth=fail"); // 임시
        } catch (Exception e) {
            // TODO 예외 처리: 리다이렉트 실패 로깅
        }
    }
}