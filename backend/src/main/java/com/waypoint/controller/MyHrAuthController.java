package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.MyHrAuthenticatedUserResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/myhr/auth")
@RequiredArgsConstructor
public class MyHrAuthController {

    private static final Set<UserRole> MY_HR_ROLES =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);

    private final CurrentUserResolver currentUserResolver;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    @GetMapping("/me")
    public ApiResponse<MyHrAuthenticatedUserResponse> currentUser(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser user =
                authenticatedUserService.require(userId, MY_HR_ROLES, true);

        MyHrAuthenticatedUserResponse response = new MyHrAuthenticatedUserResponse(
                user.userId(), user.loginId(), user.email(), user.role(), user.organizationId(),
                user.accountStatus(), user.mustChangePassword());
        return new ApiResponse<>(true, "Authenticated My HR user", response);
    }
}
