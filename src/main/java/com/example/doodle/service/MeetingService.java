package com.example.doodle.service;

import com.example.doodle.domain.Meeting;
import com.example.doodle.domain.Participant;
import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import com.example.doodle.exception.NotFoundException;
import com.example.doodle.exception.SlotAlreadyBookedException;
import com.example.doodle.repository.MeetingRepository;
import com.example.doodle.repository.TimeSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class MeetingService {

    private final TimeSlotRepository slotRepository;
    private final MeetingRepository meetingRepository;

    public MeetingService(TimeSlotRepository slotRepository, MeetingRepository meetingRepository) {
        this.slotRepository = slotRepository;
        this.meetingRepository = meetingRepository;
    }

    @Transactional
    public Meeting bookMeeting(UUID slotId, String title, String description, List<String> participantEmails) {
        // Row lock: a concurrent booking of the same slot blocks here until we commit,
        // then reads status=BUSY and gets SlotAlreadyBookedException (409).
        // The UNIQUE(meeting.slot_id) constraint remains as a last line of defence.
        TimeSlot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new NotFoundException("slot", slotId));

        if (slot.getSlotStatus() != TimeSlotStatus.FREE) {
            throw new SlotAlreadyBookedException(slotId);
        }

        slot.setSlotStatus(TimeSlotStatus.BUSY);

        Meeting meeting = new Meeting(slot, title, description);
        if (participantEmails != null) {
            // de-duplicate so a repeated email doesn't trip UNIQUE(meeting_id, email)
            for (String email : new LinkedHashSet<>(participantEmails)) {
                meeting.addParticipant(new Participant(meeting, email));
            }
        }

        return meetingRepository.save(meeting);
    }
}
