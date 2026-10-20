package com.example.doodle.controller;

import com.example.doodle.controller.dto.BookMeetingRequest;
import com.example.doodle.controller.dto.MeetingResponse;
import com.example.doodle.service.MeetingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/slots/{slotId}/meeting")
public class MeetingController {

    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse bookMeeting(@PathVariable UUID slotId,
                                       @RequestBody BookMeetingRequest request) {
        return MeetingResponse.from(meetingService.bookMeeting(
                slotId, request.title(), request.description(), request.participants()));
    }
}