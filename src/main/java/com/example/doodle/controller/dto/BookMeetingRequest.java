package com.example.doodle.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BookMeetingRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 10_000) String description,
        @Size(max = 100) List<@NotBlank @Email @Size(max = 320) String> participants
) {}
