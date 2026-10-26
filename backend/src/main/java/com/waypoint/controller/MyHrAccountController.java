package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.CreateEmployeeAccountRequest;
import com.waypoint.dto.myhr.CreateHrAccountRequest;
import com.waypoint.dto.myhr.CreatedAccountResult;
import com.waypoint.dto.myhr.ReportingManagerOption;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.MyHrAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/myhr/accounts")
@RequiredArgsConstructor
public class MyHrAccountController {

    private final MyHrAccountService myHrAccountService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping("/reporting-managers")
    public ApiResponse<List<ReportingManagerOption>> getEligibleReportingManagers(
            HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Eligible reporting managers",
                myHrAccountService.getEligibleReportingManagers(actorUserId));
    }

    @PostMapping("/hr")
    public ResponseEntity<ApiResponse<CreatedAccountResult>> createHr(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateHrAccountRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        CreatedAccountResult result = myHrAccountService.createHr(actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "HR account created successfully", result));
    }

    @PostMapping("/employees")
    public ResponseEntity<ApiResponse<CreatedAccountResult>> createEmployee(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateEmployeeAccountRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        CreatedAccountResult result = myHrAccountService.createEmployee(actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Employee account created successfully", result));
    }
}
