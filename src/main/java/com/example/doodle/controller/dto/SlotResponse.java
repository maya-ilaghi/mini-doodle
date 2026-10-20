package com.example.doodle.controller.dto;

import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;

import java.time.Instant;
import java.util.UUID;

public record SlotResponse(UUID id, Instant startTime, Instant endTime, TimeSlotStatus status) {
    public static SlotResponse from(TimeSlot s) {
        return new SlotResponse(s.getId(), s.getStartTime(), s.getEndTime(), s.getSlotStatus());
    }
}

