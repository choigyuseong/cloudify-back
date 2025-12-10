package org.example.apispring.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 10XX ~ 12XX 인증/인가
    UNAUTHORIZED(1001, "Authentication required or token invalid", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1002, "You do not have permission to access this resource", HttpStatus.FORBIDDEN),

    JWT_EXPIRED(1101, "Access token expired", HttpStatus.UNAUTHORIZED),
    JWT_INVALID(1102, "Invalid token", HttpStatus.UNAUTHORIZED),
    JWT_MISSING(1103, "Access token missing", HttpStatus.UNAUTHORIZED),

    OAUTH_CONSENT_REQUIRED(1201, "Google consent required", HttpStatus.UNAUTHORIZED),
    OAUTH_SCOPES_MISSING(1202, "Required Google scopes are missing", HttpStatus.FORBIDDEN),
    CREDENTIALS_REVOKED(1203, "OAuth credentials revoked", HttpStatus.UNAUTHORIZED),

    // 13XX YouTube 검색
    YOUTUBE_API_KEY_MISSING(1300, "YouTube API key is not configured", HttpStatus.INTERNAL_SERVER_ERROR),
    YOUTUBE_UPSTREAM_ERROR(1301, "Failed to call YouTube API", HttpStatus.BAD_GATEWAY),
    YOUTUBE_QUOTA_EXCEEDED(1302, "YouTube API quota exceeded", HttpStatus.TOO_MANY_REQUESTS),
    YOUTUBE_VIDEO_NOT_FOUND(1304, "YouTube video not found", HttpStatus.NOT_FOUND),

    // 14XX Gemini / LLM 태그 추론
    GEMINI_API_KEY_MISSING(1400, "Gemini API key is not configured", HttpStatus.INTERNAL_SERVER_ERROR),
    GEMINI_UPSTREAM_ERROR(1401, "Failed to call Gemini API", HttpStatus.BAD_GATEWAY),
    GEMINI_QUOTA_EXCEEDED(1402, "Gemini API quota exceeded", HttpStatus.TOO_MANY_REQUESTS),
    GEMINI_RESPONSE_INVALID(1403, "Invalid response from Gemini API", HttpStatus.BAD_GATEWAY),
    GEMINI_TAG_JSON_PARSE_ERROR(1404, "Failed to parse tag JSON from Gemini response", HttpStatus.BAD_GATEWAY),
    GEMINI_TAG_ENUM_MISMATCH(1405, "Gemini returned unsupported tag value", HttpStatus.BAD_REQUEST),

    // 15XX 추천 도메인
    RECOMMENDATION_NO_CANDIDATES(1500, "No songs matched the given tags", HttpStatus.NOT_FOUND),
    RECOMMENDATION_INTERNAL_ERROR(1599, "Failed to compute recommendations", HttpStatus.INTERNAL_SERVER_ERROR),

    // 16XX Genius 검색
    GENIUS_API_TOKEN_MISSING(1600, "Genius API token is not configured", HttpStatus.INTERNAL_SERVER_ERROR),
    GENIUS_UPSTREAM_ERROR(1601, "Failed to call Genius API", HttpStatus.BAD_GATEWAY),
    GENIUS_QUOTA_EXCEEDED(1602, "Genius API quota exceeded", HttpStatus.TOO_MANY_REQUESTS),
    GENIUS_RESPONSE_INVALID(1603, "Invalid response from Genius API", HttpStatus.BAD_GATEWAY),

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
