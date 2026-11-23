package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.MyHrChatRequest;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.MyHrChatHistoryProvenance;
import com.waypoint.service.MyHrChatService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** Present only when the Claude-first chat is switched on: waypoint.myhr.chat.enabled (MYHR_CHAT_ENABLED), off by default. */
@RestController
@ConditionalOnProperty(name = "waypoint.myhr.chat.enabled", havingValue = "true")
@RequestMapping("/api/myhr/chat")
@RequiredArgsConstructor
public class MyHrChatController {
    /** The MyHR roles every other /api/myhr endpoint accepts. */
    static final Set<UserRole> CHAT_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);

    private final MyHrChatService chatService;
    private final MyHrActorResolver actorResolver;

    /**
     * The user, role and organization come from the signed token and the user's current database
     * record; inactive, removed, password-change-pending and non-MyHR users are refused. Identity
     * fields in the request body are ignored. The conversation belongs to the login session of the
     * already-verified bearer token, identified only by a fingerprint of it.
     */
    @PostMapping
    public ApiResponse<MyHrChatResponse> chat(HttpServletRequest servletRequest,
                                               @Valid @RequestBody MyHrChatRequest request) {
        VerifiedMyHrActor actor = actorResolver.resolve(servletRequest, CHAT_ROLES);
        MyHrChatHistoryProvenance.Session session =
                MyHrChatHistoryProvenance.Session.fromAuthorizationHeader(servletRequest.getHeader("Authorization"));
        return new ApiResponse<>(true, "Chat response generated",
                chatService.chat(actor, request.message().trim(), request.history(), session));
    }
}
