package com.example.doodle.controller.dto;

import java.time.Instant;
import java.util.List;

public record AvailabilityResponse(
        Instant from,
        Instant to,
        List<SlotResponse> free,
        List<SlotResponse> busy
) {}