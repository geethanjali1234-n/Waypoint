package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.ChangeReportingManagerRequest;
import com.waypoint.dto.myhr.EligibleReportingManagerResponse;
import com.waypoint.dto.myhr.EmployeeDetailResponse;
import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.UpdateEmployeeAccountStatusRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.EmployeeManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/myhr/employees")
@RequiredArgsConstructor
public class EmployeeManagementController {

    private final EmployeeManagementService employeeManagementService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ApiResponse<List<EmployeeSummaryResponse>> list(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Employees retrieved successfully",
                employeeManagementService.list(actorUserId));
    }

    @GetMapping("/me")
    public ApiResponse<MyHrOwnProfileResponse> myProfile(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Your profile retrieved successfully",
                employeeManagementService.getOwnDetail(actorUserId));
    }

    /** The authenticated user's own signature (My HR Profile, any role) - always the caller's, never a client-supplied user. */
    @PostMapping("/me/signature")
    public ApiResponse<MyHrOwnProfileResponse> uploadMySignature(
            HttpServletRequest servletRequest,
            @RequestParam("file") MultipartFile file) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Your signature was saved",
                employeeManagementService.uploadOwnSignature(actorUserId, file));
    }

    @DeleteMapping("/me/signature")
    public ApiResponse<MyHrOwnProfileResponse> removeMySignature(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Your signature was removed",
                employeeManagementService.removeOwnSignature(actorUserId));
    }

    @GetMapping("/reporting-managers")
    public ApiResponse<List<EligibleReportingManagerResponse>> reportingManagers(
            HttpServletRequest servletRequest,
            @RequestParam(name = "employeeUserId", required = false) String employeeUserId) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Eligible reporting managers retrieved successfully",
                employeeManagementService.eligibleReportingManagers(actorUserId, employeeUserId));
    }

    @GetMapping("/{employeeUserId}")
    public ApiResponse<EmployeeDetailResponse> detail(
            HttpServletRequest servletRequest,
            @PathVariable String employeeUserId) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Employee retrieved successfully",
                employeeManagementService.getDetail(actorUserId, employeeUserId));
    }

    @PatchMapping("/{employeeUserId}/reporting-manager")
    public ApiResponse<EmployeeDetailResponse> changeReportingManager(
            HttpServletRequest servletRequest,
            @PathVariable String employeeUserId,
            @Valid @RequestBody ChangeReportingManagerRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Reporting manager updated successfully",
                employeeManagementService.changeReportingManager(actorUserId, employeeUserId, request));
    }

    @PatchMapping("/{employeeUserId}/status")
    public ApiResponse<EmployeeDetailResponse> updateStatus(
            HttpServletRequest servletRequest,
            @PathVariable String employeeUserId,
            @Valid @RequestBody UpdateEmployeeAccountStatusRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Account status updated successfully",
                employeeManagementService.updateAccountStatus(
                        actorUserId, employeeUserId, request.getStatus(), request.getReassignToUserId()));
    }
}
