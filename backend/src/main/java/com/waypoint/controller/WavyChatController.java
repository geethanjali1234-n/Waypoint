package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.WavyChatRequest;
import com.waypoint.dto.myhr.WavyChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.WavyChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/myhr/wavy")
@RequiredArgsConstructor
public class WavyChatController {
    private static final Set<UserRole> ROLES = Set.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN);
    private final CurrentUserResolver currentUserResolver;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final WavyChatService chat;

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities(HttpServletRequest request) {
        authenticatedUserService.require(currentUserResolver.resolveUserId(request), ROLES, false);
        return new ApiResponse<>(true, "Wavy capabilities", Map.of("aiAvailable", chat.isConfigured(), "readOnly", true));
    }

    @PostMapping("/chat")
    public ApiResponse<WavyChatResponse> reply(HttpServletRequest request, @Valid @RequestBody WavyChatRequest body) {
        var actor = authenticatedUserService.require(currentUserResolver.resolveUserId(request), ROLES, false);
        return new ApiResponse<>(true, "Wavy response", chat.reply(actor, body));
    }
}
