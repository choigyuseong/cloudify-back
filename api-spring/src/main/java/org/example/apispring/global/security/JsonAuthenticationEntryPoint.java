package org.example.apispring.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.global.security.jwt.JwtErrorCodeMapper;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JwtErrorCodeMapper jwtErrorCodeMapper;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) {
        Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
        ErrorCode ec = jwtErrorCodeMapper.map(cause);
        handlerExceptionResolver.resolveException(req, res, null, new BusinessException(ec));
    }
}

