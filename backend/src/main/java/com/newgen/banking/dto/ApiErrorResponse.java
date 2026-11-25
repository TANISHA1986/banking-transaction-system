package com.newgen.banking.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Standard JSON error payload returned by {@link com.newgen.banking.exception.GlobalExceptionHandler}.
 */
public record ApiErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> validationErrors
) {
    public ApiErrorResponse(
            LocalDateTime timestamp,
            int status,
            String error,
            String message,
            String path
    ) {
        this(timestamp, status, error, message, path, null);
    }
}
