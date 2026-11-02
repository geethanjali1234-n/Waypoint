package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.config.DocumentRequestReminderProperties;
import com.waypoint.dto.myhr.DocumentRequestResponse;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.DocumentRequestReminder;
import com.waypoint.entity.DocumentRequestReminderState;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.DocumentRequestReminderType;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.DocumentRequestReminderRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F1 end-to-end checks against a real, throwaway LOCAL MongoDB database (waypoint-f1-it on localhost:27017),
 * which is dropped afterwards. Notifications are a test double, so no in-app notification or email is created.
 * Mail and R2 are disabled. Reminders are enabled only inside this test's own application context.
 *
 * Opt-in: runs only when F1_LOCAL_MONGO_IT=true and a local MongoDB is running.
 */
@SpringBootTest(properties = {
        "MONGODB_URI=mongodb://localhost:27017/waypoint-f1-it",
        "MONGODB_DATABASE=waypoint-f1-it",
        "MAIL_ENABLED=false",
        "MAIL_HOST=localhost",
        "MAIL_USERNAME=test",
        "MAIL_PASSWORD=test",
        "R2_ENABLED=false",
        "waypoint.documents.r2.enabled=false",
        "waypoint.documents.local.root=target/test-object-store",
        "DOCUMENT_STORAGE_MIGRATION_ENABLED=false",
        "MYHR_DEV_CREDENTIALS_ENABLED=false",
        "MYHR_DEV_CREDENTIALS_OPEN_TERMINAL=false",
        "PAYROLL_READINESS_AUTOMATIC_REMINDERS_ENABLED=false",
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "DOCUMENT_REQUEST_REMINDERS_ENABLED=true"
})
@EnabledIfEnvironmentVariable(named = "F1_LOCAL_MONGO_IT", matches = "(?i:true)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentRequestRemindersLocalIntegrationTest {

    private static final String DATABASE = "waypoint-f1-it";
    private static final String ORG = "org-f1";
    private static final String HR = "f1-hr";
    private static final String ADMIN = "f1-admin";
    private static final String EMPLOYEE = "f1-employee";
    private static final String INACTIVE_EMPLOYEE = "f1-inactive";
    private static final String SECOND_ADMIN = "f1-admin-2";
    private static final String INACTIVE_HR = "f1-hr-gone";
    /** 03:00 UTC on Oct 21 is still Oct 20 in America/Chicago. */
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
    private static final LocalDate CHICAGO_TODAY = LocalDate.of(2026, 10, 20);
    /** Activation day used for the reminder runs (dates are passed explicitly; the system clock is never changed). */
    private static final LocalDate ACTIVATION = LocalDate.of(2026, 11, 2);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean private DatabaseInitializer databaseInitializer;
    @MockitoBean private ResumeStorageMigrationService resumeStorageMigrationService;
    @MockitoBean private EventLocationMigrationService eventLocationMigrationService;
    @MockitoBean private JobLocationMigrationService jobLocationMigrationService;
    @MockitoBean private FileStorageMigrationService fileStorageMigrationService;
    /** Test double: nothing is sent to anyone. */
    @MockitoBean private NotificationOrchestrator notificationOrchestrator;

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private DocumentRequestService documentRequestService;
    @Autowired private DocumentRequestReminderService reminderService;
    @Autowired private DocumentRequestReminderProperties reminderProperties;
    @Autowired private DocumentRequestReminderRepository reminderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeProfileRepository employeeProfileRepository;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private List<ScheduledTaskHolder> scheduledTaskHolders;

    @BeforeAll
    void refuseAnythingButTheLocalThrowawayDatabase() {
        assertThat(mongoTemplate.getDb().getName()).isEqualTo(DATABASE);
    }

    @BeforeEach
    void cleanFixtures() {
        // Delete documents only, so the indexes created at startup (including the unique reminder index) stay.
        List.of("users", "employee_profiles", "document_requests", "document_request_reminders", "document_request_reminder_state")
                .forEach(collection -> mongoTemplate.remove(new Query(), collection));
        User firstAdmin = user(ADMIN, UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE);
        firstAdmin.setCreatedAt(LocalDateTime.of(2024, 1, 1, 9, 0));
        User laterAdmin = user(SECOND_ADMIN, UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE);
        laterAdmin.setCreatedAt(LocalDateTime.of(2025, 6, 1, 9, 0));
        userRepository.saveAll(List.of(user(HR, UserRole.HR, AccountStatus.ACTIVE), firstAdmin, laterAdmin,
                user(INACTIVE_HR, UserRole.HR, AccountStatus.INACTIVE),
                user(EMPLOYEE, UserRole.EMPLOYEE, AccountStatus.ACTIVE), user(INACTIVE_EMPLOYEE, UserRole.EMPLOYEE, AccountStatus.INACTIVE)));
    }

    @AfterAll
    void dropTheThrowawayDatabase() {
        if (DATABASE.equals(mongoTemplate.getDb().getName())) mongoTemplate.getDb().drop();
    }

    @Test
    @Order(1)
    void startupRegistersTheDailyCronButNeverProcessesReminders() {
        // Reminders are enabled in this context, yet starting the application sent nothing and recorded no activation.
        verifyNoInteractions(notificationOrchestrator);
        assertThat(mongoTemplate.count(new Query(), DocumentRequestReminderState.class)).isZero();
        List<CronTask> cronTasks = scheduledTaskHolders.stream().flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask()).filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .filter(task -> task.toString().contains("DocumentRequestReminderService.runDaily")).toList();
        assertThat(cronTasks).singleElement().satisfies(task -> assertThat(task.getExpression()).isEqualTo("0 0 8 * * *"));
    }

    @Test
    @Order(2)
    void overdueFlagAndFilterUseChicagoDateAndOnlyHrToEmployeeRequests() throws Exception {
        assertThat(clock.instant()).isEqualTo(NOW);
        save(request("due-yesterday", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, CHICAGO_TODAY.minusDays(1), EMPLOYEE));
        save(request("due-today", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, CHICAGO_TODAY, EMPLOYEE));
        save(request("fulfilled-past-due", "TO_EMPLOYEE", DocumentRequestStatus.FULFILLED, CHICAGO_TODAY.minusDays(20), EMPLOYEE));
        save(request("no-due-date", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, null, EMPLOYEE));
        DocumentRequest toManager = request("to-manager-past-due", "TO_MANAGER", DocumentRequestStatus.PENDING, CHICAGO_TODAY.minusDays(20), HR);
        toManager.setRequestedByUserId(EMPLOYEE);
        save(toManager);
        Map<String, DocumentRequestStatus> before = storedStatuses();

        Map<String, Boolean> flags = documentRequestService.list(ADMIN, 0, 50, "ALL", "ALL", "ALL", null, null).items().stream()
                .collect(Collectors.toMap(DocumentRequestResponse::id, DocumentRequestResponse::overdue));
        assertThat(flags).containsExactlyInAnyOrderEntriesOf(Map.of("due-yesterday", true, "due-today", false,
                "fulfilled-past-due", false, "no-due-date", false, "to-manager-past-due", true));

        assertThat(ids(documentRequestService.list(ADMIN, 0, 50, "OVERDUE", "ALL", "ALL", null, null).items()))
                .containsExactlyInAnyOrder("due-yesterday", "to-manager-past-due");
        assertThat(ids(documentRequestService.list(HR, 0, 50, "OVERDUE", "ALL", "ALL", null, "requested-employees").items())).containsExactly("due-yesterday");
        assertThat(ids(documentRequestService.list(HR, 0, 50, "OVERDUE", "ALL", "ALL", null, "received").items())).containsExactly("to-manager-past-due");
        assertThat(ids(documentRequestService.list(EMPLOYEE, 0, 50, "OVERDUE", "ALL", "ALL", null, null).items()))
                .containsExactlyInAnyOrder("due-yesterday", "to-manager-past-due");
        // PENDING filtering is unchanged: every pending request, overdue or not, in every direction.
        assertThat(ids(documentRequestService.list(ADMIN, 0, 50, "PENDING", "ALL", "ALL", null, null).items()))
                .containsExactlyInAnyOrder("due-yesterday", "due-today", "no-due-date", "to-manager-past-due");

        // The JSON the frontend receives carries the flag under the name it reads.
        DocumentRequestResponse overdue = documentRequestService.get(ADMIN, "due-yesterday");
        assertThat(objectMapper.writeValueAsString(overdue)).contains("\"overdue\":true").contains("\"status\":\"PENDING\"");

        assertThat(storedStatuses()).isEqualTo(before);
    }

    @Test
    @Order(3)
    void remindersEndToEndIncludingDuplicatesRestartDowntimeAndActivationCutoff() {
        save(request("due-in-3", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.plusDays(3), EMPLOYEE));
        save(request("due-in-1", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.plusDays(1), EMPLOYEE));
        save(request("overdue-before-activation", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.minusDays(5), EMPLOYEE));
        save(request("fulfilled", "TO_EMPLOYEE", DocumentRequestStatus.FULFILLED, ACTIVATION.plusDays(1), EMPLOYEE));
        save(request("no-due-date", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, null, EMPLOYEE));
        save(request("inactive-recipient", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.plusDays(3), INACTIVE_EMPLOYEE));
        // Employee-to-HR and I-983 reminders are covered in their own test below.
        Map<String, DocumentRequestStatus> before = storedStatuses();

        // Activation day: 3-day and 1-day reminders to the employee only.
        var first = reminderService.runForDate(ACTIVATION);
        assertThat(first.activatedOn()).isEqualTo(ACTIVATION);
        assertThat(first.sent()).isEqualTo(2);
        assertThat(first.skipped()).isEqualTo(1);
        List<NotificationEvent> events = sent(2);
        assertThat(events).extracting(NotificationEvent::getRelatedEntityId).containsExactlyInAnyOrder("due-in-3", "due-in-1");
        assertThat(events).extracting(NotificationEvent::getRecipientUserId).containsOnly(EMPLOYEE);
        assertThat(events).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_REMINDER);
        assertThat(claim("inactive-recipient", DocumentRequestReminderType.DUE_IN_3_DAYS).getStatus())
                .isEqualTo(DocumentRequestReminder.Status.SKIPPED);

        // Same day again, from a freshly built service (as after a backend restart): nothing is sent twice.
        clearInvocations();
        var afterRestart = restartedService().runForDate(ACTIVATION);
        assertThat(afterRestart.sent()).isZero();
        assertThat(afterRestart.alreadyHandled()).isEqualTo(3);
        verify(notificationOrchestrator, never()).notify(any());

        // 12 days of downtime: every request that became overdue since activation is alerted, the pre-activation one never.
        clearInvocations();
        var afterDowntime = restartedService().runForDate(ACTIVATION.plusDays(12));
        assertThat(afterDowntime.activatedOn()).isEqualTo(ACTIVATION);
        List<NotificationEvent> overdue = sent(3);
        assertThat(overdue).extracting(NotificationEvent::getRelatedEntityId)
                .containsExactlyInAnyOrder("due-in-1", "due-in-3", "inactive-recipient");
        assertThat(overdue).extracting(NotificationEvent::getRecipientUserId).containsOnly(HR);
        assertThat(overdue).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_OVERDUE);

        clearInvocations();
        assertThat(reminderService.runForDate(ACTIVATION.plusDays(13)).sent()).isZero();
        verify(notificationOrchestrator, never()).notify(any());

        // Exactly one activation record, never moved; stored request statuses never changed.
        assertThat(mongoTemplate.findAll(DocumentRequestReminderState.class)).singleElement()
                .satisfies(state -> assertThat(state.getActivatedOn()).isEqualTo(ACTIVATION));
        assertThat(storedStatuses()).isEqualTo(before);
    }

    @Test
    @Order(4)
    void aFailedSendIsRetriedOnTheNextRunAndTheUniqueIndexBlocksDuplicateClaims() {
        save(request("flaky", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.plusDays(1), EMPLOYEE));
        doThrow(new IllegalStateException("simulated failure")).when(notificationOrchestrator).notify(any());

        assertThat(reminderService.runForDate(ACTIVATION).failed()).isEqualTo(1);
        DocumentRequestReminder failed = claim("flaky", DocumentRequestReminderType.DUE_IN_1_DAY);
        assertThat(failed.getStatus()).isEqualTo(DocumentRequestReminder.Status.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);

        doNothing().when(notificationOrchestrator).notify(any());
        assertThat(reminderService.runForDate(ACTIVATION).sent()).isEqualTo(1);
        DocumentRequestReminder retried = claim("flaky", DocumentRequestReminderType.DUE_IN_1_DAY);
        assertThat(retried.getStatus()).isEqualTo(DocumentRequestReminder.Status.SENT);
        assertThat(retried.getAttempts()).isEqualTo(2);

        assertThatThrownBy(() -> reminderRepository.insert(DocumentRequestReminder.builder().organizationId(ORG)
                .documentRequestId("flaky").reminderType(DocumentRequestReminderType.DUE_IN_1_DAY)
                .requiredBy(ACTIVATION.plusDays(1)).status(DocumentRequestReminder.Status.PROCESSING).attempts(1).build()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Order(5)
    void retriesStopAtTheAttemptLimitAndDisabledRemindersDoNothing() {
        save(request("exhausted", "TO_EMPLOYEE", DocumentRequestStatus.PENDING, ACTIVATION.plusDays(1), EMPLOYEE));
        reminderRepository.insert(DocumentRequestReminder.builder().organizationId(ORG).documentRequestId("exhausted")
                .reminderType(DocumentRequestReminderType.DUE_IN_1_DAY).requiredBy(ACTIVATION.plusDays(1)).recipientUserId(EMPLOYEE)
                .status(DocumentRequestReminder.Status.FAILED).attempts(DocumentRequestReminderService.MAX_ATTEMPTS).build());

        assertThat(reminderService.runForDate(ACTIVATION).alreadyHandled()).isEqualTo(1);
        verify(notificationOrchestrator, never()).notify(any());

        reminderProperties.setEnabled(false);
        try {
            mongoTemplate.remove(new Query(), DocumentRequestReminderState.class);
            assertThat(reminderService.runDaily().enabled()).isFalse();
            assertThat(mongoTemplate.count(new Query(), DocumentRequestReminderState.class)).isZero();
        } finally {
            reminderProperties.setEnabled(true);
        }
    }

    @Test
    @Order(6)
    void employeeToHrAndI983RemindersWithFallbackDowntimeAndCorrection() {
        save(toManager("emp-letter", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, ACTIVATION.plusDays(3), HR));
        save(toManager("emp-approved", DocumentRequestStatus.APPROVED, RequestedDocumentType.EMPLOYMENT_LETTER, ACTIVATION.plusDays(1), HR));
        save(toManager("emp-awaiting-approval", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.OFFER_LETTER, ACTIVATION.plusDays(1), HR));
        save(toManager("emp-hr-gone", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, ACTIVATION.plusDays(1), INACTIVE_HR));
        save(toManager("emp-fulfilled", DocumentRequestStatus.FULFILLED, RequestedDocumentType.EMPLOYMENT_LETTER, ACTIVATION.plusDays(1), HR));
        save(toManager("i983-review", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.I983, ACTIVATION.plusDays(3), HR));
        save(toManager("i983-returned", DocumentRequestStatus.RETURNED_FOR_CORRECTION, RequestedDocumentType.I983, ACTIVATION.plusDays(1), HR));
        save(toManager("i983-approved", DocumentRequestStatus.APPROVED, RequestedDocumentType.I983, ACTIVATION.plusDays(1), HR));
        Map<String, DocumentRequestStatus> before = storedStatuses();

        // Activation day: the people who have to act are reminded; nobody while a Company Admin is approving.
        var first = reminderService.runForDate(ACTIVATION);
        assertThat(first.sent()).isEqualTo(5);
        Map<String, NotificationEvent> events = byRequest(sent(5));
        assertThat(events).containsOnlyKeys("emp-letter", "emp-approved", "emp-hr-gone", "i983-review", "i983-returned");
        assertThat(events.get("emp-letter").getRecipientUserId()).isEqualTo(HR);
        assertThat(events.get("emp-approved").getRecipientUserId()).isEqualTo(HR);
        assertThat(events.get("i983-review").getRecipientUserId()).isEqualTo(HR);
        assertThat(events.get("i983-returned").getRecipientUserId()).isEqualTo(EMPLOYEE);
        // The assigned HR is inactive: exactly one Company Admin (the earliest account) gets it, never both.
        assertThat(events.get("emp-hr-gone").getRecipientUserId()).isEqualTo(ADMIN);
        assertThat(claim("emp-hr-gone", DocumentRequestReminderType.DUE_IN_1_DAY).getDetail())
                .isEqualTo(DocumentRequestReminderService.FALLBACK_DETAIL);
        assertThat(claim("i983-returned", DocumentRequestReminderType.CORRECTION_DUE_IN_1_DAY).getStatus())
                .isEqualTo(DocumentRequestReminder.Status.SENT);

        // 10 days of downtime: one overdue alert per open request; still nothing while awaiting approval.
        clearInvocations();
        restartedService().runForDate(ACTIVATION.plusDays(10));
        Map<String, NotificationEvent> overdue = byRequest(sent(5));
        assertThat(overdue).containsOnlyKeys("emp-letter", "emp-approved", "emp-hr-gone", "i983-review", "i983-returned");
        assertThat(overdue.values()).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_OVERDUE);
        assertThat(overdue.get("emp-hr-gone").getRecipientUserId()).isEqualTo(ADMIN);
        assertThat(overdue.get("i983-review").getRecipientUserId()).isEqualTo(HR);
        assertThat(overdue.get("i983-returned").getRecipientUserId()).isEqualTo(EMPLOYEE);
        assertThat(claim("i983-returned", DocumentRequestReminderType.CORRECTION_OVERDUE).getStatus())
                .isEqualTo(DocumentRequestReminder.Status.SENT);

        // HR returns the overdue I-983 for correction: the employee gets their own overdue alert, once.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("i983-review")),
                new Update().set("status", DocumentRequestStatus.RETURNED_FOR_CORRECTION), DocumentRequest.class);
        clearInvocations();
        reminderService.runForDate(ACTIVATION.plusDays(11));
        NotificationEvent correction = sent(1).getFirst();
        assertThat(correction.getRelatedEntityId()).isEqualTo("i983-review");
        assertThat(correction.getRecipientUserId()).isEqualTo(EMPLOYEE);
        assertThat(correction.getTitle()).isEqualTo("Signed I-983 correction overdue");

        // The employee resubmits it: the reviewer's overdue alert was already sent for this due date, so nothing repeats.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("i983-review")),
                new Update().set("status", DocumentRequestStatus.SUBMITTED), DocumentRequest.class);
        clearInvocations();
        assertThat(reminderService.runForDate(ACTIVATION.plusDays(12)).sent()).isZero();
        verify(notificationOrchestrator, never()).notify(any());

        // Reminder runs never changed a stored status (only this test's own simulated workflow steps did).
        Map<String, DocumentRequestStatus> after = storedStatuses();
        after.put("i983-review", DocumentRequestStatus.SUBMITTED);
        assertThat(after).isEqualTo(before);
    }

    private static Map<String, NotificationEvent> byRequest(List<NotificationEvent> events) {
        return events.stream().collect(Collectors.toMap(NotificationEvent::getRelatedEntityId, event -> event));
    }

    private static DocumentRequest toManager(String id, DocumentRequestStatus status, RequestedDocumentType type,
                                             LocalDate requiredBy, String assignedHr) {
        DocumentRequest request = request(id, "TO_MANAGER", status, requiredBy, assignedHr);
        request.setRequestedByUserId(EMPLOYEE);
        request.setDocumentType(type);
        return request;
    }

    private DocumentRequestReminderService restartedService() {
        return new DocumentRequestReminderService(reminderProperties, reminderRepository, userRepository, employeeProfileRepository,
                notificationOrchestrator, mongoTemplate, clock);
    }

    private void clearInvocations() {
        org.mockito.Mockito.clearInvocations(notificationOrchestrator);
    }

    private List<NotificationEvent> sent(int expected) {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationOrchestrator, org.mockito.Mockito.times(expected)).notify(captor.capture());
        return captor.getAllValues();
    }

    private DocumentRequestReminder claim(String requestId, DocumentRequestReminderType type) {
        return reminderRepository.findByDocumentRequestIdIn(List.of(requestId)).stream()
                .filter(claim -> claim.getReminderType() == type).findFirst().orElseThrow();
    }

    private Map<String, DocumentRequestStatus> storedStatuses() {
        return mongoTemplate.findAll(DocumentRequest.class).stream()
                .collect(Collectors.toMap(DocumentRequest::getId, DocumentRequest::getStatus));
    }

    private static List<String> ids(List<DocumentRequestResponse> responses) {
        return responses.stream().map(DocumentRequestResponse::id).toList();
    }

    private void save(DocumentRequest request) {
        mongoTemplate.insert(request);
    }

    private static DocumentRequest request(String id, String direction, DocumentRequestStatus status, LocalDate requiredBy, String requestedFor) {
        LocalDateTime at = LocalDateTime.of(2026, 9, 1, 10, 0);
        return DocumentRequest.builder().id(id).organizationId(ORG).requestedByUserId(HR).requestedForUserId(requestedFor)
                .requestTitle("F1 " + id).requestDirection(direction).status(status).requiredBy(requiredBy)
                .priority("NORMAL").category("EMPLOYMENT").requestedAt(at).createdAt(at).updatedAt(at).build();
    }

    private static User user(String id, UserRole role, AccountStatus status) {
        return User.builder().id(id).loginId(id).email(id + "@example.invalid").organizationId(ORG).role(role)
                .accountStatus(status).mustChangePassword(false).build();
    }
}
