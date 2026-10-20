package com.example.doodle;

import com.example.doodle.domain.TimeSlot;
import com.example.doodle.domain.TimeSlotStatus;
import com.example.doodle.domain.User;
import com.example.doodle.exception.SlotAlreadyBookedException;
import com.example.doodle.repository.TimeSlotRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@Tag("integration")
@SpringBootTest
@Testcontainers
class BookingConcurrencyTest {

    private static final int THREADS = 8;

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
    @Autowired
    TimeSlotRepository slotRepository;

    @Test
    void concurrentBookingsOfOneSlot_exactlyOneWins_othersGetSlotAlreadyBooked() throws Exception {
        // given: a user with one free slot
        User user = userService.createUser("race@example.com", "Race");
        TimeSlot slot = slotService.createSlot(user.getId(),
                Instant.parse("2026-11-02T10:00:00Z"),
                Instant.parse("2026-11-02T10:30:00Z"));

        // when: several threads try to book the SAME slot at once
        var pool = Executors.newFixedThreadPool(THREADS);
        var start = new CountDownLatch(1);
        Callable<Void> booking = () -> {
            start.await();                     // all wait, then fire together
            meetingService.bookMeeting(slot.getId(), "Sync", null, List.of());
            return null;
        };
        List<Future<Void>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(booking));
        }
        start.countDown();                     // release all simultaneously

        // then: exactly one wins, and every loser fails with the domain conflict
        // (which the API maps to 409) — not a database error that would surface as 500
        int successes = 0;
        for (Future<Void> f : results) {
            try {
                f.get();
                successes++;
            } catch (ExecutionException e) {
                assertInstanceOf(SlotAlreadyBookedException.class, e.getCause(),
                        "losing bookings must be rejected as already booked");
            }
        }
        pool.shutdown();

        assertEquals(1, successes, "exactly one booking must succeed");
        assertEquals(TimeSlotStatus.BUSY,
                slotRepository.findById(slot.getId()).orElseThrow().getSlotStatus());
    }
}
