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
        TimeSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("slot", slotId));

        if (slot.getSlotStatus() != TimeSlotStatus.FREE) {   // sequential guard
            throw new SlotAlreadyBookedException(slotId);
        }

        slot.setSlotStatus(TimeSlotStatus.BUSY);              // @Version insured the concurrent race

        Meeting meeting = new Meeting(slot, title, description);
        if (participantEmails != null) {
            for (String email : participantEmails) {
                meeting.addParticipant(new Participant(meeting, email));
            }
        }

        return meetingRepository.save(meeting);
    }
}