package com.waypoint.service;

import com.waypoint.dto.myhr.DocumentUploadResponse;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.StorageProvider;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHrDocumentServiceTest {

    @Mock
    private DocumentService documentService;

    @Mock
    private MyHrAuthenticatedUserService authenticatedUserService;

    private MyHrDocumentService service;

    @BeforeEach
    void setUp() {
        service = new MyHrDocumentService(documentService, authenticatedUserService);
    }

    private void authenticateAs(String userId, UserRole role, String organizationId) {
        lenient().when(authenticatedUserService.require(eq(userId), any(), eq(false)))
                .thenReturn(new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                        userId, "TD003", userId + "@waypoint.local", role, organizationId,
                        AccountStatus.ACTIVE, false));
    }

    @Test
    void uploadStoresThroughDocumentServiceWithMyHrContext() {
        authenticateAs("employee-1", UserRole.EMPLOYEE, "org-1");
        when(documentService.store(any())).thenAnswer(invocation -> {
            DocumentUploadRequest request = invocation.getArgument(0);
            return StoredDocument.builder()
                    .id("document-1")
                    .fileName(request.fileName())
                    .contentType(request.contentType())
                    .fileSize((long) request.content().length)
                    .documentType(request.documentType())
                    .storageProvider(StorageProvider.R2)
                    .build();
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "August hours.pdf", "application/pdf",
                "hours".getBytes(StandardCharsets.UTF_8));

        DocumentUploadResponse response = service.upload("employee-1", file, null);

        assertThat(response.id()).isEqualTo("document-1");
        assertThat(response.documentType()).isEqualTo("TIMESHEET_SUPPORTING_DOCUMENT");

        ArgumentCaptor<DocumentUploadRequest> captor = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documentService).store(captor.capture());
        DocumentUploadRequest sent = captor.getValue();
        assertThat(sent.organizationId()).isEqualTo("org-1");
        assertThat(sent.ownerUserId()).isEqualTo("employee-1");
        assertThat(sent.uploadedByUserId()).isEqualTo("employee-1");
        assertThat(sent.documentType()).isEqualTo(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT);
        assertThat(sent.relatedEntityType()).isEqualTo("TIMESHEET");
    }

    @Test
    void uploadRejectsEmptyFile() {
        authenticateAs("employee-1", UserRole.EMPLOYEE, "org-1");
        MockMultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.upload("employee-1", empty, null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(documentService);
    }

    @Test
    void downloadAllowsTheOwningEmployee() {
        authenticateAs("employee-1", UserRole.EMPLOYEE, "org-1");
        when(documentService.getMetadata("document-1")).thenReturn(StoredDocument.builder()
                .id("document-1").organizationId("org-1").ownerUserId("employee-1").build());
        when(documentService.getContent("document-1")).thenReturn(new DocumentService.DocumentContent(
                StoredDocument.builder().fileName("hours.pdf").build(),
                new byte[]{1}, "application/pdf", 1));

        assertThat(service.download("employee-1", "document-1")).isNotNull();
    }

    @Test
    void downloadRejectsAnotherEmployeeInTheSameOrganization() {
        authenticateAs("employee-2", UserRole.EMPLOYEE, "org-1");
        when(documentService.getMetadata("document-1")).thenReturn(StoredDocument.builder()
                .id("document-1").organizationId("org-1").ownerUserId("employee-1").build());

        assertThatThrownBy(() -> service.download("employee-2", "document-1"))
                .isInstanceOf(ForbiddenException.class);
        verify(documentService, org.mockito.Mockito.never()).getContent(any());
    }

    @Test
    void downloadAllowsHrForADirectReportDocument() {
        authenticateAs("hr-1", UserRole.HR, "org-1");
        when(documentService.getMetadata("document-1")).thenReturn(StoredDocument.builder()
                .id("document-1").organizationId("org-1").ownerUserId("employee-1").build());
        when(documentService.getContent("document-1")).thenReturn(new DocumentService.DocumentContent(
                StoredDocument.builder().fileName("hours.pdf").build(),
                new byte[]{1}, "application/pdf", 1));

        assertThat(service.download("hr-1", "document-1")).isNotNull();
    }

    @Test
    void downloadThrowsBusinessExceptionWhenContentRetrievalFails() {
        authenticateAs("hr-1", UserRole.HR, "org-1");
        when(documentService.getMetadata("document-1")).thenReturn(StoredDocument.builder()
                .id("document-1").organizationId("org-1").ownerUserId("employee-1").build());
        when(documentService.getContent("document-1"))
                .thenThrow(new ObjectStorageException("Stored document content is unavailable", null));

        assertThatThrownBy(() -> service.download("hr-1", "document-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("original uploaded document is unavailable");
    }

    @Test
    void downloadRejectsAcrossOrganizations() {
        authenticateAs("admin-9", UserRole.COMPANY_ADMIN, "org-9");
        when(documentService.getMetadata("document-1")).thenReturn(StoredDocument.builder()
                .id("document-1").organizationId("org-1").ownerUserId("employee-1").build());

        assertThatThrownBy(() -> service.download("admin-9", "document-1"))
                .isInstanceOf(ForbiddenException.class);
    }
}

