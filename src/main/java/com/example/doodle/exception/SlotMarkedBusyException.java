package com.example.doodle.exception;

import java.util.UUID;

/** The owner marked the slot busy (no meeting on it), so it can't be booked. */
public class SlotMarkedBusyException extends RuntimeException {
    public SlotMarkedBusyException(UUID slotId) {
        super("Slot is marked busy: " + slotId);
    }
}
