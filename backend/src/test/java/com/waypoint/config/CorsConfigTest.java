package com.waypoint.config;

import com.waypoint.controller.FileServingController;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uploaded files (profile pictures, organization logos) are served from
 * "/uploads/**", outside the "/api/**" prefix CorsConfig used to cover alone.
 * A browser <img> tag renders a cross-origin image fine without CORS, but a
 * fetch() call - e.g. OfferLetterPreparePage pulling the organization logo's
 * bytes to embed in a generated PDF - is blocked from reading the response
 * unless it carries Access-Control-Allow-Origin. This guards against that
 * regression.
 */
@WebMvcTest(FileServingController.class)
class CorsConfigTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean FileStorageService fileStorageService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    @Test
    void allowsCrossOriginReadsOfUploadedFilesFromTheFrontendDevOrigin() throws Exception {
        mockMvc.perform(options("/uploads/gridfs/507f1f77bcf86cd799439011/logo.png")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
