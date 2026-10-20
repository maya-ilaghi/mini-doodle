package com.example.doodle.controller;

import com.example.doodle.controller.dto.ErrorResponse;
import com.example.doodle.exception.NotFoundException;
import com.example.doodle.exception.SlotAlreadyBookedException;
import com.example.doodle.exception.SlotOverlapException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)                 // 404
    public ErrorResponse handleNotFound(NotFoundException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler({
            SlotOverlapException.class,
            SlotAlreadyBookedException.class,
            ObjectOptimisticLockingFailureException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)                  // 409
    public ErrorResponse handleConflict(RuntimeException e) {
        return new ErrorResponse(e.getMessage());
    }

    /**
     * Backstop for constraint violations the services didn't translate themselves
     * (e.g. a race that slips past the application checks and hits a UNIQUE key).
     * The request conflicts with current state, so 409 — never a 500. The raw
     * database message is not echoed back to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)                  // 409
    public ErrorResponse handleDataIntegrity(DataIntegrityViolationException e) {
        return new ErrorResponse("Request conflicts with the current state of the resource");
    }
}
