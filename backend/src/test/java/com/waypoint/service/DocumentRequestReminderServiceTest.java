package com.waypoint.service;

import com.waypoint.config.DocumentRequestReminderProperties;
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
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentRequestReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private final DocumentRequestReminderProperties properties = new DocumentRequestReminderProperties();
    private final DocumentRequestReminderRepository reminders = mock(DocumentRequestReminderRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final NotificationOrchestrator orchestrator = mock(NotificationOrchestrator.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 10, 21, 2, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant(),
            ZoneId.of("America/Chicago"));

    /** Requests "stored in MongoDB", answered by the mocked find/findById. */
    private final Map<String, DocumentRequest> stored = new HashMap<>();
    private final List<DocumentRequestReminder> existingClaims = new ArrayList<>();
    private LocalDate activatedOn = TODAY;
    private DocumentRequestReminderService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        service = new DocumentRequestReminderService(properties, reminders, users, profiles, orchestrator, mongo, clock);

        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DocumentRequestReminderState.class)))
                .thenAnswer(call -> DocumentRequestReminderState.builder().id("activation").activatedOn(activatedOn).build());
        when(mongo.find(any(Query.class), eq(DocumentRequest.class))).thenAnswer(call -> matching(call.getArgument(0)));
        when(mongo.findById(anyString(), eq(DocumentRequest.class))).thenAnswer(call -> stored.get((String) call.getArgument(0)));
        when(reminders.findByDocumentRequestIdIn(any())).thenAnswer(call -> List.copyOf(existingClaims));
        when(reminders.insert(any(DocumentRequestReminder.class))).thenAnswer(call -> {
            DocumentRequestReminder claim = call.getArgument(0);
            claim.setId("claim-" + claim.getDocumentRequestId() + "-" + claim.getReminderType());
            return claim;
        });
        when(users.findById(anyString())).thenAnswer(call -> Optional.of(user(call.getArgument(0), AccountStatus.ACTIVE)));
        when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void disabledByDefaultDoesNothing() {
        properties.setEnabled(false);
        var result = service.runForDate(TODAY);

        assertThat(result.enabled()).isFalse();
        verifyNoInteractions(mongo, reminders, users, orchestrator);
    }

    @Test
    void dailyRunDoesNothingWhenDisabled() {
        properties.setEnabled(false);
        var result = service.runDaily();

        assertThat(result.enabled()).isFalse();
        verifyNoInteractions(mongo, reminders, users, orchestrator);
    }

    @Test
    void dailyRunDelegatesWithTheChicagoDate() {
        // 03:00 UTC on Oct 21 is still Oct 20 in Chicago.
        Clock utcClock = Clock.fixed(java.time.Instant.parse("2026-10-21T03:00:00Z"), ZoneId.of("UTC"));
        var dailyService = spy(new DocumentRequestReminderService(properties, reminders, users, profiles, orchestrator, mongo, utcClock));

        var result = dailyService.runDaily();

        verify(dailyService).runForDate(LocalDate.of(2026, 10, 20));
        assertThat(result.enabled()).isTrue();
        assertThat(result.runDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void dailyRunIsACronInTheReminderZoneAndNeverRunsAtStartup() throws Exception {
        Scheduled scheduled = DocumentRequestReminderService.class.getMethod("runDaily").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${waypoint.myhr.document-request-reminders.scheduler-cron:0 0 8 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${waypoint.myhr.document-request-reminders.zone-id:America/Chicago}");
        assertThat(scheduled.fixedRate()).isNegative();
        assertThat(scheduled.fixedDelay()).isNegative();
        assertThat(scheduled.initialDelay()).isNegative();
        assertThat(ApplicationRunner.class.isAssignableFrom(DocumentRequestReminderService.class)).isFalse();
        assertThat(CommandLineRunner.class.isAssignableFrom(DocumentRequestReminderService.class)).isFalse();
        assertThat(DocumentRequestReminderService.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(EventListener.class));
        // The default fires once a day at 8:00 AM Chicago time.
        assertThat(CronExpression.parse("0 0 8 * * *").next(ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, ZoneId.of("America/Chicago"))))
                .isEqualTo(ZonedDateTime.of(2026, 10, 22, 8, 0, 0, 0, ZoneId.of("America/Chicago")));
    }

    @Test
    void activationDateIsOnlyEverInsertedNeverOverwritten() {
        service.runForDate(TODAY);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), options.capture(), eq(DocumentRequestReminderState.class));
        assertThat(update.getValue().getUpdateObject().keySet()).containsExactly("$setOnInsert");
        assertThat(options.getValue().isUpsert()).isTrue();
    }

    @Test
    void remindsTheEmployeeThreeDaysAndOneDayBeforeTheDueDate() {
        store(request("r3", TODAY.plusDays(3)));
        store(request("r1", TODAY.plusDays(1)));
        store(request("r2", TODAY.plusDays(2)));

        var result = service.runForDate(TODAY);

        assertThat(result.sent()).isEqualTo(2);
        List<NotificationEvent> events = sentEvents(2);
        assertThat(events).extracting(NotificationEvent::getRecipientUserId).containsOnly("employee-1");
        assertThat(events).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_REMINDER);
        assertThat(events).extracting(NotificationEvent::getRelatedEntityId).containsExactlyInAnyOrder("r3", "r1");
        assertThat(events.getFirst().getActionPath()).startsWith("/myhr/employee/document-requests/");
        verify(reminders, times(2)).save(any(DocumentRequestReminder.class));
    }

    @Test
    void overdueAlertsOnlyCoverDueDatesFromTheActivationDateAndSurviveDowntime() {
        activatedOn = LocalDate.of(2026, 10, 1);
        store(request("before-activation", LocalDate.of(2026, 9, 30)));
        store(request("on-activation", LocalDate.of(2026, 10, 1)));
        store(request("ten-days-ago", LocalDate.of(2026, 10, 11)));
        store(request("due-today", TODAY));

        var result = service.runForDate(TODAY);

        assertThat(result.activatedOn()).isEqualTo(activatedOn);
        List<NotificationEvent> events = sentEvents(2);
        assertThat(events).extracting(NotificationEvent::getRelatedEntityId).containsExactlyInAnyOrder("on-activation", "ten-days-ago");
        assertThat(events).extracting(NotificationEvent::getRecipientUserId).containsOnly("hr-1");
        assertThat(events).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_OVERDUE);
        assertThat(events.getFirst().getMessage()).contains("employee-1").contains("has not completed");
    }

    @Test
    void noOverdueAlertsOnTheActivationDayItself() {
        store(request("old-overdue", TODAY.minusDays(30)));

        service.runForDate(TODAY);

        verify(orchestrator, never()).notify(any());
    }

    @Test
    void aReminderAlreadySentIsNeverSentAgain() {
        store(request("r1", TODAY.plusDays(1)));
        existingClaims.add(claim("r1", DocumentRequestReminderType.DUE_IN_1_DAY, TODAY.plusDays(1), DocumentRequestReminder.Status.SENT, 1));

        var result = service.runForDate(TODAY);

        assertThat(result.alreadyHandled()).isEqualTo(1);
        verify(reminders, never()).insert(any(DocumentRequestReminder.class));
        verify(orchestrator, never()).notify(any());
    }

    @Test
    void aClaimMadeByAnotherInstanceAtTheSameTimeIsNotSentTwice() {
        store(request("r1", TODAY.plusDays(1)));
        when(reminders.insert(any(DocumentRequestReminder.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(reminders.findByDocumentRequestIdAndReminderTypeAndRequiredBy("r1", DocumentRequestReminderType.DUE_IN_1_DAY, TODAY.plusDays(1)))
                .thenReturn(Optional.of(claim("r1", DocumentRequestReminderType.DUE_IN_1_DAY, TODAY.plusDays(1),
                        DocumentRequestReminder.Status.PROCESSING, 1)));

        var result = service.runForDate(TODAY);

        assertThat(result.alreadyHandled()).isEqualTo(1);
        verify(orchestrator, never()).notify(any());
    }

    @Test
    void aFailedReminderIsRetriedUntilTheAttemptLimit() {
        store(request("retry", TODAY.plusDays(1)));
        store(request("exhausted", TODAY.plusDays(3)));
        existingClaims.add(claim("retry", DocumentRequestReminderType.DUE_IN_1_DAY, TODAY.plusDays(1), DocumentRequestReminder.Status.FAILED, 1));
        existingClaims.add(claim("exhausted", DocumentRequestReminderType.DUE_IN_3_DAYS, TODAY.plusDays(3),
                DocumentRequestReminder.Status.FAILED, DocumentRequestReminderService.MAX_ATTEMPTS));
        when(reminders.claimFailedForRetry(eq("claim-retry"), anyInt(), any())).thenReturn(1L);

        var result = service.runForDate(TODAY);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.alreadyHandled()).isEqualTo(1);
        assertThat(sentEvents(1).getFirst().getRelatedEntityId()).isEqualTo("retry");
        verify(reminders, never()).claimFailedForRetry(eq("claim-exhausted"), anyInt(), any());
    }

    @Test
    void skipsRequestsCompletedSinceTheQueryAndInactiveRecipients() {
        store(request("completed", TODAY.plusDays(1)));
        store(request("inactive", TODAY.plusDays(3)));
        DocumentRequest fulfilledSinceQuery = request("completed", TODAY.plusDays(1));
        fulfilledSinceQuery.setStatus(DocumentRequestStatus.FULFILLED);
        when(mongo.findById(eq("completed"), eq(DocumentRequest.class))).thenReturn(fulfilledSinceQuery);
        when(users.findById("employee-1")).thenReturn(Optional.of(user("employee-1", AccountStatus.INACTIVE)));

        var result = service.runForDate(TODAY);

        assertThat(result.skipped()).isEqualTo(2);
        verify(orchestrator, never()).notify(any());
        ArgumentCaptor<DocumentRequestReminder> saved = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DocumentRequestReminder::getStatus).containsOnly(DocumentRequestReminder.Status.SKIPPED);
        assertThat(saved.getAllValues()).extracting(DocumentRequestReminder::getDetail)
                .containsExactlyInAnyOrder("REQUEST_NOT_ACTIONABLE", "RECIPIENT_NOT_ACTIVE");
    }

    @Test
    void oneFailureIsRecordedAndTheRunContinues() {
        store(request("broken", TODAY.plusDays(3)));
        store(request("fine", TODAY.plusDays(1)));
        when(mongo.findById(eq("broken"), eq(DocumentRequest.class))).thenThrow(new IllegalStateException("boom"));

        var result = service.runForDate(TODAY);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        ArgumentCaptor<DocumentRequestReminder> saved = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DocumentRequestReminder::getStatus)
                .containsExactlyInAnyOrder(DocumentRequestReminder.Status.FAILED, DocumentRequestReminder.Status.SENT);
    }

    @Test
    void aReminderAlreadyHandedOffIsNeverMarkedFailedEvenIfSavingItsStatusFails() {
        store(request("r1", TODAY.plusDays(1)));
        when(reminders.save(any(DocumentRequestReminder.class))).thenThrow(new IllegalStateException("database unavailable"));

        var result = service.runForDate(TODAY);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        sentEvents(1);
        // Only the SENT save was attempted; no FAILED save follows, so the claim stays PROCESSING and is never resent.
        ArgumentCaptor<DocumentRequestReminder> saved = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders, times(1)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DocumentRequestReminder.Status.SENT);
    }

    // ---- Employee-to-HR requests -------------------------------------------------------------------------------

    @Test
    void remindsTheAssignedHrAboutEmployeeRequestsThreeDaysAndOneDayBefore() {
        store(toManager("letter-3", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(3)));
        store(toManager("offer-1", DocumentRequestStatus.PENDING, RequestedDocumentType.OFFER_LETTER, TODAY.plusDays(1)));
        store(toManager("letter-2", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(2)));
        store(toManager("no-due-date", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, null));

        var result = service.runForDate(TODAY);

        assertThat(result.sent()).isEqualTo(2);
        List<NotificationEvent> events = sentEvents(2);
        assertThat(events).extracting(NotificationEvent::getRelatedEntityId).containsExactlyInAnyOrder("letter-3", "offer-1");
        assertThat(events).extracting(NotificationEvent::getRecipientUserId).containsOnly("hr-1");
        assertThat(events).extracting(NotificationEvent::getType).containsOnly(NotificationType.DOCUMENT_REQUEST_REMINDER);
        assertThat(events).extracting(NotificationEvent::getTitle).containsOnly("Employee document request due soon");
        assertThat(events).allSatisfy(event -> assertThat(event.getMessage()).contains("employee-1 requested"));
        assertThat(events.getFirst().getActionPath()).startsWith("/myhr/hr/document-requests/");
    }

    @Test
    void remindsHrWhileItMustReviseOrSendButNotWhileACompanyAdminIsApproving() {
        store(toManager("returned-by-admin", DocumentRequestStatus.RETURNED_FOR_CORRECTION, RequestedDocumentType.OFFER_LETTER, TODAY.plusDays(1)));
        store(toManager("approved-to-send", DocumentRequestStatus.APPROVED, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1)));
        store(toManager("awaiting-approval", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.OFFER_LETTER, TODAY.plusDays(1)));
        store(toManager("completed", DocumentRequestStatus.FULFILLED, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1)));
        store(toManager("rejected", DocumentRequestStatus.REJECTED, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1)));

        service.runForDate(TODAY);

        assertThat(sentEvents(2)).extracting(NotificationEvent::getRelatedEntityId)
                .containsExactlyInAnyOrder("returned-by-admin", "approved-to-send");
    }

    @Test
    void employeeRequestOverdueAlertsGoToTheAssignedHrFromTheActivationDate() {
        activatedOn = LocalDate.of(2026, 10, 1);
        store(toManager("before-activation", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, LocalDate.of(2026, 9, 30)));
        store(toManager("after-activation", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, LocalDate.of(2026, 10, 9)));
        store(toManager("awaiting-approval", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.OFFER_LETTER, LocalDate.of(2026, 10, 9)));

        service.runForDate(TODAY);

        NotificationEvent alert = sentEvents(1).getFirst();
        assertThat(alert.getRelatedEntityId()).isEqualTo("after-activation");
        assertThat(alert.getRecipientUserId()).isEqualTo("hr-1");
        assertThat(alert.getType()).isEqualTo(NotificationType.DOCUMENT_REQUEST_OVERDUE);
        assertThat(alert.getMessage()).contains("employee-1 requested").contains("not completed yet");
    }

    @Test
    void aSingleCompanyAdminIsTheFallbackWhenTheAssignedHrIsUnavailable() {
        store(toManager("letter", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1)));
        inactive("hr-1");
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("admin-newer", LocalDateTime.of(2026, 5, 1, 9, 0)), admin("admin-oldest", LocalDateTime.of(2025, 1, 1, 9, 0))));

        var result = service.runForDate(TODAY);

        assertThat(result.sent()).isEqualTo(1);
        NotificationEvent event = sentEvents(1).getFirst();
        assertThat(event.getRecipientUserId()).isEqualTo("admin-oldest");
        assertThat(event.getActionPath()).startsWith("/myhr/admin/document-requests/");
        assertThat(event.getMessage()).contains("because the assigned HR contact is unavailable");
        ArgumentCaptor<DocumentRequestReminder> saved = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders).save(saved.capture());
        assertThat(saved.getValue().getRecipientUserId()).isEqualTo("admin-oldest");
        assertThat(saved.getValue().getDetail()).isEqualTo(DocumentRequestReminderService.FALLBACK_DETAIL);
    }

    @Test
    void theFallbackIsNeverThePersonWaitingAndIsSkippedWhenNoAdminIsAvailable() {
        // The HR requester of an overdue HR-to-employee request is unavailable and there is no active Company Admin.
        activatedOn = LocalDate.of(2026, 10, 1);
        DocumentRequest overdue = request("overdue", TODAY.minusDays(2));
        store(overdue);
        inactive("hr-1");
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)).thenReturn(List.of());

        var result = service.runForDate(TODAY);

        assertThat(result.skipped()).isEqualTo(1);
        verify(orchestrator, never()).notify(any());

        // An admin who asked their own manager for a document is never picked as the fallback for it.
        stored.remove("overdue");
        DocumentRequest ownRequest = toManager("own", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(3));
        ownRequest.setRequestedByUserId("admin-requester");
        store(ownRequest);
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("admin-requester", LocalDateTime.of(2024, 1, 1, 9, 0)), admin("admin-other", LocalDateTime.of(2025, 1, 1, 9, 0))));
        service.runForDate(TODAY);
        assertThat(sentEvents(1).getFirst().getRecipientUserId()).isEqualTo("admin-other");
    }

    @Test
    void hrToEmployeeOverdueAlertsNowFallBackToOneAdminWhenTheHrRequesterIsUnavailable() {
        activatedOn = LocalDate.of(2026, 10, 1);
        store(request("overdue", TODAY.minusDays(2)));
        inactive("hr-1");
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("admin-1", LocalDateTime.of(2025, 1, 1, 9, 0))));

        service.runForDate(TODAY);

        NotificationEvent alert = sentEvents(1).getFirst();
        assertThat(alert.getRecipientUserId()).isEqualTo("admin-1");
        assertThat(alert.getTitle()).isEqualTo("Document request overdue");
    }

    @Test
    void anEmployeeIsNeverReplacedByAnAdminWhenInactive() {
        store(request("r1", TODAY.plusDays(1)));
        inactive("employee-1");
        when(users.findByOrganizationIdAndRoleAndAccountStatus(any(), any(), any()))
                .thenReturn(List.of(admin("admin-1", LocalDateTime.of(2025, 1, 1, 9, 0))));

        assertThat(service.runForDate(TODAY).skipped()).isEqualTo(1);
        verify(orchestrator, never()).notify(any());
    }

    @Test
    void anEmployeeRequestSentForApprovalAfterTheQueryIsNotReminded() {
        store(toManager("letter", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1)));
        DocumentRequest nowAwaitingApproval = toManager("letter", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(1));
        when(mongo.findById(eq("letter"), eq(DocumentRequest.class))).thenReturn(nowAwaitingApproval);

        assertThat(service.runForDate(TODAY).skipped()).isEqualTo(1);
        verify(orchestrator, never()).notify(any());
    }

    // ---- Signed I-983 --------------------------------------------------------------------------------------------

    @Test
    void remindsTheAssignedHrToReviewASubmittedI983() {
        store(toManager("i983", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.I983, TODAY.plusDays(3)));
        store(toManager("i983-approved", DocumentRequestStatus.APPROVED, RequestedDocumentType.I983, TODAY.plusDays(3)));

        service.runForDate(TODAY);

        NotificationEvent event = sentEvents(1).getFirst();
        assertThat(event.getRelatedEntityId()).isEqualTo("i983");
        assertThat(event.getRecipientUserId()).isEqualTo("hr-1");
        assertThat(event.getTitle()).isEqualTo("Signed I-983 review due soon");
        assertThat(event.getMessage()).contains("employee-1's Signed I-983 is due in 3 days");
        assertThat(event.getActionLabel()).isEqualTo("Review I-983");
        ArgumentCaptor<DocumentRequestReminder> claim = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders).insert(claim.capture());
        assertThat(claim.getValue().getReminderType()).isEqualTo(DocumentRequestReminderType.DUE_IN_3_DAYS);
    }

    @Test
    void remindsTheEmployeeToCorrectAReturnedI983WithItsOwnReminderTypes() {
        activatedOn = LocalDate.of(2026, 10, 1);
        store(toManager("returned-soon", DocumentRequestStatus.RETURNED_FOR_CORRECTION, RequestedDocumentType.I983, TODAY.plusDays(1)));
        store(toManager("returned-late", DocumentRequestStatus.RETURNED_FOR_CORRECTION, RequestedDocumentType.I983, TODAY.minusDays(4)));

        service.runForDate(TODAY);

        List<NotificationEvent> events = sentEvents(2);
        assertThat(events).extracting(NotificationEvent::getRecipientUserId).containsOnly("employee-1");
        assertThat(events).extracting(NotificationEvent::getTitle)
                .containsExactlyInAnyOrder("Signed I-983 correction due soon", "Signed I-983 correction overdue");
        assertThat(events.getFirst().getActionPath()).startsWith("/myhr/employee/document-requests/");
        ArgumentCaptor<DocumentRequestReminder> claims = ArgumentCaptor.forClass(DocumentRequestReminder.class);
        verify(reminders, times(2)).insert(claims.capture());
        assertThat(claims.getAllValues()).extracting(DocumentRequestReminder::getReminderType)
                .containsExactlyInAnyOrder(DocumentRequestReminderType.CORRECTION_DUE_IN_1_DAY, DocumentRequestReminderType.CORRECTION_OVERDUE);
    }

    @Test
    void anI983ReviewReminderAlreadySentDoesNotBlockItsCorrectionReminder() {
        store(toManager("i983", DocumentRequestStatus.RETURNED_FOR_CORRECTION, RequestedDocumentType.I983, TODAY.plusDays(1)));
        existingClaims.add(claim("i983", DocumentRequestReminderType.DUE_IN_1_DAY, TODAY.plusDays(1), DocumentRequestReminder.Status.SENT, 1));

        service.runForDate(TODAY);

        assertThat(sentEvents(1).getFirst().getTitle()).isEqualTo("Signed I-983 correction due soon");
    }

    @Test
    void noCatchUpRemindersForMissedExactDays() {
        // Due in 2 days: the 3-day reminder day has passed and the 1-day day has not come yet.
        store(request("hr-to-employee", TODAY.plusDays(2)));
        store(toManager("employee-to-hr", DocumentRequestStatus.PENDING, RequestedDocumentType.EMPLOYMENT_LETTER, TODAY.plusDays(2)));
        store(toManager("i983", DocumentRequestStatus.SUBMITTED, RequestedDocumentType.I983, TODAY.plusDays(2)));

        service.runForDate(TODAY);

        verify(orchestrator, never()).notify(any());
    }

    private List<NotificationEvent> sentEvents(int expected) {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(orchestrator, times(expected)).notify(captor.capture());
        return captor.getAllValues();
    }

    /** Applies the query's direction/status/document type/due-date range to the stored requests, like MongoDB would. */
    private List<DocumentRequest> matching(Query query) {
        Document criteria = query.getQueryObject();
        Document range = (Document) criteria.get("requiredBy");
        LocalDate from = (LocalDate) range.get("$gte");
        LocalDate toExclusive = (LocalDate) range.get("$lt");
        Object status = criteria.get("status");
        Object documentType = criteria.get("documentType");
        return stored.values().stream()
                .filter(r -> criteria.get("requestDirection").equals(r.getRequestDirection()))
                .filter(r -> status instanceof Document in ? ((java.util.Collection<?>) in.get("$in")).contains(r.getStatus())
                        : status == r.getStatus())
                .filter(r -> documentType == null
                        || (documentType instanceof Document ne ? ne.get("$ne") != r.getDocumentType() : documentType == r.getDocumentType()))
                .filter(r -> r.getRequiredBy() != null && !r.getRequiredBy().isBefore(from) && r.getRequiredBy().isBefore(toExclusive))
                .toList();
    }

    private void store(DocumentRequest request) {
        stored.put(request.getId(), request);
    }

    private DocumentRequest request(String id, LocalDate requiredBy) {
        return DocumentRequest.builder().id(id).organizationId("org-1").requestedByUserId("hr-1").requestedForUserId("employee-1")
                .requestTitle("Passport copy").requestDirection("TO_EMPLOYEE").status(DocumentRequestStatus.PENDING)
                .requiredBy(requiredBy).build();
    }

    private DocumentRequestReminder claim(String requestId, DocumentRequestReminderType type, LocalDate requiredBy,
                                          DocumentRequestReminder.Status status, int attempts) {
        return DocumentRequestReminder.builder().id("claim-" + requestId).organizationId("org-1").documentRequestId(requestId)
                .reminderType(type).requiredBy(requiredBy).recipientUserId("employee-1").status(status).attempts(attempts).build();
    }

    private User user(String id, AccountStatus status) {
        UserRole role = id.startsWith("hr") ? UserRole.HR : id.startsWith("admin") ? UserRole.COMPANY_ADMIN : UserRole.EMPLOYEE;
        return User.builder().id(id).loginId(id).organizationId("org-1").role(role).accountStatus(status).build();
    }

    private User admin(String id, LocalDateTime createdAt) {
        User admin = user(id, AccountStatus.ACTIVE);
        admin.setCreatedAt(createdAt);
        return admin;
    }

    /** An employee-to-HR request: employee-1 asked their assigned HR (hr-1). */
    private DocumentRequest toManager(String id, DocumentRequestStatus status, RequestedDocumentType type, LocalDate requiredBy) {
        return DocumentRequest.builder().id(id).organizationId("org-1").requestedByUserId("employee-1").requestedForUserId("hr-1")
                .requestTitle(type == RequestedDocumentType.I983 ? "Signed I-983" : "Employment letter").documentType(type)
                .requestDirection("TO_MANAGER").status(status).requiredBy(requiredBy).build();
    }

    private void inactive(String userId) {
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, AccountStatus.INACTIVE)));
    }
}
