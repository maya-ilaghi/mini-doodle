package com.example.doodle.controller;

import com.example.doodle.controller.dto.ErrorResponse;
import com.example.doodle.exception.BadRequestException;
import com.example.doodle.exception.EmailAlreadyRegisteredException;
import com.example.doodle.exception.NotFoundException;
import com.example.doodle.exception.SlotAlreadyBookedException;
import com.example.doodle.exception.SlotHasMeetingException;
import com.example.doodle.exception.SlotOverlapException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    // ---- 400 Bad Request --------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .sorted()
                .toList();
        return new ErrorResponse("Validation failed", details);
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(BadRequestException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        // e.g. malformed JSON or an unparseable timestamp; don't echo parser internals
        return new ErrorResponse("Malformed request body");
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadParameter(Exception e) {
        return new ErrorResponse(e instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for parameter '" + mismatch.getName() + "'"
                : e.getMessage());
    }

    // ---- 404 Not Found ----------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e) {
        return new ErrorResponse(e.getMessage());
    }

    // ---- 409 Conflict -----------------------------------------------------------------

    @ExceptionHandler({
            SlotOverlapException.class,
            SlotAlreadyBookedException.class,
            SlotHasMeetingException.class,
            EmailAlreadyRegisteredException.class,
            ObjectOptimisticLockingFailureException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
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
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDataIntegrity(DataIntegrityViolationException e) {
        return new ErrorResponse("Request conflicts with the current state of the resource");
    }
}
