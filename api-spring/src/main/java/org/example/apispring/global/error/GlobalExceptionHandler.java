package org.example.apispring.global.error;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import java.util.NoSuchElementException;

import static org.example.apispring.global.error.ErrorCode.*;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        var ec = e.errorCode();
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec, e.getMessage()));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<ErrorResponse> handleValidation(Exception e) {
        var ec = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        var ec = FORBIDDEN;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler({ NoSuchElementException.class })
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        var ec = RESOURCE_NOT_FOUND;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleData(DataAccessException e) {
        var ec = DB_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        var ec = INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }
}
