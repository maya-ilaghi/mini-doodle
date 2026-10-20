package com.example.doodle.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

public class SlotAlreadyBookedException extends RuntimeException {
    public SlotAlreadyBookedException(UUID slotId) {
        super("Slot is already booked: " + slotId);
    }
}
