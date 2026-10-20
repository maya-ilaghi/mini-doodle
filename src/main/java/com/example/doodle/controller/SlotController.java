package com.example.doodle.controller;

import com.example.doodle.controller.dto.AvailabilityResponse;
import com.example.doodle.controller.dto.CreateSlotRequest;
import com.example.doodle.controller.dto.SlotResponse;
import com.example.doodle.service.SlotService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/slots")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlotResponse createSlot(@PathVariable UUID userId,
                                   @RequestBody CreateSlotRequest request) {
        return SlotResponse.from(
                slotService.createSlot(userId, request.startTime(), request.endTime()));
    }

    @GetMapping
    public Page<SlotResponse> listSlots(@PathVariable UUID userId,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to,
                                        Pageable pageable) {
        return slotService.listSlots(userId, from, to, pageable).map(SlotResponse::from);
    }

    @PatchMapping("/{slotId}")
    public SlotResponse modifySlot(@PathVariable UUID userId,
                                   @PathVariable UUID slotId,
                                   @RequestBody CreateSlotRequest request) {
        return SlotResponse.from(
                slotService.modifySlot(userId, slotId, request.startTime(), request.endTime()));
    }

    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable UUID userId, @PathVariable UUID slotId) {
        slotService.deleteSlot(userId, slotId);
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(@PathVariable UUID userId,
                                             @RequestParam Instant from,
                                             @RequestParam Instant to) {
        return slotService.getAvailability(userId, from, to);
    }
}