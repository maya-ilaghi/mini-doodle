package com.example.doodle.controller.dto;

import java.util.List;

/**
 * Uniform error body. {@code details} lists per-field validation problems
 * (e.g. "endTime: must not be null") and is empty for other errors.
 */
public record ErrorResponse(String message, List<String> details) {
    public ErrorResponse(String message) {
        this(message, List.of());
    }
}
