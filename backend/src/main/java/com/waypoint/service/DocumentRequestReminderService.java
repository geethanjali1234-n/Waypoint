package com.waypoint.service;

import com.waypoint.config.DocumentRequestReminderProperties;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.DocumentRequestReminder;
import com.waypoint.entity.DocumentRequestReminderState;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.DocumentRequestReminderType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.service.DocumentRequestReminderRules.Recipient;
import com.waypoint.service.DocumentRequestReminderRules.Rule;
import com.waypoint.service.DocumentRequestReminderRules.Stage;
import com.waypoint.repository.DocumentRequestReminderRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends automatic reminders for document requests that have a due date while a clearly identified person has to act
 * on them (the workflows and recipients are defined in DocumentRequestReminderRules): a reminder 3 days and 1 day
 * before the due date (exact calendar days in the reminder time zone, never caught up later), and one overdue alert.
 * When the responsible HR user is unavailable, a single active Company Admin receives it instead (never all admins).
 *
 * Each reminder is claimed in document_request_reminders before it is sent (unique per request, type and due date),
 * so it is sent at most once. Overdue alerts only cover due dates on or after the activation date (the first enabled
 * run, stored in document_request_reminder_state), so requests that were already overdue before activation are never
 * alerted. There is no look-back window, so alerts that fall due during downtime are sent on the next run.
 *
 * Nothing runs unless DOCUMENT_REQUEST_REMINDERS_ENABLED=true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRequestReminderService {

    static final int MAX_ATTEMPTS = 3;
    static final String FALLBACK_DETAIL = "FALLBACK_COMPANY_ADMIN";
    private static final Set<UserRole> RESPONSIBLE_HR_ROLES = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);

    private final DocumentRequestReminderProperties properties;
    private final DocumentRequestReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final NotificationOrchestrator notificationOrchestrator;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /**
     * Daily run at the configured time in the reminder time zone. Uses the existing @EnableScheduling in
     * SchedulingConfig; a cron schedule never fires at startup. Does nothing unless reminders are enabled.
     */
    @Scheduled(cron = "${waypoint.myhr.document-request-reminders.scheduler-cron:0 0 8 * * *}",
            zone = "${waypoint.myhr.document-request-reminders.zone-id:America/Chicago}")
    public ReminderRunResult runDaily() {
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        if (!properties.isEnabled()) return ReminderRunResult.disabled(today);
        return runForDate(today);
    }

    public ReminderRunResult runForDate(LocalDate runDate) {
        if (!properties.isEnabled()) return ReminderRunResult.disabled(runDate);

        LocalDate activatedOn = activationDate(runDate);
        Counts counts = new Counts();
        for (Rule rule : DocumentRequestReminderRules.RULES) {
            process(rule, Stage.DUE_IN_3_DAYS, dueBetween(rule, runDate.plusDays(3), runDate.plusDays(4)), counts);
            process(rule, Stage.DUE_IN_1_DAY, dueBetween(rule, runDate.plusDays(1), runDate.plusDays(2)), counts);
            if (activatedOn.isBefore(runDate)) {
                process(rule, Stage.OVERDUE, dueBetween(rule, activatedOn, runDate), counts);
            }
        }
        log.info("Document request reminders run [date={}, sent={}, skipped={}, failed={}, alreadyHandled={}]",
                runDate, counts.sent, counts.skipped, counts.failed, counts.alreadyHandled);
        return new ReminderRunResult(true, runDate, activatedOn, counts.sent, counts.skipped, counts.failed, counts.alreadyHandled);
    }

    /** Returns the stored activation date, recording runDate as the activation date on the very first enabled run. */
    LocalDate activationDate(LocalDate runDate) {
        Query query = Query.query(Criteria.where("_id").is(DocumentRequestReminderState.ACTIVATION_ID));
        Update update = new Update().setOnInsert("activatedOn", runDate).setOnInsert("activatedAt", now());
        try {
            return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
                    DocumentRequestReminderState.class).getActivatedOn();
        } catch (DuplicateKeyException concurrentFirstRun) {
            // Another instance inserted it at the same moment; its value wins.
            return mongoTemplate.findById(DocumentRequestReminderState.ACTIVATION_ID, DocumentRequestReminderState.class)
                    .getActivatedOn();
        }
    }

    /** The rule's requests due in [from, toExclusive). */
    private List<DocumentRequest> dueBetween(Rule rule, LocalDate from, LocalDate toExclusive) {
        return mongoTemplate.find(Query.query(rule.scope().criteria().and("requiredBy").gte(from).lt(toExclusive)),
                DocumentRequest.class);
    }

    private void process(Rule rule, Stage stage, List<DocumentRequest> requests, Counts counts) {
        if (requests.isEmpty()) return;
        DocumentRequestReminderType type = rule.type(stage);
        Map<String, DocumentRequestReminder> existing = reminderRepository
                .findByDocumentRequestIdIn(requests.stream().map(DocumentRequest::getId).toList()).stream()
                .filter(claim -> claim.getReminderType() == type)
                .collect(Collectors.toMap(claim -> key(claim.getDocumentRequestId(), claim.getRequiredBy()),
                        Function.identity(), (first, second) -> first));
        for (DocumentRequest request : requests) {
            try {
                DocumentRequestReminder claim = claim(request, type, rule.recipient(stage).userId().apply(request),
                        existing.get(key(request.getId(), request.getRequiredBy())));
                if (claim == null) {
                    counts.alreadyHandled++;
                    continue;
                }
                deliver(rule, stage, request, claim, counts);
            } catch (RuntimeException failure) {
                counts.failed++;
                log.warn("Document request reminder could not be claimed [type={}, exceptionType={}]",
                        type, failure.getClass().getSimpleName());
            }
        }
    }

    /** Claims the reminder for this run, or returns null when it was already sent, skipped, in progress or out of retries. */
    private DocumentRequestReminder claim(DocumentRequest request, DocumentRequestReminderType type, String recipientUserId,
                                          DocumentRequestReminder existing) {
        if (existing == null) {
            LocalDateTime at = now();
            try {
                return reminderRepository.insert(DocumentRequestReminder.builder()
                        .organizationId(request.getOrganizationId()).documentRequestId(request.getId())
                        .reminderType(type).requiredBy(request.getRequiredBy())
                        .recipientUserId(recipientUserId)
                        .status(DocumentRequestReminder.Status.PROCESSING).attempts(1).createdAt(at).updatedAt(at).build());
            } catch (DuplicateKeyException claimedElsewhere) {
                existing = reminderRepository.findByDocumentRequestIdAndReminderTypeAndRequiredBy(
                        request.getId(), type, request.getRequiredBy()).orElse(null);
                if (existing == null) return null;
            }
        }
        if (existing.getStatus() != DocumentRequestReminder.Status.FAILED || existing.getAttempts() >= MAX_ATTEMPTS) return null;
        LocalDateTime at = now();
        if (reminderRepository.claimFailedForRetry(existing.getId(), MAX_ATTEMPTS, at) != 1) return null;
        existing.setStatus(DocumentRequestReminder.Status.PROCESSING);
        existing.setAttempts(existing.getAttempts() + 1);
        existing.setUpdatedAt(at);
        return existing;
    }

    private void deliver(Rule rule, Stage stage, DocumentRequest request, DocumentRequestReminder claim, Counts counts) {
        boolean handedOff = false;
        try {
            // Re-read so a request completed, moved to another step or re-dated since the query is not reminded.
            DocumentRequest current = mongoTemplate.findById(request.getId(), DocumentRequest.class);
            if (current == null || !rule.scope().matches(current) || !claim.getRequiredBy().equals(current.getRequiredBy())) {
                finish(claim, DocumentRequestReminder.Status.SKIPPED, "REQUEST_NOT_ACTIONABLE");
                counts.skipped++;
                return;
            }
            Recipient recipientRule = rule.recipient(stage);
            User recipient = available(recipientRule, recipientRule.userId().apply(current), current.getOrganizationId());
            boolean fallback = false;
            if (recipient == null && recipientRule.companyAdminFallback()) {
                recipient = fallbackCompanyAdmin(current);
                fallback = recipient != null;
            }
            if (recipient == null) {
                finish(claim, DocumentRequestReminder.Status.SKIPPED, "RECIPIENT_NOT_ACTIVE");
                counts.skipped++;
                return;
            }
            claim.setRecipientUserId(recipient.getId());
            notificationOrchestrator.notify(event(rule, stage, current, recipient, fallback));
            handedOff = true;
            finish(claim, DocumentRequestReminder.Status.SENT, fallback ? FALLBACK_DETAIL : null);
            counts.sent++;
        } catch (RuntimeException failure) {
            if (handedOff) {
                // Already handed to the orchestrator: never mark FAILED (that would allow a resend). The claim
                // stays PROCESSING, which is never resent automatically.
                counts.sent++;
                log.warn("Document request reminder sent but its status could not be saved [type={}, exceptionType={}]",
                        claim.getReminderType(), failure.getClass().getSimpleName());
                return;
            }
            counts.failed++;
            log.warn("Document request reminder failed [type={}, attempt={}, exceptionType={}]",
                    claim.getReminderType(), claim.getAttempts(), failure.getClass().getSimpleName());
            try {
                finish(claim, DocumentRequestReminder.Status.FAILED, failure.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                // The claim stays PROCESSING and is never resent automatically.
            }
        }
    }

    private NotificationEvent event(Rule rule, Stage stage, DocumentRequest request, User recipient, boolean fallback) {
        String due = DocumentRequestService.REQUIRED_BY_FORMAT.format(request.getRequiredBy());
        String when = stage == Stage.DUE_IN_1_DAY ? "tomorrow" : "in 3 days";
        String otherPersonId = rule.otherPersonId().apply(request);
        String who = null;
        if (otherPersonId != null) {
            User other = userRepository.findById(otherPersonId).orElse(null);
            who = other == null ? null : DocumentRequestService.participantLabel(
                    other, employeeProfileRepository.findByUserId(other.getId()).orElse(null));
        }
        DocumentRequestReminderRules.Text text = rule.wording().text(stage, request, who, when, due);
        String message = fallback ? text.message() + " You are receiving this because the assigned HR contact is unavailable."
                : text.message();
        return NotificationEvent.builder().organizationId(request.getOrganizationId()).recipientUserId(recipient.getId())
                .relatedEntityId(request.getId()).email(true)
                .actionPath("/myhr/" + DocumentRequestService.personaSegment(recipient.getRole()) + "/document-requests/" + request.getId())
                .type(stage == Stage.OVERDUE ? NotificationType.DOCUMENT_REQUEST_OVERDUE : NotificationType.DOCUMENT_REQUEST_REMINDER)
                .title(text.title()).message(message).actionLabel(text.actionLabel()).build();
    }

    /** The user if they exist in this organization, are active and (for HR recipients) still hold an HR or Company Admin role. */
    private User available(Recipient recipient, String userId, String organizationId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .filter(user -> organizationId != null && organizationId.equals(user.getOrganizationId()))
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE)
                .filter(user -> !recipient.companyAdminFallback() || RESPONSIBLE_HR_ROLES.contains(user.getRole()))
                .orElse(null);
    }

    /**
     * One active Company Admin of the request's organization, chosen deterministically (earliest account first),
     * never the person who is waiting on the request. Used only when the responsible HR user is unavailable.
     */
    private User fallbackCompanyAdmin(DocumentRequest request) {
        if (request.getOrganizationId() == null) return null;
        return userRepository.findByOrganizationIdAndRoleAndAccountStatus(request.getOrganizationId(), UserRole.COMPANY_ADMIN,
                        AccountStatus.ACTIVE).stream()
                .filter(admin -> !Objects.equals(admin.getId(), request.getRequestedByUserId())
                        && !Objects.equals(admin.getId(), request.getRequestedForUserId()))
                .min(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private void finish(DocumentRequestReminder claim, DocumentRequestReminder.Status status, String detail) {
        claim.setStatus(status);
        claim.setDetail(detail);
        claim.setUpdatedAt(now());
        reminderRepository.save(claim);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(properties.zone()));
    }

    private static String key(String documentRequestId, LocalDate requiredBy) {
        return documentRequestId + "|" + requiredBy;
    }

    private static final class Counts {
        int sent;
        int skipped;
        int failed;
        int alreadyHandled;
    }

    public record ReminderRunResult(boolean enabled, LocalDate runDate, LocalDate activatedOn,
                                    int sent, int skipped, int failed, int alreadyHandled) {
        static ReminderRunResult disabled(LocalDate runDate) {
            return new ReminderRunResult(false, runDate, null, 0, 0, 0, 0);
        }
    }
}
