package com.example.doodle.repository;

import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

    // slots in a user's calendar within a time window (paginated) — rides the (calendar_id, start_time) index
    Page<TimeSlot> findByCalendarIdAndStartTimeBetween(
            UUID calendarId, Instant from, Instant to, Pageable pageable);

    // free-vs-busy filtering — rides the (calendar_id, status) index
    Page<TimeSlot> findByCalendarIdAndSlotStatus(
            UUID calendarId, TimeSlotStatus status, Pageable pageable);

    List<TimeSlot> findByCalendarIdAndStartTimeBetween(UUID calendarId, Instant from, Instant to);

    // a slot, but only if it belongs to the given user's calendar (ownership check)
    Optional<TimeSlot> findByIdAndCalendar_User_Id(UUID id, UUID userId);

    /**
     * Loads a slot with a row lock (SELECT ... FOR UPDATE). Concurrent bookings of the
     * same slot are serialised here: the second transaction waits, then sees the slot
     * as BUSY and is rejected with a clean 409 instead of failing later on a constraint.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TimeSlot s where s.id = :id")
    Optional<TimeSlot> findByIdForUpdate(@Param("id") UUID id);
}
