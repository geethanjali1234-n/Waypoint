package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/", "/api", "/api/"})
    public ApiResponse<Map<String, String>> apiStatus() {
        return new ApiResponse<>(true, "Waypoint Backend is running.", Map.of("health", "/health"));
    }

    @GetMapping("/health")
    public String health() {
        return "Waypoint Backend is Running Successfully!";
    }

}
