package com.example.doodle;

import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.User;
import com.example.doodle.service.MeetingService;
import com.example.doodle.service.SlotService;
import com.example.doodle.service.UserService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@SpringBootTest
@Testcontainers
class BookingConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");


    @BeforeAll
    static void dockerAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping concurrency integration test");
    }


    @Autowired
    UserService userService;
    @Autowired
    SlotService slotService;
    @Autowired
    MeetingService meetingService;

    @Test
    void twoConcurrentBookingsOnlyOneSucceeds() throws Exception {
        // given: a user with one free slot
        User user = userService.createUser("race@example.com", "Race");
        TimeSlot slot = slotService.createSlot(user.getId(),
                Instant.parse("2026-08-10T10:00:00Z"),
                Instant.parse("2026-08-10T10:30:00Z"));

        // when: two threads try to book the SAME slot at once
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> booking = () -> {
            start.await();                     // both wait, then fire together
            try {
                meetingService.bookMeeting(slot.getId(), "Sync", null, List.of());
                return true;                   // succeeded
            } catch (Exception e) {
                return false;                  // lost the race
            }
        };
        var f1 = pool.submit(booking);
        var f2 = pool.submit(booking);
        start.countDown();                     // release both simultaneously

        // then: exactly one wins
        int successes = (f1.get() ? 1 : 0) + (f2.get() ? 1 : 0);
        assertEquals(1, successes, "exactly one booking must succeed");
    }
}
