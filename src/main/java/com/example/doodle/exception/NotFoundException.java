package com.example.doodle.exception;

import java.util.UUID;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String entity, UUID id) {
        super("%s not found: %s".formatted(entity, id));
    }
}