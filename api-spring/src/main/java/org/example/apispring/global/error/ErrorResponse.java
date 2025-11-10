package org.example.apispring.global.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public record ErrorResponse(int code, String name, String message) {

    public static ErrorResponse of(ErrorCode ec) {
        return new ErrorResponse(ec.getCode(), ec.name(), ec.getMessage());
    }

    public static ErrorResponse of(ErrorCode ec, String customMessage) {
        return new ErrorResponse(ec.getCode(), ec.name(), customMessage);
    }


    // 시큐리티/OAuth 레이어 용: HttpServletResponse에 직접 쓰기
    public static void write(HttpServletResponse res, ObjectMapper om, ErrorCode ec, @Nullable String customMessage)
            throws IOException {
        res.setStatus(ec.getHttpStatus().value());
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        om.writeValue(res.getWriter(), customMessage == null ? of(ec) : of(ec, customMessage));
    }
}
