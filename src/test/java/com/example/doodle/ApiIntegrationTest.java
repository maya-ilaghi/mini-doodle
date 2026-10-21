package com.example.doodle;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests through the HTTP layer against a real PostgreSQL, so the
 * exclusion constraint, row locks and error mapping are exercised for real.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void dockerAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping API integration tests");
    }

    @Autowired
    MockMvc mvc;

    // ---- ownership -----------------------------------------------------------------

    @Test
    void cannotModifyAnotherUsersSlot() throws Exception {
        String alice = createUser();
        String bob = createUser();
        String aliceSlot = createSlot(alice, "2026-11-03T09:00:00Z", "2026-11-03T09:30:00Z");

        mvc.perform(patch("/api/v1/users/{u}/slots/{s}", bob, aliceSlot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson("2026-11-03T12:00:00Z", "2026-11-03T12:30:00Z")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotDeleteAnotherUsersSlot() throws Exception {
        String alice = createUser();
        String bob = createUser();
        String aliceSlot = createSlot(alice, "2026-11-03T10:00:00Z", "2026-11-03T10:30:00Z");

        mvc.perform(delete("/api/v1/users/{u}/slots/{s}", bob, aliceSlot))
                .andExpect(status().isNotFound());

        // still deletable by its owner, proving it was untouched
        mvc.perform(delete("/api/v1/users/{u}/slots/{s}", alice, aliceSlot))
                .andExpect(status().isNoContent());
    }

    // ---- error mapping -------------------------------------------------------------

    @Test
    void duplicateEmail_is409_not500() throws Exception {
        String json = "{\"email\":\"dup-%s@example.com\",\"name\":\"Dup\"}".formatted(UUID.randomUUID());
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("A user with this email already exists")));
    }

    @Test
    void overlappingSlot_is409() throws Exception {
        String user = createUser();
        createSlot(user, "2026-11-04T09:00:00Z", "2026-11-04T10:00:00Z");

        mvc.perform(post("/api/v1/users/{u}/slots", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson("2026-11-04T09:30:00Z", "2026-11-04T10:30:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Slot overlaps")));
    }

    @Test
    void backToBackSlots_doNotOverlap() throws Exception {
        String user = createUser();
        createSlot(user, "2026-11-04T11:00:00Z", "2026-11-04T11:30:00Z");
        createSlot(user, "2026-11-04T11:30:00Z", "2026-11-04T12:00:00Z");   // asserts 201 inside
    }

    @Test
    void availabilityWithInvertedWindow_is400() throws Exception {
        String user = createUser();
        mvc.perform(get("/api/v1/users/{u}/slots/availability", user)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-04T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'from' must be before 'to'"));
    }

    @Test
    void bookingTheSameSlotTwice_is409() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-04T14:00:00Z", "2026-11-04T14:30:00Z");

        bookMeeting(slot).andExpect(status().isCreated());
        bookMeeting(slot)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Slot is already booked: " + slot));
    }

    @Test
    void duplicateParticipantEmails_areCollapsed() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-04T15:00:00Z", "2026-11-04T15:30:00Z");

        mvc.perform(post("/api/v1/slots/{s}/meeting", slot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sync\",\"participants\":[\"bob@example.com\",\"bob@example.com\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participants.length()").value(1));
    }

    // ---- booked slots --------------------------------------------------------------

    @Test
    void bookedSlotCannotBeMovedOrDeleted() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-06T09:00:00Z", "2026-11-06T09:30:00Z");
        bookMeeting(slot).andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/users/{u}/slots/{s}", user, slot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson("2026-11-06T13:00:00Z", "2026-11-06T13:30:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Slot has a meeting")));

        mvc.perform(delete("/api/v1/users/{u}/slots/{s}", user, slot))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingAMeetingFreesTheSlot() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-06T10:00:00Z", "2026-11-06T10:30:00Z");
        bookMeeting(slot).andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/slots/{s}/meeting", slot))
                .andExpect(status().isNoContent());

        // free again: can be re-booked, and once cancelled again, edited
        bookMeeting(slot).andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/slots/{s}/meeting", slot))
                .andExpect(status().isNoContent());
        mvc.perform(patch("/api/v1/users/{u}/slots/{s}", user, slot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson("2026-11-06T16:00:00Z", "2026-11-06T16:30:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FREE"));
    }

    @Test
    void cancellingWhenNoMeetingExists_is404() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-06T11:00:00Z", "2026-11-06T11:30:00Z");

        mvc.perform(delete("/api/v1/slots/{s}/meeting", slot))
                .andExpect(status().isNotFound());
    }

    // ---- marking slots busy / free -------------------------------------------------

    @Test
    void slotCanBeMarkedBusyAndFreeAgain() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-09T09:00:00Z", "2026-11-09T09:30:00Z");

        setStatus(user, slot, "BUSY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUSY"));
        bookMeeting(slot)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Slot is marked busy: " + slot));

        setStatus(user, slot, "FREE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FREE"));
        bookMeeting(slot).andExpect(status().isCreated());
    }

    @Test
    void statusOfABookedSlotCannotBeChanged() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-09T10:00:00Z", "2026-11-09T10:30:00Z");
        bookMeeting(slot).andExpect(status().isCreated());

        setStatus(user, slot, "FREE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Slot has a meeting")));
    }

    @Test
    void cannotChangeStatusOfAnotherUsersSlot() throws Exception {
        String alice = createUser();
        String bob = createUser();
        String aliceSlot = createSlot(alice, "2026-11-09T11:00:00Z", "2026-11-09T11:30:00Z");

        setStatus(bob, aliceSlot, "BUSY").andExpect(status().isNotFound());
    }

    @Test
    void manuallyBusySlotCanStillBeMovedAndDeleted() throws Exception {
        String user = createUser();
        String slot = createSlot(user, "2026-11-09T12:00:00Z", "2026-11-09T12:30:00Z");
        setStatus(user, slot, "BUSY").andExpect(status().isOk());

        // no meeting on it, so it's just blocked time and stays editable
        mvc.perform(patch("/api/v1/users/{u}/slots/{s}", user, slot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson("2026-11-09T14:00:00Z", "2026-11-09T14:30:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUSY"));
        mvc.perform(delete("/api/v1/users/{u}/slots/{s}", user, slot))
                .andExpect(status().isNoContent());
    }

    // ---- time windows --------------------------------------------------------------

    @Test
    void windowQueriesReturnSlotsThatOverlapTheWindow() throws Exception {
        String user = createUser();
        // window under test: [10:00, 12:00)
        String startsBefore = createSlot(user, "2026-11-10T09:30:00Z", "2026-11-10T10:30:00Z"); // runs into it
        String inside       = createSlot(user, "2026-11-10T10:30:00Z", "2026-11-10T11:00:00Z");
        String touchesEnd   = createSlot(user, "2026-11-10T12:00:00Z", "2026-11-10T12:30:00Z"); // starts at `to`
        setStatus(user, inside, "BUSY").andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/{u}/slots/availability", user)
                        .param("from", "2026-11-10T10:00:00Z")
                        .param("to", "2026-11-10T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.free.length()").value(1))
                .andExpect(jsonPath("$.free[0].id").value(startsBefore))
                .andExpect(jsonPath("$.busy.length()").value(1))
                .andExpect(jsonPath("$.busy[0].id").value(inside))
                .andExpect(jsonPath("$.free[*].id", not(hasItem(touchesEnd))));

        mvc.perform(get("/api/v1/users/{u}/slots", user)
                        .param("from", "2026-11-10T10:00:00Z")
                        .param("to", "2026-11-10T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(startsBefore))   // sorted by start time
                .andExpect(jsonPath("$.content[1].id").value(inside));
    }

    // ---- helpers -------------------------------------------------------------------

    ResultActions setStatus(String userId, String slotId, String status) throws Exception {
        return mvc.perform(patch("/api/v1/users/{u}/slots/{s}/status", userId, slotId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\"}".formatted(status)));
    }

    ResultActions bookMeeting(String slotId) throws Exception {
        return mvc.perform(post("/api/v1/slots/{s}/meeting", slotId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Sync\",\"description\":\"weekly\",\"participants\":[\"bob@example.com\"]}"));
    }

    String createUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String body = mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Test\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    String createSlot(String userId, String start, String end) throws Exception {
        String body = mvc.perform(post("/api/v1/users/{u}/slots", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson(start, end)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FREE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    static String slotJson(String start, String end) {
        return "{\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(start, end);
    }
}
