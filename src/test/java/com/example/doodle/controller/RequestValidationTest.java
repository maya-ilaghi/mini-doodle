package com.example.doodle.controller;

import com.example.doodle.service.MeetingService;
import com.example.doodle.service.SlotService;
import com.example.doodle.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer only: no database, no Docker, runs in the default build.
 * Invalid input must be rejected with 400 before it ever reaches a service.
 */
@WebMvcTest(controllers = {UserController.class, SlotController.class, MeetingController.class})
class RequestValidationTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    UserService userService;
    @MockBean
    SlotService slotService;
    @MockBean
    MeetingService meetingService;

    private final UUID userId = UUID.randomUUID();
    private final UUID slotId = UUID.randomUUID();

    @Test
    void slotWithoutEndTime_is400() throws Exception {
        mvc.perform(post("/api/v1/users/{u}/slots", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-11-02T10:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details", hasItem("endTime: must not be null")));
        verifyNoInteractions(slotService);
    }

    @Test
    void slotEndingBeforeItStarts_is400_notAnOverlap409() throws Exception {
        mvc.perform(post("/api/v1/users/{u}/slots", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-11-02T11:00:00Z\",\"endTime\":\"2026-11-02T10:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("endAfterStart: endTime must be after startTime")));
        verifyNoInteractions(slotService);
    }

    @Test
    void zeroLengthSlot_is400() throws Exception {
        mvc.perform(patch("/api/v1/users/{u}/slots/{s}", userId, slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-11-02T10:00:00Z\",\"endTime\":\"2026-11-02T10:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(slotService);
    }

    @Test
    void unparseableTimestamp_is400() throws Exception {
        mvc.perform(post("/api/v1/users/{u}/slots", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"tomorrow\",\"endTime\":\"2026-11-02T10:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void invalidUuidInPath_is400() throws Exception {
        mvc.perform(get("/api/v1/users/{u}/slots/availability", "not-a-uuid")
                        .param("from", "2026-11-02T00:00:00Z")
                        .param("to", "2026-11-03T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'userId'"));
    }

    @Test
    void missingQueryParameter_is400() throws Exception {
        mvc.perform(get("/api/v1/users/{u}/slots/availability", userId)
                        .param("from", "2026-11-02T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userWithInvalidEmail_is400() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"name\":\"Ada\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("email: must be a well-formed email address")));
        verifyNoInteractions(userService);
    }

    @Test
    void userWithBlankName_is400() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("name: must not be blank")));
        verifyNoInteractions(userService);
    }

    @Test
    void meetingWithoutTitle_is400() throws Exception {
        mvc.perform(post("/api/v1/slots/{s}/meeting", slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"weekly\",\"participants\":[\"bob@example.com\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("title: must not be blank")));
        verifyNoInteractions(meetingService);
    }

    @Test
    void meetingWithInvalidParticipantEmail_is400() throws Exception {
        mvc.perform(post("/api/v1/slots/{s}/meeting", slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sync\",\"participants\":[\"bob\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(meetingService);
    }

    @Test
    void statusUpdateWithoutStatus_is400() throws Exception {
        mvc.perform(patch("/api/v1/users/{u}/slots/{s}/status", userId, slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("status: must not be null")));
        verifyNoInteractions(slotService);
    }

    @Test
    void statusUpdateWithUnknownStatus_is400() throws Exception {
        mvc.perform(patch("/api/v1/users/{u}/slots/{s}/status", userId, slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        verifyNoInteractions(slotService);
    }
}
