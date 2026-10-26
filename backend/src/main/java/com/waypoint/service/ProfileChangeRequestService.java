package com.waypoint.service;

import com.waypoint.dto.myhr.CreateProfileChangeRequestRequest;
import com.waypoint.dto.myhr.ProfileChangeRequestDetailResponse;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.dto.myhr.ReportingManagerSummary;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.ProfileChangeRequestRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class ProfileChangeRequestService {

    private static final Logger log = LoggerFactory.getLogger(ProfileChangeRequestService.class);
    private static final Set<UserRole> REVIEWER_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);
    private static final Set<UserRole> REQUESTER_ROLES = Set.of(UserRole.EMPLOYEE, UserRole.HR);
    private static final Set<ProfileChangeField> SELF_SERVICE_FIELDS = Set.of(
            ProfileChangeField.NAME, ProfileChangeField.PERSONAL_EMAIL,
            ProfileChangeField.PHONE_NUMBER, ProfileChangeField.ADDRESS);
    private static final Set<String> ADDRESS_KEYS = Set.of("streetAddress", "city", "state", "zipCode");
    private static final Pattern PERSON_NAME = Pattern.compile("^[\\p{L}\\p{M}]+(?:[ '\\-][\\p{L}\\p{M}]+)*$");
    private static final Pattern PLACE_NAME = Pattern.compile("^[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int NAME_MAX = 100;
    private static final int EMAIL_MAX = 254;
    private static final int PHONE_MAX = 25;
    private static final int ADDRESS_MAX = 200;
    private static final Map<ProfileChangeField, String> FIELD_LABELS = Map.of(
            ProfileChangeField.NAME, "Name",
            ProfileChangeField.PERSONAL_EMAIL, "Personal Email",
            ProfileChangeField.PHONE_NUMBER, "Phone Number",
            ProfileChangeField.ADDRESS, "Address",
            ProfileChangeField.DESIGNATION, "Designation");
    private static final List<String> ADDRESS_DISPLAY_ORDER =
            List.of("streetAddress", "city", "state", "zipCode");

    private final ProfileChangeRequestRepository profileChangeRequestRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final UserRepository userRepository;
    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final AuditService auditService;
    private final NotificationOrchestrator notificationOrchestrator;

    public ProfileChangeRequestResponse create(String actorUserId, CreateProfileChangeRequestRequest input) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REQUESTER_ROLES, false);
        EmployeeProfile profile = employeeProfileRepository.findByUserId(actorUserId)
                .orElseThrow(() -> new BusinessException("Employee profile not found"));

        ProfileChangeField field = input.fieldName();
        if (field == null || !SELF_SERVICE_FIELDS.contains(field)) {
            throw new BusinessException("This profile field cannot be changed through self-service");
        }
        if (profileChangeRequestRepository.existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
                actor.organizationId(), actor.userId(), field, ProfileChangeStatus.PENDING)) {
            throw new BusinessException("A pending request already exists for this profile field");
        }

        Object requestedValue = validateRequestedValue(field, input.requestedValue());
        User reviewer = resolveReviewer(actor);
        LocalDateTime now = LocalDateTime.now();
        ProfileChangeRequest saved = profileChangeRequestRepository.save(ProfileChangeRequest.builder()
                .organizationId(actor.organizationId())
                .requesterUserId(actor.userId())
                .profileId(profile.getId())
                .assignedToUserId(reviewer.getId())
                .fieldName(field)
                .existingValue(extractCurrentValue(profile, field))
                .requestedValue(requestedValue)
                .requestReason(normalizeOptional(input.requestReason(), "Request reason"))
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        record(actor, AuditActionType.PROFILE_CHANGE_REQUESTED, saved, "Profile change request submitted");
        notifyProfileChangeSubmitted(actor, profile, reviewer, saved);
        return toResponse(saved);
    }

    private User resolveReviewer(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor) {
        ReportingAssignment assignment = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .orElseThrow(() -> new BusinessException("An active reporting manager is required"));

        if (!actor.organizationId().equals(assignment.getOrganizationId())
                || actor.userId().equals(assignment.getReportingManagerUserId())) {
            throw new BusinessException("A valid reporting manager is required");
        }
        User reviewer = userRepository.findById(assignment.getReportingManagerUserId())
                .orElseThrow(() -> new BusinessException("A valid reporting manager is required"));
        if (!actor.organizationId().equals(reviewer.getOrganizationId())
                || reviewer.getAccountStatus() != AccountStatus.ACTIVE
                || !REVIEWER_ROLES.contains(reviewer.getRole())) {
            throw new BusinessException("A valid active HR or Company Admin reporting manager is required");
        }
        return reviewer;
    }

    public List<ProfileChangeRequestResponse> listMine(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REQUESTER_ROLES, false);
        return enrich(profileChangeRequestRepository
                .findByOrganizationIdAndRequesterUserIdOrderByRequestedAtDesc(actor.organizationId(), actor.userId()));
    }

    public List<ProfileChangeRequestResponse> listForAdmin(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        List<ProfileChangeRequest> requests = actor.role() == UserRole.HR
                ? profileChangeRequestRepository.findByOrganizationIdAndAssignedToUserIdOrderByRequestedAtDesc(
                        actor.organizationId(), actor.userId())
                : profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc(actor.organizationId());
        return enrich(requests);
    }

    public ProfileChangeRequestDetailResponse getDetailForAdmin(String actorUserId, String requestId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        ProfileChangeRequest request = loadInOrganization(requestId, actor.organizationId());
        requireAssignedReviewer(actor, request);
        EmployeeProfile profile = employeeProfileRepository.findByUserId(request.getRequesterUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for this request"));
        return new ProfileChangeRequestDetailResponse(
                request.getId(), request.getFieldName(), request.getExistingValue(), request.getRequestedValue(),
                request.getRequestReason(), request.getStatus(), request.getProcessedByUserId(),
                request.getProcessingComment(), request.getRequestedAt(), request.getProcessedAt(),
                request.getCreatedAt(), request.getUpdatedAt(),
                new ProfileChangeRequestDetailResponse.RequesterSummary(
                        profile.getUserId(), profile.getEmployeeId(), profile.getName(), null),
                resolveReportingManager(request.getAssignedToUserId()));
    }

    public ProfileChangeRequest approve(String actorUserId, String requestId, String processingComment) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        ProfileChangeRequest request = loadPending(requestId, actor.organizationId());
        requireAssignedReviewer(actor, request);
        EmployeeProfile profile = employeeProfileRepository.findByUserId(request.getRequesterUserId())
                .orElseThrow(() -> new BusinessException("Employee profile not found for this request"));

        Object currentValue = extractCurrentValue(profile, request.getFieldName());
        if (!Objects.equals(currentValue, normalizeStoredValue(request.getFieldName(), request.getExistingValue()))) {
            throw new BusinessException(
                    "The profile changed after this request was submitted. Submit a new profile change request.");
        }

        ProfileSnapshot snapshot = ProfileSnapshot.capture(profile);
        applyApprovedValue(profile, request.getFieldName(),
                validateRequestedValue(request.getFieldName(), request.getRequestedValue()));
        LocalDateTime priorUpdatedAt = profile.getUpdatedAt();
        profile.setUpdatedAt(LocalDateTime.now());
        employeeProfileRepository.save(profile);

        try {
            finalizeDecision(request, ProfileChangeStatus.APPROVED, actor.userId(), processingComment);
        } catch (RuntimeException original) {
            snapshot.restore(profile);
            profile.setUpdatedAt(priorUpdatedAt);
            try {
                employeeProfileRepository.save(profile);
            } catch (RuntimeException compensationFailure) {
                original.addSuppressed(compensationFailure);
                log.error("Failed to restore employee profile after profile-change approval finalization failed", compensationFailure);
            }
            throw original;
        }

        record(actor, AuditActionType.PROFILE_CHANGE_APPROVED, request, "Profile change request approved");
        notifyProfileChangeApproved(request);
        return request;
    }

    public ProfileChangeRequest reject(String actorUserId, String requestId, String processingComment) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        ProfileChangeRequest request = loadPending(requestId, actor.organizationId());
        requireAssignedReviewer(actor, request);
        finalizeDecision(request, ProfileChangeStatus.REJECTED, actor.userId(), processingComment);
        record(actor, AuditActionType.PROFILE_CHANGE_REJECTED, request, "Profile change request rejected");
        notifyProfileChangeRejected(request);
        return request;
    }

    private ProfileChangeRequest loadInOrganization(String requestId, String organizationId) {
        return profileChangeRequestRepository.findById(requestId)
                .filter(request -> organizationId.equals(request.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Profile change request not found"));
    }

    private ProfileChangeRequest loadPending(String requestId, String organizationId) {
        ProfileChangeRequest request = loadInOrganization(requestId, organizationId);
        if (request.getStatus() != ProfileChangeStatus.PENDING) {
            throw new BusinessException("Profile change request has already been processed");
        }
        return request;
    }

    private void requireAssignedReviewer(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, ProfileChangeRequest request) {
        if (actor.role() == UserRole.HR && !actor.userId().equals(request.getAssignedToUserId())) {
            throw new ForbiddenException("This request is not assigned to you");
        }
    }

    private void finalizeDecision(ProfileChangeRequest request, ProfileChangeStatus status,
                                  String actorUserId, String processingComment) {
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(status);
        request.setProcessedByUserId(actorUserId);
        request.setProcessingComment(normalizeOptional(processingComment, "Processing comment"));
        request.setProcessedAt(now);
        request.setUpdatedAt(now);
        profileChangeRequestRepository.save(request);
    }

    private Object validateRequestedValue(ProfileChangeField field, Object value) {
        return switch (field) {
            case NAME -> validateName(value);
            case PERSONAL_EMAIL -> validateEmail(value);
            case PHONE_NUMBER -> validatePhone(value);
            case ADDRESS -> validateAddress(value);
            case DESIGNATION -> throw new BusinessException("Designation is managed through Employee Management");
        };
    }

    private Map<String, String> validateName(Object value) {
        Map<?, ?> map = requireMap(value, "Name");
        if (!map.keySet().equals(Set.of("firstName", "lastName"))) {
            throw new BusinessException("Name requires only firstName and lastName");
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("firstName", validName(map.get("firstName"), "First name"));
        result.put("lastName", validName(map.get("lastName"), "Last name"));
        return result;
    }

    private String validateEmail(Object value) {
        String email = requiredString(value, "Personal email", EMAIL_MAX).toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw new BusinessException("Enter a valid personal email");
        }
        return email;
    }

    private String validatePhone(Object value) {
        String phone = requiredString(value, "Phone number", PHONE_MAX);
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) throw new BusinessException("Enter a valid 10-digit phone number");
        return "+1" + digits;
    }

    private String validName(Object value, String label) {
        String name = requiredString(value, label, NAME_MAX);
        if (!PERSON_NAME.matcher(name).matches()) {
            throw new BusinessException(label + " contains unsupported characters");
        }
        return name;
    }

    private Map<String, String> validateAddress(Object value) {
        Map<?, ?> map = requireMap(value, "Address");
        if (map.isEmpty() || !ADDRESS_KEYS.containsAll(map.keySet())) {
            throw new BusinessException("Address contains an unsupported field");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : ADDRESS_KEYS) {
            if (map.containsKey(key)) {
                String item = requiredString(map.get(key), addressLabel(key), ADDRESS_MAX);
                if ("zipCode".equals(key) && !item.matches("\\d{5}")) {
                    throw new BusinessException("Enter a valid 5-digit ZIP code");
                }
                if (("city".equals(key) || "state".equals(key)) && !PLACE_NAME.matcher(item).matches()) {
                    throw new BusinessException(addressLabel(key) + " may contain letters and spaces only");
                }
                result.put(key, item);
            }
        }
        return result;
    }

    private Map<?, ?> requireMap(Object value, String label) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new BusinessException(label + " must be a structured value");
        }
        return map;
    }

    private String requiredString(Object value, String label, int maxLength) {
        if (!(value instanceof String string)) {
            throw new BusinessException(label + " must be text");
        }
        String normalized = string.trim();
        if (normalized.isEmpty()) throw new BusinessException(label + " is required");
        if (normalized.length() > maxLength) throw new BusinessException(label + " is too long");
        return normalized;
    }

    private String addressLabel(String key) {
        return switch (key) {
            case "streetAddress" -> "Street address";
            case "zipCode" -> "ZIP code";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    private String normalizeOptional(String value, String label) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > 1000) throw new BusinessException(label + " is too long");
        return normalized.isEmpty() ? null : normalized;
    }

    private Object normalizeStoredValue(ProfileChangeField field, Object value) {
        return switch (field) {
            case NAME -> validateName(value);
            case PERSONAL_EMAIL -> value == null ? "" : String.valueOf(value).trim();
            case PHONE_NUMBER -> value == null ? "" : validatePhone(value);
            case ADDRESS -> normalizeAddressBaseline(value);
            case DESIGNATION -> value;
        };
    }

    private Map<String, String> normalizeAddressBaseline(Object value) {
        Map<?, ?> map = requireMap(value, "Address");
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : ADDRESS_KEYS) {
            Object item = map.get(key);
            if (item instanceof String string && !string.isBlank()) result.put(key, string.trim());
        }
        return result;
    }

    private Object extractCurrentValue(EmployeeProfile profile, ProfileChangeField field) {
        return switch (field) {
            case NAME -> {
                Map<String, String> name = new LinkedHashMap<>();
                name.put("firstName", normalizeExisting(profile.getFirstName()));
                name.put("lastName", normalizeExisting(profile.getLastName()));
                yield name;
            }
            case PERSONAL_EMAIL -> normalizeExisting(profile.getPersonalEmail()).toLowerCase();
            case PHONE_NUMBER -> profile.getPhoneNumber() == null || profile.getPhoneNumber().isBlank()
                    ? "" : validatePhone(profile.getPhoneNumber());
            case ADDRESS -> {
                Map<String, String> address = new LinkedHashMap<>();
                putPresent(address, "streetAddress", profile.getStreetAddress());
                putPresent(address, "city", profile.getCity());
                putPresent(address, "state", profile.getState());
                putPresent(address, "zipCode", profile.getZipCode());
                yield address;
            }
            case DESIGNATION -> normalizeExisting(profile.getDesignation());
        };
    }

    private String normalizeExisting(String value) {
        return value == null ? "" : value.trim();
    }

    private void putPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }

    @SuppressWarnings("unchecked")
    private void applyApprovedValue(EmployeeProfile profile, ProfileChangeField field, Object value) {
        switch (field) {
            case NAME -> {
                Map<String, String> name = (Map<String, String>) value;
                profile.setFirstName(name.get("firstName"));
                profile.setLastName(name.get("lastName"));
                profile.setName(name.get("firstName") + " " + name.get("lastName"));
            }
            case PERSONAL_EMAIL -> profile.setPersonalEmail((String) value);
            case PHONE_NUMBER -> profile.setPhoneNumber((String) value);
            case ADDRESS -> ((Map<String, String>) value).forEach((key, item) -> {
                switch (key) {
                    case "streetAddress" -> profile.setStreetAddress(item);
                    case "city" -> profile.setCity(item);
                    case "state" -> profile.setState(item);
                    case "zipCode" -> profile.setZipCode(item);
                    default -> throw new BusinessException("Address contains an unsupported field");
                }
            });
            case DESIGNATION -> throw new BusinessException("Designation is managed through Employee Management");
        }
    }

    private List<ProfileChangeRequestResponse> enrich(List<ProfileChangeRequest> requests) {
        Set<String> ids = new LinkedHashSet<>();
        requests.forEach(request -> {
            if (request.getRequesterUserId() != null) ids.add(request.getRequesterUserId());
            if (request.getAssignedToUserId() != null) ids.add(request.getAssignedToUserId());
        });
        Map<String, EmployeeProfile> profiles = employeeProfileRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (a, b) -> a));
        Set<String> userLookupIds = new LinkedHashSet<>();
        requests.forEach(request -> {
            if (request.getRequesterUserId() != null) userLookupIds.add(request.getRequesterUserId());
            if (request.getAssignedToUserId() != null && !profiles.containsKey(request.getAssignedToUserId())) {
                userLookupIds.add(request.getAssignedToUserId());
            }
        });
        Map<String, User> users = userLookupIds.isEmpty() ? Map.of()
                : StreamSupport.stream(userRepository.findAllById(userLookupIds).spliterator(), false)
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        return requests.stream().map(request -> toResponse(request, profiles, users)).toList();
    }

    public ProfileChangeRequestResponse toResponse(ProfileChangeRequest request) {
        return toResponse(request, Map.of(), Map.of());
    }

    private ProfileChangeRequestResponse toResponse(ProfileChangeRequest request,
            Map<String, EmployeeProfile> profiles, Map<String, User> users) {
        EmployeeProfile requesterProfile = profiles.get(request.getRequesterUserId());
        User requester = users.get(request.getRequesterUserId());
        return new ProfileChangeRequestResponse(
                request.getId(), request.getRequesterUserId(), request.getProfileId(), request.getAssignedToUserId(),
                request.getFieldName(), request.getExistingValue(), request.getRequestedValue(),
                request.getRequestReason(), request.getStatus(), request.getProcessedByUserId(),
                request.getProcessingComment(), request.getRequestedAt(), request.getProcessedAt(),
                request.getCreatedAt(), request.getUpdatedAt(),
                requesterProfile == null ? null : requesterProfile.getEmployeeId(),
                requesterProfile == null ? null : requesterProfile.getName(),
                resolveReportingManagerSummary(request.getAssignedToUserId(), profiles, users),
                requester == null || requester.getRole() == null ? null : requester.getRole().name());
    }

    private ReportingManagerSummary resolveReportingManager(String id) {
        if (id == null) return null;
        return employeeProfileRepository.findByUserId(id)
                .map(profile -> new ReportingManagerSummary(profile.getUserId(), profile.getEmployeeId(),
                        profile.getName(), null, null))
                .or(() -> userRepository.findById(id).map(this::managerFromUser))
                .orElse(null);
    }

    private ReportingManagerSummary resolveReportingManagerSummary(String id,
            Map<String, EmployeeProfile> profiles, Map<String, User> users) {
        if (id == null) return null;
        EmployeeProfile profile = profiles.get(id);
        return profile != null
                ? new ReportingManagerSummary(id, profile.getEmployeeId(), profile.getName(), null, null)
                : users.containsKey(id) ? managerFromUser(users.get(id)) : null;
    }

    private ReportingManagerSummary managerFromUser(User user) {
        return new ReportingManagerSummary(user.getId(), null, null, user.getLoginId(), user.getEmail());
    }

    private String personaSegment(UserRole role) {
        if (role == UserRole.COMPANY_ADMIN) return "admin";
        if (role == UserRole.HR) return "hr";
        return "employee";
    }

    /**
     * Notifies the current reviewer (the employee's active reporting manager, always
     * HR or Company Admin - see {@link #resolveReviewer}) and, separately, every
     * active Company Admin in the organization. When the reviewer already is a
     * Company Admin they are not notified twice.
     */
    private void notifyProfileChangeSubmitted(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            EmployeeProfile profile, User reviewer, ProfileChangeRequest saved) {
        String fieldLabel = FIELD_LABELS.getOrDefault(saved.getFieldName(), saved.getFieldName().name());
        String employeeId = profile.getEmployeeId();
        String employeeLabel = employeeId == null || employeeId.isBlank()
                ? profile.getName() : profile.getName() + " (" + employeeId + ")";
        String message = employeeLabel + " submitted a profile change request for " + fieldLabel + ".";

        notifySafely(() -> notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(reviewer.getId())
                .type(NotificationType.PROFILE_CHANGE_SUBMITTED).title("Profile change request submitted").message(message)
                .relatedEntityId(saved.getId()).actionPath(assignedReviewTarget(personaSegment(reviewer.getRole()), saved.getId())).email(true).actionLabel("Review Request").build()));

        userRepository.findByOrganizationIdAndRoleIn(actor.organizationId(), List.of(UserRole.COMPANY_ADMIN)).stream()
                .filter(admin -> admin.getAccountStatus() == AccountStatus.ACTIVE)
                .filter(admin -> !admin.getId().equals(reviewer.getId()))
                .forEach(admin -> notifySafely(() -> notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(admin.getId())
                .type(NotificationType.PROFILE_CHANGE_SUBMITTED).title("Profile change request submitted").message(message)
                .relatedEntityId(saved.getId()).actionPath(assignedReviewTarget("admin", saved.getId())).email(true).actionLabel("Review Request").build())));
    }

    /**
     * A "submitted" notification exists specifically so the reviewer can act on
     * that request - landing on the generic two-card Profile Change Requests
     * page (Request a Change / Assigned Requests) would still require them to
     * navigate again. ?view=assigned takes HR straight past that card menu (a
     * no-op for Company Admin, who has no card menu); ?requestId lets the page
     * open that exact request for review once its authorized queue has loaded
     * (see ProfileChangeRequestsPage's requestId auto-open effect) rather than
     * just the section it lives in.
     */
    private String assignedReviewTarget(String persona, String requestId) {
        return "/myhr/" + persona + "/profile-change-requests?view=assigned&requestId=" + requestId;
    }

    private void notifyProfileChangeApproved(ProfileChangeRequest request) {
        String fieldLabel = FIELD_LABELS.getOrDefault(request.getFieldName(), request.getFieldName().name());
        String oldValue = formatChangeValue(request.getFieldName(), request.getExistingValue());
        String newValue = formatChangeValue(request.getFieldName(), request.getRequestedValue());
        String message = oldValue.isBlank()
                ? "Your profile change request was approved. " + fieldLabel + " was updated to \"" + newValue + "\"."
                : "Your profile change request was approved. " + fieldLabel + " was updated from \""
                        + oldValue + "\" to \"" + newValue + "\".";

        User requester = userRepository.findById(request.getRequesterUserId()).orElse(null);
        String persona = requester == null ? "employee" : personaSegment(requester.getRole());
        notifySafely(() -> notificationOrchestrator.notify(NotificationEvent.builder().organizationId(request.getOrganizationId()).recipientUserId(request.getRequesterUserId())
                .type(NotificationType.PROFILE_CHANGE_APPROVED).title("Profile change request approved").message(message)
                .relatedEntityId(request.getId()).actionPath(ownRequestTarget(persona, request.getId())).email(true).actionLabel("View Request").build()));
    }

    /** The requester's own Profile Change history has no card menu to skip past - only requestId is needed to open the specific request. */
    private String ownRequestTarget(String persona, String requestId) {
        return "/myhr/" + persona + "/profile-change-requests?requestId=" + requestId;
    }

    private void notifyProfileChangeRejected(ProfileChangeRequest request) {
        String comment = request.getProcessingComment();
        String message = "Your profile change request was rejected."
                + (comment == null || comment.isBlank() ? "" : " Reason: " + comment);

        User requester = userRepository.findById(request.getRequesterUserId()).orElse(null);
        String persona = requester == null ? "employee" : personaSegment(requester.getRole());
        notifySafely(() -> notificationOrchestrator.notify(NotificationEvent.builder().organizationId(request.getOrganizationId()).recipientUserId(request.getRequesterUserId())
                .type(NotificationType.PROFILE_CHANGE_REJECTED).title("Profile change request rejected").message(message)
                .relatedEntityId(request.getId()).actionPath(ownRequestTarget(persona, request.getId())).email(true).actionLabel("View Request").build()));
    }

    /**
     * Notification creation is secondary to the profile-change decision itself -
     * a transient failure here must never make an otherwise successful
     * submission/approval/rejection look like it failed.
     */
    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("Profile change notification could not be created", failure);
        }
    }

    /** Renders a stored NAME/ADDRESS map or a plain scalar as human-readable text for a notification. */
    private String formatChangeValue(ProfileChangeField field, Object value) {
        if (value == null) return "";
        if (field == ProfileChangeField.NAME && value instanceof Map<?, ?> map) {
            return (nullToEmpty(map.get("firstName")) + " " + nullToEmpty(map.get("lastName"))).trim();
        }
        if (field == ProfileChangeField.ADDRESS && value instanceof Map<?, ?> map) {
            return ADDRESS_DISPLAY_ORDER.stream()
                    .map(map::get)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(part -> !part.isBlank())
                    .collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void record(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
                        AuditActionType action, ProfileChangeRequest request, String description) {
        Map<String, Object> previousValue = new LinkedHashMap<>();
        previousValue.put("fieldName", request.getFieldName().name());
        previousValue.put("value", request.getExistingValue());

        Map<String, Object> updatedValue = new LinkedHashMap<>();
        updatedValue.put("fieldName", request.getFieldName().name());
        updatedValue.put("value", request.getRequestedValue());
        updatedValue.put("status", request.getStatus().name());

        auditService.record(actor, action, "PROFILE_CHANGE_REQUEST", request.getId(),
                request.getFieldName().name(), description, previousValue, updatedValue);
    }

    private record ProfileSnapshot(String name, String firstName, String lastName, String personalEmail,
                                   String phoneNumber, String streetAddress, String city, String state,
                                   String zipCode) {
        static ProfileSnapshot capture(EmployeeProfile profile) {
            return new ProfileSnapshot(profile.getName(), profile.getFirstName(), profile.getLastName(),
                    profile.getPersonalEmail(), profile.getPhoneNumber(), profile.getStreetAddress(),
                    profile.getCity(), profile.getState(), profile.getZipCode());
        }

        void restore(EmployeeProfile profile) {
            profile.setName(name);
            profile.setFirstName(firstName);
            profile.setLastName(lastName);
            profile.setPersonalEmail(personalEmail);
            profile.setPhoneNumber(phoneNumber);
            profile.setStreetAddress(streetAddress);
            profile.setCity(city);
            profile.setState(state);
            profile.setZipCode(zipCode);
        }
    }
}
