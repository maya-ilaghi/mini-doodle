package com.example.doodle.repository;

import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

    // slots in a user's calendar within a time window (paginated) — rides the (calendar_id, start_time) index
    Page<TimeSlot> findByCalendarIdAndStartTimeBetween(
            UUID calendarId, Instant from, Instant to, Pageable pageable);

    // free-vs-busy filtering — rides the (calendar_id, status) index
    Page<TimeSlot> findByCalendarIdAndSlotStatus(
            UUID calendarId, TimeSlotStatus status, Pageable pageable);

    List<TimeSlot> findByCalendarIdAndStartTimeBetween(UUID calendarId, Instant from, Instant to);
}