package com.example.doodle.controller.dto;

import com.example.doodle.domain.TimeSlotStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateSlotStatusRequest(@NotNull TimeSlotStatus status) {}
