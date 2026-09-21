package com.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.booking.support.AbstractIntegrationTest;
import com.booking.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthAndAccessControlIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resetDatabase();
        createUser("member@test.dev", "password123", Role.USER);
        createUser("boss@test.dev", "password123", Role.ADMIN);
    }

    @Test
    @DisplayName("registering an email twice returns 409")
    void duplicateRegistrationRejected() throws Exception {
        String body = """
                {"email":"fresh@test.dev","password":"password123","fullName":"Fresh User"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("wrong password returns 401 without revealing whether the email exists")
    void badCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@test.dev\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ghost@test.dev\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("protected endpoints reject anonymous callers with a JSON 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("public endpoints stay open")
    void publicEndpointsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the web UI and its assets are public, but other unknown paths are not")
    void webUiIsPublic() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Event Booking")));
        mockMvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/app.css")).andExpect(status().isOk());

        // Opening up the UI must not open up everything else.
        mockMvc.perform(get("/some/other/path")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("creating an event needs ADMIN: USER gets 403, ADMIN gets 201")
    void roleBasedAccess() throws Exception {
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "Role Test",
                "venue", "Somewhere",
                "startsAt", Instant.now().plus(20, ChronoUnit.DAYS).toString(),
                "totalSeats", 5));

        mockMvc.perform(post("/api/v1/events")
                .header("Authorization", "Bearer " + login("member@test.dev"))
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/events")
                .header("Authorization", "Bearer " + login("boss@test.dev"))
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("refresh rotates the token; the old one stops working")
    void refreshTokenRotation() throws Exception {
        JsonNode tokens = loginFull("member@test.dev");
        String originalRefresh = tokens.get("refreshToken").asText();

        String refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + originalRefresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newRefresh = objectMapper.readTree(refreshed).get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(originalRefresh);

        // Replaying the old one must fail.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + originalRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String login(String email) throws Exception {
        return loginFull(email).get("accessToken").asText();
    }

    private JsonNode loginFull(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
