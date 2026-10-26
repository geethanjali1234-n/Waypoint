package com.waypoint.controller;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentType;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.DocumentService;
import com.waypoint.service.MyHrDocumentService;
import com.waypoint.service.TimesheetDocumentParserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyHrDocumentControllerTest {
    private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    private final MyHrDocumentService documents = mock(MyHrDocumentService.class);
    private final MyHrDocumentController controller = new MyHrDocumentController(resolver, documents, new TimesheetDocumentParserService());

    @Test
    void parsesTheAuthenticatedStoredDocumentThroughHttp() throws Exception {
        when(resolver.resolveUserId(any())).thenReturn("employee-1");
        when(documents.download("employee-1", "doc-1")).thenReturn(documentContent("employee-1"));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/myhr/documents/doc-1/parse-timesheet").param("dateOrder", "DMY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportingDocumentId").value("doc-1"))
                .andExpect(jsonPath("$.data.periodStartDate").value("2025-01-02"))
                .andExpect(jsonPath("$.data.entries[0].hoursWorked").value(8));
        verify(documents).download("employee-1", "doc-1");
    }

    @Test
    void reviewerCannotImportAnotherEmployeesDocumentAsTheirOwn() {
        when(resolver.resolveUserId(any())).thenReturn("hr-1");
        when(documents.download("hr-1", "doc-1")).thenReturn(documentContent("employee-1"));
        assertThatThrownBy(() -> controller.parseTimesheet(null, "doc-1", "MDY"))
                .isInstanceOf(com.waypoint.exception.ForbiddenException.class);
    }

    @Test
    void downloadUsesActualTextContentTypeBeforeImageFileExtension() throws Exception {
        byte[] bytes = "Waypoint Certified Supporting Document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StoredDocument metadata = StoredDocument.builder().id("doc-jpeg-fallback")
                .ownerUserId("employee-1")
                .fileName("WhatsApp_Image_2026-09-21.jpeg")
                .documentType(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT)
                .build();
        when(resolver.resolveUserId(any())).thenReturn("employee-1");
        when(documents.download("employee-1", "doc-jpeg-fallback"))
                .thenReturn(new DocumentService.DocumentContent(metadata, bytes, "text/plain", bytes.length));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/myhr/documents/doc-jpeg-fallback"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"WhatsApp_Image_2026-09-21.jpeg.txt\""))
                .andExpect(content().bytes(bytes));
    }

    private DocumentService.DocumentContent documentContent(String owner) {
        byte[] bytes = "Date,Hours\n02/01/2025,8".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new DocumentService.DocumentContent(StoredDocument.builder().id("doc-1")
                .ownerUserId(owner).fileName("company.csv")
                .documentType(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT).build(), bytes, "text/csv", bytes.length);
    }
}
