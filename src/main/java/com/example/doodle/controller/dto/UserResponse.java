package com.example.doodle.controller.dto;

import com.example.doodle.domain.User;

import java.util.UUID;

public record UserResponse(UUID id, String email, String name) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getName());
    }
}

