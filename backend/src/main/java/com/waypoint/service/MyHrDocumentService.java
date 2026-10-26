package com.waypoint.service;

import com.waypoint.dto.myhr.DocumentUploadResponse;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * My HR entry point for supporting-document uploads and downloads. Adds My HR
 * authentication, organization scoping and access control on top of the shared
 * {@link DocumentService}, which handles the object-storage write/read and the
 * {@code documents} metadata record.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBean(DocumentService.class)
public class MyHrDocumentService {

    private static final Set<UserRole> MY_HR_ROLES =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<DocumentType> ALLOWED_TYPES = Set.of(
            DocumentType.TIMESHEET_SUPPORTING_DOCUMENT,
            DocumentType.EMPLOYEE_PROFILE_DOCUMENT,
            DocumentType.REQUESTED_DOCUMENT);
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private final DocumentService documentService;
    private final MyHrAuthenticatedUserService authenticatedUserService;

    public DocumentUploadResponse upload(
            String actorUserId, MultipartFile file, DocumentType documentType) {

        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, MY_HR_ROLES, false);

        if (file == null || file.isEmpty()) {
            throw new BusinessException("A document file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException("Supporting documents cannot exceed 5 MB");
        }

        DocumentType resolvedType =
                documentType == null ? DocumentType.TIMESHEET_SUPPORTING_DOCUMENT : documentType;
        if (!ALLOWED_TYPES.contains(resolvedType)) {
            throw new BusinessException("Unsupported My HR document type: " + resolvedType);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException("The uploaded document could not be read");
        }

        StoredDocument stored = documentService.store(new DocumentUploadRequest(
                actor.organizationId(),
                actor.userId(),
                resolvedType,
                relatedEntityType(resolvedType),
                null,
                originalFileName(file),
                contentType(file),
                content,
                null,
                actor.userId()));

        return new DocumentUploadResponse(
                stored.getId(),
                stored.getFileName(),
                stored.getContentType(),
                stored.getFileSize() == null ? content.length : stored.getFileSize(),
                stored.getDocumentType().name());
    }

    public DocumentService.DocumentContent download(String actorUserId, String documentId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, MY_HR_ROLES, false);

        StoredDocument metadata = documentService.getMetadata(documentId);
        boolean sameOrganization = actor.organizationId().equals(metadata.getOrganizationId());
        boolean owner = actor.userId().equals(metadata.getOwnerUserId());
        boolean privileged = actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN;
        if (!sameOrganization || (!owner && !privileged)) {
            throw new ForbiddenException("You do not have access to this document");
        }

        try {
            return documentService.getContent(documentId);
        } catch (Exception exception) {
            throw new BusinessException("The original uploaded document is unavailable. Please ask the employee to re-upload the timesheet document.");
        }
    }

    private String relatedEntityType(DocumentType documentType) {
        return documentType == DocumentType.TIMESHEET_SUPPORTING_DOCUMENT ? "TIMESHEET" : null;
    }

    private String originalFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "document" : name;
    }

    private String contentType(MultipartFile file) {
        String type = file.getContentType();
        return type == null || type.isBlank() ? "application/octet-stream" : type;
    }
}
