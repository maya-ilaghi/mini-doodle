package com.example.doodle.exception;

import java.util.UUID;

public class SlotHasMeetingException extends RuntimeException {
    public SlotHasMeetingException(UUID slotId) {
        super("Slot has a meeting; cancel the meeting before changing or deleting the slot: " + slotId);
    }
}
