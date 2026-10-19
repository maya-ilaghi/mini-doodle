package com.example.doodle.exception;

import java.time.Instant;

public class SlotOverlapException extends RuntimeException {
    public SlotOverlapException(Instant start, Instant end) {
        super("Slot overlaps an existing slot: %s to %s".formatted(start, end));
    }
}
