package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.TimesheetReviewRequest;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.dto.myhr.TimesheetUpsertRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.TimesheetPdfService;
import com.waypoint.service.TimesheetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waypoint.dto.myhr.TimesheetSettingsResponse;
import com.waypoint.enums.PayrollPeriod;
import org.springframework.format.annotation.DateTimeFormat;
import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import com.waypoint.enums.DocumentType;
import com.waypoint.exception.BusinessException;
import com.waypoint.service.MyHrDocumentService;
import com.waypoint.service.TimesheetDocumentParserService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/myhr/timesheets")
@RequiredArgsConstructor
public class MyHrTimesheetController {

    private final CurrentUserResolver currentUserResolver;
    private final TimesheetService timesheetService;
    private final TimesheetPdfService timesheetPdfService;
    private final TimesheetDocumentParserService timesheetDocumentParserService;
    private final Optional<MyHrDocumentService> myHrDocumentService;

    @PostMapping(value = "/parse-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ParsedTimesheetResponse> parseDocument(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dateOrder", defaultValue = "MDY") String dateOrder,
            @RequestParam(value = "ocrText", required = false) String ocrText) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        String documentId = null;
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "timesheet";
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Failed to read uploaded file");
        }
        MyHrDocumentService documents = myHrDocumentService.orElseThrow(
                () -> new BusinessException("Document storage is not configured"));
        var uploaded = documents.upload(actorUserId, file, DocumentType.TIMESHEET_SUPPORTING_DOCUMENT);
        if (uploaded == null || uploaded.id() == null || uploaded.id().isBlank()) {
            throw new BusinessException("The timesheet document could not be stored");
        }
        documentId = uploaded.id();
        ParsedTimesheetResponse parsed = timesheetDocumentParserService.parse(bytes, fileName, documentId, dateOrder, ocrText);
        return new ApiResponse<>(true, "Document extraction completed", parsed);
    }

    @GetMapping("/settings")
    public ApiResponse<TimesheetSettingsResponse> getSettings(HttpServletRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        PayrollPeriod period = timesheetService.getPayrollPeriodForOrganization(actorUserId);
        return new ApiResponse<>(true, "Timesheet settings loaded",
                new TimesheetSettingsResponse(period));
    }

    @GetMapping
    public ApiResponse<List<TimesheetResponse>> list(HttpServletRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Timesheets loaded", timesheetService.listForUser(actorUserId));
    }

    @GetMapping("/period")
    public ApiResponse<TimesheetResponse> getPeriod(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Consolidated pay period loaded",
                timesheetService.getConsolidatedPeriodForUser(actorUserId, start, end));
    }

    @GetMapping("/period-pdf")
    public ResponseEntity<byte[]> downloadPeriodPdf(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        TimesheetResponse timesheet = timesheetService.getConsolidatedPeriodForUser(actorUserId, start, end);
        byte[] pdf = timesheetPdfService.render(timesheet);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(timesheetPdfService.fileName(timesheet))
                                .build().toString())
                .body(pdf);
    }

    @GetMapping("/{timesheetId}")
    public ApiResponse<TimesheetResponse> get(
            HttpServletRequest request,
            @PathVariable String timesheetId) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Timesheet loaded",
                timesheetService.getForUser(actorUserId, timesheetId));
    }

    @GetMapping("/{timesheetId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            HttpServletRequest request,
            @PathVariable String timesheetId) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        TimesheetResponse timesheet = timesheetService.getForUser(actorUserId, timesheetId);
        byte[] pdf = timesheetPdfService.render(timesheet);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(timesheetPdfService.fileName(timesheet))
                                .build().toString())
                .body(pdf);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TimesheetResponse>> create(
            HttpServletRequest request,
            @Valid @RequestBody TimesheetUpsertRequest payload) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        TimesheetResponse response = timesheetService.create(actorUserId, payload);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Timesheet saved", response));
    }

    @PutMapping("/{timesheetId}")
    public ApiResponse<TimesheetResponse> update(
            HttpServletRequest request,
            @PathVariable String timesheetId,
            @Valid @RequestBody TimesheetUpsertRequest payload) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Timesheet updated",
                timesheetService.update(actorUserId, timesheetId, payload));
    }

    @PatchMapping("/{timesheetId}/review")
    public ApiResponse<TimesheetResponse> review(
            HttpServletRequest request,
            @PathVariable String timesheetId,
            @Valid @RequestBody TimesheetReviewRequest payload) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Timesheet reviewed",
                timesheetService.review(actorUserId, timesheetId, payload));
    }
}
