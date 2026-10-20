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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    // ---- helpers -------------------------------------------------------------------

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
