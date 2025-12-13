package org.example.apispring.global.security.jwt;

import org.example.apispring.global.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class JwtErrorCodeMapper {
    public ErrorCode map(Throwable t) {
        if (t instanceof io.jsonwebtoken.ExpiredJwtException) return ErrorCode.JWT_EXPIRED;
        if (t instanceof io.jsonwebtoken.JwtException || t instanceof IllegalArgumentException)
            return ErrorCode.JWT_INVALID;
        return ErrorCode.UNAUTHORIZED;
    }
}