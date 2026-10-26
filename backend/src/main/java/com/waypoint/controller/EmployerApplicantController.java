package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.employer.EmployerApplicantDetailResponse;
import com.waypoint.dto.employer.EmployerApplicantSummaryResponse;
import com.waypoint.dto.employer.UpdateApplicationStatusRequest;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.EmployerApplicantService;
import com.waypoint.service.ResumeStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/employer/jobs/{jobId}/applications")
@RequiredArgsConstructor
public class EmployerApplicantController {

    private final EmployerApplicantService employerApplicantService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployerApplicantSummaryResponse>>> listApplicants(
            HttpServletRequest request, @PathVariable String jobId) {

        List<EmployerApplicantSummaryResponse> applicants = employerApplicantService.listApplicants(
                authenticatedUserId(request), jobId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Job applicants retrieved successfully", applicants));
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<EmployerApplicantDetailResponse>> getApplicant(
            HttpServletRequest request,
            @PathVariable String jobId,
            @PathVariable String applicationId) {

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                authenticatedUserId(request), jobId, applicationId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Applicant details retrieved successfully", applicant));
    }

    @PatchMapping("/{applicationId}/status")
    public ResponseEntity<ApiResponse<EmployerApplicantDetailResponse>> updateApplicantStatus(
            HttpServletRequest request,
            @PathVariable String jobId,
            @PathVariable String applicationId,
            @Valid @RequestBody UpdateApplicationStatusRequest statusRequest) {

        EmployerApplicantDetailResponse applicant = employerApplicantService.updateApplicationStatus(
                authenticatedUserId(request), jobId, applicationId, statusRequest.getStatus());

        return ResponseEntity.ok(new ApiResponse<>(true, "Applicant status updated successfully", applicant));
    }

    @GetMapping("/{applicationId}/resume")
    public ResponseEntity<byte[]> getApplicantResume(
            HttpServletRequest request,
            @PathVariable String jobId,
            @PathVariable String applicationId,
            @RequestParam(defaultValue = "false") boolean download) {

        ResumeStorageService.ResumeDocument resume = employerApplicantService.getApplicantResume(
                authenticatedUserId(request), jobId, applicationId);

        ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(resume.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(resume.mediaType())
                .contentLength(resume.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resume.content());
    }

    private String authenticatedUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);
    }
}
