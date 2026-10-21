package com.example.doodle.repository;

import com.example.doodle.domain.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Optional<Meeting> findBySlot_Id(UUID slotId);
}
