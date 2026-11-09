package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.StoredDocument;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.StoredDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimesheetDocumentIdentityServiceTest {
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final StoredDocumentRepository documents = mock(StoredDocumentRepository.class);
    private final DocumentService storage = mock(DocumentService.class);
    private final TimesheetDocumentParserService parser = new TimesheetDocumentParserService();
    private final EmployeeProfile profile = EmployeeProfile.builder().userId("u1").organizationId("org")
            .employeeId("EMP-001").firstName("John").lastName("Doe").build();
    private TimesheetDocumentIdentityService service;

    @BeforeEach void setup() {
        service = new TimesheetDocumentIdentityService(profiles, documents, parser, Optional.of(storage));
    }

    @Test void matchingIdCannotVerifyInitials() {
        assertThat(service.inspect("Employee ID: emp001\nEmployee Name: J Doe", profile).status()).isEqualTo("UNVERIFIED");
    }
    @Test void differentClientIdDoesNotRejectAMatchingName() {
        assertThat(service.inspect("Employee ID: CLIENT-987\nEmployee Name: John Doe", profile).status()).isEqualTo("MATCHED");
    }
    @Test void matchingIdCannotOverrideAWrongName() {
        assertThat(service.inspect("Employee ID: EMP001\nEmployee Name: Alice Smith", profile).status()).isEqualTo("MISMATCH");
    }
    @Test void rejectsDisjointFullName() {
        assertThat(service.inspect("Employee Name: Alice Smith", profile).status()).isEqualTo("MISMATCH");
    }
    @Test void matchesReorderedNameAndIgnoresManagersName() {
        assertThat(service.inspect("Name: DOE JOHN\nManager Name: Alice Smith", profile).status()).isEqualTo("MATCHED");
        assertThat(service.inspect("Name: Acme Corporation\nEmployee Name: John Doe", profile).status()).isEqualTo("MATCHED");
    }
    @Test void leavesInitialsAndPartialNamesForReview() {
        assertThat(service.inspect("Name: J. Doe", profile).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("Name: Jane Doe", profile).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("Name: John", profile).status()).isEqualTo("UNVERIFIED");
    }
    @Test void missingIdentityOrProfileIsNeverMatched() {
        assertThat(service.inspect("2026-11-01 8 hours", profile).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("Employee ID: EMP-001", null).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("", profile).status()).isEqualTo("UNVERIFIED");
    }
    @Test void columnHeadersAreNotIdentityEvidence() {
        assertThat(service.inspect("Employee Name,Employee ID,Date,Hours", profile).status())
                .isEqualTo("UNVERIFIED");
    }
    @Test void checksCsvAndSpreadsheetIdentityColumns() {
        assertThat(service.inspect("Employee Name,Employee ID,Date,Hours\n\"Doe, John\",CLIENT-987,2026-11-01,8", profile).status())
                .isEqualTo("MATCHED");
        assertThat(service.inspect("Employee Name\tEmployee ID\tDate\tHours\nAlice Smith\tEMP002\t2026-11-01\t8", profile).status())
                .isEqualTo("MISMATCH");
        assertThat(service.inspect("Employee Name,Date,Hours\nAlice Smith,2026-11-01,8", profile).status())
                .isEqualTo("MISMATCH");
    }
    @Test void idsAloneNeverVerifyOrRejectAName() {
        assertThat(service.inspect("Employee ID: EMP001\nEmployee ID: CLIENT-987", profile).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("Employee ID: EMP001", profile).status()).isEqualTo("UNVERIFIED");
        assertThat(service.inspect("Employee ID: CLIENT-987", profile).status()).isEqualTo("UNVERIFIED");
    }
    @Test void doesNotRequireAnInternalEmployeeIdToMatchAName() {
        var nameOnlyProfile = EmployeeProfile.builder().firstName("John").lastName("Doe").build();
        assertThat(service.inspect("Employee ID: CLIENT-987\nEmployee Name: John Doe", nameOnlyProfile).status()).isEqualTo("MATCHED");
    }
    @Test void validatesActualStoredBytesAndRecordsMismatch() {
        var document = StoredDocument.builder().id("doc").ownerUserId("u1").organizationId("org").fileName("hours.txt").build();
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        var bytes = "Employee Name: Alice Smith".getBytes(StandardCharsets.UTF_8);
        when(storage.getContent("doc")).thenReturn(new DocumentService.DocumentContent(document, bytes, "text/plain", bytes.length));
        assertThatThrownBy(() -> service.validateStored(document)).isInstanceOf(BusinessException.class).hasMessageContaining("name does not match");
        assertThat(document.getIdentityValidationStatus()).isEqualTo("MISMATCH");
        verify(documents).save(document);
    }
    @Test void unreadableFileIsExplicitlyUnverified() {
        var document = StoredDocument.builder().id("doc").ownerUserId("u1").organizationId("org").fileName("hours.bin").build();
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        assertThat(service.validate(document, new byte[]{1, 2, 3}).status()).isEqualTo("UNVERIFIED");
        assertThat(document.getIdentityValidationMessage()).contains("HR must check");
    }
    @Test void checksIdentityInsideActualSpreadsheetBytes() throws Exception {
        var document = StoredDocument.builder().id("doc").ownerUserId("u1").organizationId("org").fileName("hours.xlsx").build();
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        var bytes = new java.io.ByteArrayOutputStream();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Hours");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Employee Name");
            header.createCell(1).setCellValue("Employee ID");
            header.createCell(2).setCellValue("Hours");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Alice Smith");
            row.createCell(1).setCellValue("EMP002");
            row.createCell(2).setCellValue(8);
            workbook.write(bytes);
        }
        assertThatThrownBy(() -> service.validate(document, bytes.toByteArray()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("name does not match");
    }
    @Test void checksIdentityInsideActualPdfBytes() throws Exception {
        var document = StoredDocument.builder().id("doc").ownerUserId("u1").organizationId("org").fileName("hours.pdf").build();
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        var bytes = new java.io.ByteArrayOutputStream();
        var pdf = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(pdf, bytes);
        pdf.open();
        pdf.add(new com.lowagie.text.Paragraph("Employee Name: John Doe"));
        pdf.add(new com.lowagie.text.Paragraph("Employee ID: CLIENT-987"));
        pdf.close();
        assertThat(service.validate(document, bytes.toByteArray()).status()).isEqualTo("MATCHED");
    }
    @Test void missingStorageCannotBypassValidation() {
        var offline = new TimesheetDocumentIdentityService(profiles, documents, parser, Optional.empty());
        assertThatThrownBy(() -> offline.validateStored(StoredDocument.builder().id("doc").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("verification is unavailable");
    }
    @Test void storedIdBasedMismatchIsReplacedByFreshNameCheck() {
        var document = StoredDocument.builder().id("doc").ownerUserId("u1").organizationId("org")
                .fileName("hours.txt").identityValidationStatus("MISMATCH").build();
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        var bytes = "Employee Name: John Doe\nEmployee ID: CLIENT-987".getBytes(StandardCharsets.UTF_8);
        when(storage.getContent("doc")).thenReturn(new DocumentService.DocumentContent(document, bytes, "text/plain", bytes.length));
        assertThat(service.validateStored(document).status()).isEqualTo("MATCHED");
        assertThat(document.getIdentityValidationStatus()).isEqualTo("MATCHED");
    }
}
