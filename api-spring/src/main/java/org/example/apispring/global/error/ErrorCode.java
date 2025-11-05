package org.example.apispring.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 1xxx 인증/인가
    UNAUTHORIZED(1001, "Authentication required or token invalid", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1002, "You do not have permission to access this resource", HttpStatus.FORBIDDEN),

    JWT_EXPIRED(1101, "Access token expired", HttpStatus.UNAUTHORIZED),
    JWT_INVALID(1102, "Invalid token", HttpStatus.UNAUTHORIZED),
    JWT_MISSING(1103, "Access token missing", HttpStatus.UNAUTHORIZED),

    OAUTH_CONSENT_REQUIRED(1201, "Google consent required", HttpStatus.UNAUTHORIZED),
    OAUTH_SCOPES_MISSING(1202, "Required Google scopes are missing", HttpStatus.FORBIDDEN),
    CREDENTIALS_REVOKED(1203, "OAuth credentials revoked", HttpStatus.UNAUTHORIZED),

    // 2xxx 리소스
    RESOURCE_NOT_FOUND(2404, "Resource not found", HttpStatus.NOT_FOUND),
    CONFLICT(2409, "Conflict", HttpStatus.CONFLICT),

    // 9xxx 공통
    VALIDATION_ERROR(9000, "Validation error", HttpStatus.BAD_REQUEST),
    DB_ERROR(9001, "Database error", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_SERVER_ERROR(9999, "Unexpected server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.httpStatus = status;
    }
}
