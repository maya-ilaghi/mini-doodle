package com.example.doodle.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("A user with this email already exists: " + email);
    }
}
