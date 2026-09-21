package com.booking.event;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.booking.common.NotFoundException;
import com.booking.security.JwtAuthenticationFilter;
import com.booking.security.RestAccessDeniedHandler;
import com.booking.security.RestAuthEntryPoint;
import com.booking.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice only: the controller, its validation, and the global exception
 * handler. Security is deliberately excluded here - authorization rules are
 * covered end-to-end in AuthAndAccessControlIT, against a real token.
 */
@WebMvcTest(controllers = EventController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
                        RestAuthEntryPoint.class, RestAccessDeniedHandler.class}))
@AutoConfigureMockMvc(addFilters = false)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @Test
    @DisplayName("invalid create request returns 400 with per-field errors")
    void rejectsInvalidPayload() throws Exception {
        String badJson = """
                {
                  "name": "",
                  "venue": "Somewhere",
                  "startsAt": "2020-01-01T10:00:00Z",
                  "totalSeats": 0
                }
                """;

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.startsAt").exists())
                .andExpect(jsonPath("$.fieldErrors.totalSeats").exists());
    }

    @Test
    @DisplayName("unknown event returns the standard 404 error body")
    void returnsNotFound() throws Exception {
        when(eventService.getById(anyLong())).thenThrow(new NotFoundException("Event", 99L));

        mockMvc.perform(get("/api/v1/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/events/99"))
                .andExpect(jsonPath("$.message").value("Event with id 99 was not found"));
    }

    @Test
    @DisplayName("a path variable of the wrong type returns 400, not 500")
    void rejectsMalformedPathVariable() throws Exception {
        mockMvc.perform(get("/api/v1/events/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Parameter 'id' has an invalid value"));
    }
}
