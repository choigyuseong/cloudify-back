package org.example.apispring.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.global.error.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper om;

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        Throwable cause = (ex.getCause() != null)
                ? ex.getCause()
                : ex;
        ErrorCode ec = mapJwtError(cause);

        res.setStatus(ec.getHttpStatus().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        om.writeValue(res.getWriter(), ErrorResponse.of(ec));
    }

    // 예외들 분기 처리
    private ErrorCode mapJwtError(Throwable t) {
        if (t instanceof io.jsonwebtoken.ExpiredJwtException) return ErrorCode.JWT_EXPIRED;
        if (t instanceof io.jsonwebtoken.JwtException || t instanceof IllegalArgumentException)
            return ErrorCode.JWT_INVALID;
        return ErrorCode.UNAUTHORIZED;
    }
}

