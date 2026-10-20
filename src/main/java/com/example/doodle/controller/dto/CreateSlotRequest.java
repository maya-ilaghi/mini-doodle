package com.example.doodle.controller.dto;

import java.time.Instant;

public record CreateSlotRequest(Instant startTime, Instant endTime) {}
