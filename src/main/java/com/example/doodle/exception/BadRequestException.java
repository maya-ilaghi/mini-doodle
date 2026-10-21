package com.example.doodle.exception;

/** A request that is well-formed but semantically invalid, e.g. a time window with from >= to. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
