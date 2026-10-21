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

    /*
     * "In the window [from, to)" means the slot OVERLAPS the window: start < to AND end > from.
     * This includes a slot that starts before `from` but runs into the window, and excludes one
     * that merely touches an edge (consistent with the half-open ranges used for overlap checks).
     * The start_time < :to predicate rides the (calendar_id, start_time) index.
     */

    // paginated listing; the caller supplies the sort (defaults to startTime in the controller)
    @Query(value = "select s from TimeSlot s where s.calendar.id = :calendarId"
                 + " and s.startTime < :to and s.endTime > :from",
           countQuery = "select count(s) from TimeSlot s where s.calendar.id = :calendarId"
                 + " and s.startTime < :to and s.endTime > :from")
    Page<TimeSlot> findOverlapping(@Param("calendarId") UUID calendarId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    // everything in the window, for the aggregated free/busy view
    @Query("select s from TimeSlot s where s.calendar.id = :calendarId"
         + " and s.startTime < :to and s.endTime > :from order by s.startTime")
    List<TimeSlot> findAllOverlapping(@Param("calendarId") UUID calendarId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);

    // free-vs-busy filtering — rides the (calendar_id, status) index
    Page<TimeSlot> findByCalendarIdAndSlotStatus(
            UUID calendarId, TimeSlotStatus status, Pageable pageable);

    // a slot, but only if it belongs to the given user's calendar (ownership check)
    Optional<TimeSlot> findByIdAndCalendar_User_Id(UUID id, UUID userId);

    /**
     * Loads a slot with a row lock (SELECT ... FOR UPDATE). Bookings, cancellations and
     * status changes of the same slot are serialised here: a second transaction waits,
     * then sees the committed state and is rejected with a clean 409 if it no longer applies.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TimeSlot s where s.id = :id")
    Optional<TimeSlot> findByIdForUpdate(@Param("id") UUID id);
}
