package com.example.doodle.controller.dto;

import com.example.doodle.domain.Meeting;

import java.util.List;
import java.util.UUID;

public record MeetingResponse(UUID id, UUID slotId, String title, String description, List<String> participants) {

    public static MeetingResponse from(Meeting m) {
        List<String> emails = m.getParticipants().stream()
                .map(p -> p.getEmail())
                .toList();
        return new MeetingResponse(m.getId(), m.getSlot().getId(), m.getTitle(), m.getDescription(), emails);
    }
}