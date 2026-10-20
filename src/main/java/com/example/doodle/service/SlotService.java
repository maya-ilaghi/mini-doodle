package com.example.doodle.service;

import com.example.doodle.controller.dto.AvailabilityResponse;
import com.example.doodle.controller.dto.SlotResponse;
import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import com.example.doodle.domain.User;
import com.example.doodle.exception.NotFoundException;
import com.example.doodle.exception.SlotOverlapException;
import com.example.doodle.repository.TimeSlotRepository;
import com.example.doodle.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SlotService {

    private final UserRepository userRepository;
    private final TimeSlotRepository slotRepository;

    public SlotService(UserRepository userRepository, TimeSlotRepository slotRepository) {
        this.userRepository = userRepository;
        this.slotRepository = slotRepository;
    }

    @Transactional
    public TimeSlot createSlot(UUID userId, Instant start, Instant end) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));

        TimeSlot slot = new TimeSlot(user.getCalendar(), start, end);
        try {
            return slotRepository.saveAndFlush(slot);   // flush now so the overlap constraint fires here
        } catch (DataIntegrityViolationException e) {
            throw new SlotOverlapException(start, end);  // -> 409
        }
    }

    @Transactional(readOnly = true)
    public Page<TimeSlot> listSlots(UUID userId, Instant from, Instant to, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));
        return slotRepository.findByCalendarIdAndStartTimeBetween(
                user.getCalendar().getId(), from, to, pageable);
    }

    @Transactional
    public TimeSlot modifySlot(UUID slotId, Instant newStart, Instant newEnd) {
        TimeSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("slot", slotId));
        slot.setStartTime(newStart);
        slot.setEndTime(newEnd);
        try {
            return slotRepository.saveAndFlush(slot);   // modifying times can create an overlap too
        } catch (DataIntegrityViolationException e) {
            throw new SlotOverlapException(newStart, newEnd);
        }
    }

    @Transactional
    public void deleteSlot(UUID slotId) {
        if (!slotRepository.existsById(slotId)) {
            throw new NotFoundException("slot", slotId);
        }
        slotRepository.deleteById(slotId);
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID userId, Instant from, Instant to) {
        // 1. load user -> calendar id
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));
        UUID calendarId = user.getCalendar().getId();

        // 2. fetch slots in range
        List<TimeSlot> slots = slotRepository.findByCalendarIdAndStartTimeBetween(calendarId, from, to);

        // 3. partition into free vs busy by status
        List<SlotResponse> free = slots.stream()
                .filter(s -> s.getSlotStatus() == TimeSlotStatus.FREE)
                .map(SlotResponse::from)
                .toList();
        List<SlotResponse> busy = slots.stream()
                .filter(s -> s.getSlotStatus() == TimeSlotStatus.BUSY)
                .map(SlotResponse::from)
                .toList();

        // 4. build the response
        return new AvailabilityResponse(from, to, free, busy);
    }
}