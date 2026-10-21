package com.example.doodle.service;

import com.example.doodle.controller.dto.AvailabilityResponse;
import com.example.doodle.controller.dto.SlotResponse;
import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import com.example.doodle.domain.User;
import com.example.doodle.exception.BadRequestException;
import com.example.doodle.exception.NotFoundException;
import com.example.doodle.exception.SlotHasMeetingException;
import com.example.doodle.exception.SlotOverlapException;
import com.example.doodle.repository.MeetingRepository;
import com.example.doodle.repository.TimeSlotRepository;
import com.example.doodle.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SlotService {

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String OVERLAP_CONSTRAINT = "time_slot_no_overlap";

    private final UserRepository userRepository;
    private final TimeSlotRepository slotRepository;
    private final MeetingRepository meetingRepository;

    public SlotService(UserRepository userRepository,
                       TimeSlotRepository slotRepository,
                       MeetingRepository meetingRepository) {
        this.userRepository = userRepository;
        this.slotRepository = slotRepository;
        this.meetingRepository = meetingRepository;
    }

    @Transactional
    public TimeSlot createSlot(UUID userId, Instant start, Instant end) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));

        TimeSlot slot = new TimeSlot(user.getCalendar(), start, end);
        try {
            return slotRepository.saveAndFlush(slot);   // flush now so the overlap constraint fires here
        } catch (DataIntegrityViolationException e) {
            throw translateOverlap(e, start, end);
        }
    }

    @Transactional(readOnly = true)
    public Page<TimeSlot> listSlots(UUID userId, Instant from, Instant to, Pageable pageable) {
        requireValidWindow(from, to);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));
        return slotRepository.findOverlapping(user.getCalendar().getId(), from, to, pageable);
    }

    @Transactional
    public TimeSlot modifySlot(UUID userId, UUID slotId, Instant newStart, Instant newEnd) {
        TimeSlot slot = findOwnedSlot(userId, slotId);
        requireNoMeeting(slot);
        slot.setStartTime(newStart);
        slot.setEndTime(newEnd);
        try {
            return slotRepository.saveAndFlush(slot);   // modifying times can create an overlap too
        } catch (DataIntegrityViolationException e) {
            throw translateOverlap(e, newStart, newEnd);
        }
    }

    @Transactional
    public void deleteSlot(UUID userId, UUID slotId) {
        TimeSlot slot = findOwnedSlot(userId, slotId);
        requireNoMeeting(slot);
        slotRepository.delete(slot);   // versioned delete: loses cleanly to a concurrent booking
    }

    /**
     * Marks a slot busy or free by hand, e.g. to block time without booking a meeting.
     * Takes the same row lock as booking, so a status change and a booking of one slot
     * can't interleave. A slot that carries a meeting is BUSY because of that meeting;
     * its status can only change by cancelling the meeting.
     */
    @Transactional
    public TimeSlot updateStatus(UUID userId, UUID slotId, TimeSlotStatus status) {
        TimeSlot slot = slotRepository.findByIdForUpdate(slotId)
                .filter(s -> s.getCalendar().getUser().getId().equals(userId))
                .orElseThrow(() -> new NotFoundException("slot", slotId));
        requireNoMeeting(slot);
        slot.setSlotStatus(status);
        return slotRepository.saveAndFlush(slot);
    }

    /**
     * A booked slot carries a meeting. Moving it would silently reschedule the meeting and
     * deleting it would silently cascade-delete the meeting, so both require cancelling first.
     * (A slot that is BUSY only because its owner marked it so has no meeting and stays editable.)
     * A booking that lands concurrently is caught by the @Version check on flush (-> 409).
     */
    private void requireNoMeeting(TimeSlot slot) {
        if (meetingRepository.existsBySlot_Id(slot.getId())) {
            throw new SlotHasMeetingException(slot.getId());
        }
    }

    private static void requireValidWindow(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
    }

    /**
     * Only an exclusion-constraint violation (SQLSTATE 23P01, time_slot_no_overlap) means
     * "overlap". Anything else is rethrown untouched instead of being mislabelled — it
     * still reaches the client as a 409 via the global handler, but with an honest message.
     */
    private static RuntimeException translateOverlap(DataIntegrityViolationException e,
                                                     Instant start, Instant end) {
        Throwable cause = e.getMostSpecificCause();
        boolean exclusionViolation =
                (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState()))
                || String.valueOf(cause.getMessage()).contains(OVERLAP_CONSTRAINT);
        return exclusionViolation ? new SlotOverlapException(start, end) : e;
    }

    /**
     * Resolves a slot only if it lives in this user's calendar. A slot owned by someone
     * else is reported as 404 (not 403) so the API doesn't reveal which slot ids exist.
     */
    private TimeSlot findOwnedSlot(UUID userId, UUID slotId) {
        return slotRepository.findByIdAndCalendar_User_Id(slotId, userId)
                .orElseThrow(() -> new NotFoundException("slot", slotId));
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID userId, Instant from, Instant to) {
        requireValidWindow(from, to);
        // 1. load user -> calendar id
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId));
        UUID calendarId = user.getCalendar().getId();

        // 2. fetch every slot that overlaps the window, ordered by start time
        List<TimeSlot> slots = slotRepository.findAllOverlapping(calendarId, from, to);

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