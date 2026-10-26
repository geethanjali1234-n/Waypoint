package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.DocumentUploadResponse;
import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import com.waypoint.enums.DocumentType;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.DocumentService;
import com.waypoint.service.MyHrDocumentService;
import com.waypoint.service.TimesheetDocumentParserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/myhr/documents")
@RequiredArgsConstructor
@ConditionalOnBean(MyHrDocumentService.class)
public class MyHrDocumentController {

    private final CurrentUserResolver currentUserResolver;
    private final MyHrDocumentService myHrDocumentService;
    private final TimesheetDocumentParserService timesheetDocumentParserService;

    @PostMapping("/{documentId}/parse-timesheet")
    public ApiResponse<ParsedTimesheetResponse> parseTimesheet(
            HttpServletRequest request, @PathVariable String documentId,
            @RequestParam(defaultValue = "MDY") String dateOrder,
            @RequestParam(value = "ocrText", required = false) String ocrText) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        DocumentService.DocumentContent content = myHrDocumentService.download(actorUserId, documentId);
        if (!actorUserId.equals(content.metadata().getOwnerUserId())
                || content.metadata().getDocumentType() != DocumentType.TIMESHEET_SUPPORTING_DOCUMENT) {
            throw new ForbiddenException("Only your own timesheet documents can be imported");
        }
        return new ApiResponse<>(true, "Document extraction completed",
                timesheetDocumentParserService.parse(content.content(), content.metadata().getFileName(), documentId, dateOrder, ocrText));
    }

    public ApiResponse<ParsedTimesheetResponse> parseTimesheet(
            HttpServletRequest request, String documentId, String dateOrder) {
        return parseTimesheet(request, documentId, dateOrder, null);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> upload(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", required = false) DocumentType documentType) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        DocumentUploadResponse response = myHrDocumentService.upload(actorUserId, file, documentType);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Document uploaded", response));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<byte[]> download(
            HttpServletRequest request,
            @PathVariable String documentId) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        DocumentService.DocumentContent content = myHrDocumentService.download(actorUserId, documentId);
        MediaType responseType = mediaType(content.contentType(), content.metadata().getFileName());
        return ResponseEntity.ok()
                .contentType(responseType)
                .contentLength(content.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(downloadFileName(content.metadata().getFileName(), responseType))
                                .build().toString())
                .body(content.content());
    }

    private MediaType mediaType(String raw, String fileName) {
        MediaType parsed = parseMediaType(raw);
        if (parsed != null && !MediaType.APPLICATION_OCTET_STREAM.includes(parsed)) {
            return parsed;
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
            if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
            if (lower.endsWith(".csv")) return MediaType.parseMediaType("text/csv");
            if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        }
        return parsed != null ? parsed : MediaType.APPLICATION_OCTET_STREAM;
    }

    private MediaType parseMediaType(String raw) {
        try {
            return (raw != null && !raw.isBlank()) ? MediaType.parseMediaType(raw) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String downloadFileName(String originalFileName, MediaType mediaType) {
        String fileName = originalFileName == null || originalFileName.isBlank() ? "supporting-document" : originalFileName;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (MediaType.TEXT_PLAIN.includes(mediaType) && !lower.endsWith(".txt")) {
            return fileName + ".txt";
        }
        if (MediaType.APPLICATION_PDF.includes(mediaType) && !lower.endsWith(".pdf")) {
            return fileName + ".pdf";
        }
        return fileName;
    }
}
