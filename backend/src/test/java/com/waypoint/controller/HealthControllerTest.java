package com.waypoint.controller;

import com.waypoint.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api", "/api/"})
    void backendLandingUrlsReturnAPublicSuccessResponse(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Waypoint Backend is running."))
                .andExpect(jsonPath("$.data.health").value("/health"));
    }

    @Test
    void unknownRouteReturns404InsteadOfAnUnexpectedServerError() throws Exception {
        mockMvc.perform(get("/api/unknown-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("The requested endpoint was not found."));
    }

    @Test
    void publicLandingPageDoesNotMakeProtectedEndpointsPublic() throws Exception {
        mockMvc.perform(get("/api/myhr/wavy/capabilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthReturns200WithExpectedMessage() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("Waypoint Backend is Running Successfully!"));
    }
}
