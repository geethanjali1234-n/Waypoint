package com.waypoint.service;

import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.RequestedDocumentType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies questions about a person's own Waypoint records - and the closely related "do I have any
 * pending ...", another-person and approval-queue count questions - by subject and aspect rather than by
 * exact wording, so natural variations route to the same deterministic lookup instead of to workflow
 * how-to guidance or an AI provider. Only classifies; {@link MyHrChatService} answers through the existing
 * authorized services. Mirrored for navigation shortcuts by the frontend's waviIntents.js, and both are
 * checked against the shared case table frontend/src/test/fixtures/waviIntentCases.json.
 */
final class MyHrOwnRecordIntent {

    enum Subject { PROFILE_CHANGE, TIMESHEET, DOCUMENT_REQUEST, OFFER_LETTER, PERSONAL_RECORD }

    enum Aspect { STATUS, APPROVED, REJECTED, PENDING, APPROVED_DATE, REQUESTED_DATE, REJECTION_REASON, UPDATED }

    enum Kind {
        /** The signed-in user's own record. */
        OWN,
        /** "Do I have any pending ...": an Employee's own requests; an HR or Company Admin reviewer's queue. */
        PENDING_CHECK,
        /** Someone else's record, named or identified by an employee ID. */
        OTHER_PERSON,
        /** A queue count no exact queue question matched. */
        QUEUE_COUNT
    }

    /** {@code field} is set only for a single profile field; {@code documentType} only for a named document. */
    record Intent(Kind kind, Subject subject, Aspect aspect, ProfileChangeField field, RequestedDocumentType documentType) {
    }

    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:(?:hey|hi|hello),? )?(?:wavi,? )?(?:(?:so|ok|okay|um),? )?"
                    + "(?:(?:(?:can|could|would|will) you (?:please )?(?:tell me|show me|check|let me know|find out|look up|confirm)"
                    + "|please (?:tell me|show me|check|let me know|confirm)|i(?:'d| would) like to know|i want to know"
                    + "|do you know|tell me|let me know|check|please)(?: (?:if|whether))?\\s+)*");
    // How-to, location and policy questions keep their existing routes.
    private static final Pattern HOW_TO = Pattern.compile(
            "^(?:how (?:do|can|should|would|to)|where (?:can|do|should|would)|can i|could i|may i|should i|i want to"
                    + "|i need to|i'd like to|i would like to|help me|(?:can|could|would|will) you help"
                    + "|request|obtain|order|need|want)\\b|\\bhow (?:do|can|should|would) i\\b|\\bhow to\\b");
    // Timing and obligation ("When should I submit my timesheet?") are policy questions, not lookups.
    private static final Pattern POLICY = Pattern.compile(
            "\\b(?:polic(?:y|ies)|deadlines?|due|cut-?offs?|how long|how soon|how many days|allowed|eligib(?:le|ility)"
                    + "|rules?|overtime|holidays?|limits?|maximum|minimum|usually|typically|normally|supposed to"
                    + "|should|must|have to|need to)\\b");
    // Asking Wavi itself to change a record is refused by the existing action-request route.
    private static final Pattern ACTION_REQUEST = Pattern.compile(
            "^(?:(?:(?:can|could|will|would) you|please)(?: please)?(?: (?:just|go ahead and))? )?"
                    + "(?:approve|reject|submit|resubmit|sign|cancel|delete|remove|change|update|edit|modify|correct"
                    + "|withdraw)\\b");
    // A reviewer's own queue ("pending my review", "assigned to me") is not the reviewer's own request.
    private static final Pattern REVIEWER_QUEUE = Pattern.compile(
            "\\bmy (?:review|approval|action|attention|decision|sign-?off|queue|team|employees|direct reports|reports|staff)\\b"
                    + "|\\b(?:assigned|routed|sent) to me\\b|\\bin (?:my|the) organi[sz]ation\\b|\\bemployee timesheets\\b"
                    + "|\\bteam timesheets\\b");

    private static final String OWN = "\\bmy (?:(?:latest|last|most recent|recent|current|previous|new|pending) )?";
    private static final String FIELD = "(phone number|phone|mobile number|cell number|home address|mailing address|address"
            + "|personal email(?: address)?|email address|legal name|first name|last name|name)";
    private static final String CHANGE = "(?:change|update|edit|correction)";
    private static final Pattern OWN_FIELD_CHANGE = Pattern.compile(OWN + FIELD + " " + CHANGE + "(?: requests?)?\\b");
    private static final Pattern OWN_FIELD_UPDATED = Pattern.compile(
            OWN + FIELD + " (?:been |got |gotten |get |was |is )?(?:updated|changed)\\b");
    private static final Pattern OWN_PROFILE_CHANGE = Pattern.compile(
            OWN + "(?:(?:profile|personal (?:details|information|info)|contact (?:details|info|information)) "
                    + CHANGE + "(?: requests?)?|profile requests?)\\b");
    private static final Pattern OWN_TIMESHEET = Pattern.compile(OWN + "time ?sheets?\\b");
    private static final Pattern OWN_OFFER_LETTER = Pattern.compile(OWN + "offer letter(?: requests?)?\\b");
    private static final Pattern OWN_DOCUMENT = Pattern.compile(
            OWN + "(?:(?:document|letter|employment verification(?: letter)?|verification letter|experience letter"
                    + "|employment letter|i-?983)(?: requests?)?|requests? for (?:a |an )?(?:document|letter))\\b"
                    + "|\\b(?:documents|document requests|letters) (?:have|did) i (?:request|requested|ask for|asked for)\\b");

    // "Do I have any pending profile change requests?" / "Are any of my document requests still pending?"
    private static final String PENDING_SUBJECTS = "(profile change requests?|profile (?:changes|updates|requests)"
            + "|(?:phone number|address|name|personal email) (?:change|update) requests?|document requests?|documents"
            + "|offer letter requests?|timesheets?)";
    private static final Pattern PENDING_CHECK = Pattern.compile(
            "^(?:do i have (?:any )?(?:pending |open |outstanding |unapproved )?" + PENDING_SUBJECTS
                    + "(?: (?:that are |which are )?(?:still )?(?:pending|open|outstanding|waiting|in review|in progress))?"
                    + "|are (?:any of )?my " + PENDING_SUBJECTS + " (?:still )?(?:pending|open|outstanding|waiting"
                    + "|in review|in progress))$");

    // Another person's record: "TARDU005's timesheet", "Sarah's profile change request", "his timesheet".
    private static final String ANY_SUBJECT = "(?:(?:latest |last |most recent |recent |current )?(?:profile change"
            + "|profile update|" + FIELD + " (?:change|update)|time ?sheet|document request|offer letter"
            + "|employee id|job title|department|work email|manager|salary|pay|phone number|address|profile))";
    private static final Pattern OTHER_POSSESSIVE = Pattern.compile(
            "(?:^|[^a-z0-9.@_-])([a-z][a-z0-9.@_-]*)'s " + ANY_SUBJECT);
    private static final Pattern OTHER_PRONOUN = Pattern.compile("\\b(?:his|her|their) " + ANY_SUBJECT);
    private static final Pattern EMPLOYEE_ID = Pattern.compile("\\b[a-z]{2,}[-_]?\\d{2,}\\b");
    private static final Pattern NOT_A_PERSON = Pattern.compile(
            "^(?:what|who|where|when|why|how|it|that|this|there|here|let|today|tomorrow|yesterday|week|month|year|day"
                    + "|company|organization|organisation|team|waypoint|wavi|everyone|someone|one)$");

    // Queue counts that no exact queue question matched ("How many timesheets were rejected this month?").
    private static final Pattern QUEUE_COUNT = Pattern.compile(
            "^(?:how many|what number of|number of|count of|count the|do i have any|are there any|any) "
                    + "(?:[a-z-]+ ){0,2}(?:time ?sheets?|profile (?:change |update )?(?:requests?|changes|updates)"
                    + "|document requests?|documents|offer letters?)\\b.*\\b(?:pending|awaiting|waiting|approval|approve"
                    + "|approved|review|submitted|rejected|assigned|queue|outstanding|open|completed|fulfilled)\\b");

    private static final Pattern OWN_COUNT = Pattern.compile("\\b(?:have i|did i|i've|i have|i had) (?:\\w+ )?(?:submitted"
            + "|requested|sent|made|filed|created|submit|request)\\b|\\bmy\\b(?! (?:review|approval"
            + "|action|attention|decision|sign-?off|queue|team|employees|direct reports|reports|staff|organi[sz]ation)\\b)");
    private static final Pattern REASON =Pattern.compile("\\bwhy\\b|\\breasons?\\b");
    private static final Pattern WHEN = Pattern.compile("^when\\b|\\bwhat (?:date|day|time)\\b|\\bon what (?:date|day)\\b");
    private static final Pattern APPROVED = Pattern.compile(
            "\\b(?:approv\\w*|accepted|go(?:ne)? through|went through|okayed|signed off)\\b");
    private static final Pattern REJECTED = Pattern.compile(
            "\\b(?:reject\\w*|declined|denied|turned down|sent back|returned)\\b");
    private static final Pattern PENDING = Pattern.compile(
            "\\b(?:pending|waiting|awaiting|still open|in progress|under review|outstanding|not (?:yet )?(?:been )?"
                    + "(?:approved|reviewed|processed))\\b");
    private static final Pattern REQUESTED = Pattern.compile(
            "\\b(?:submit\\w*|request(?:ed)?|sent|send|made|make|file[ds]?|creat\\w*|raised?|put in)\\b");
    private static final Pattern UPDATED = Pattern.compile("\\b(?:updated|changed|take effect|took effect|effective)\\b");
    private static final Pattern STATUS_CUE = Pattern.compile(
            "\\b(?:status|happened|happening|update on|updates on|any (?:update|news)|news|progress|outcome|result"
                    + "|processed|reviewed|complete[d]?|done|ready|going)\\b");
    private static final Pattern QUESTION_LEAD = Pattern.compile(
            "^(?:what|what's|which|is|was|were|are|has|have|had|did|does|do|any|show|status|when|why|where is"
                    + "|where's|how is|how's|give|list|look up|find)\\b");

    private MyHrOwnRecordIntent() {
    }

    /**
     * Lower-cased, punctuation-trimmed and without polite or addressing prefixes ("Can you please tell me
     * whether ...", "Hey Wavi, ..."), so every own-record question is read the same way.
     */
    static String normalize(String message) {
        if (message == null) return "";
        String normalized = message.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
                .replaceAll("\\s+", " ").trim().replaceAll("[?.!]+$", "").trim();
        return POLITE_PREFIX.matcher(normalized).replaceFirst("");
    }

    /** The intent, or null when the message is not about a person's records or a queue count. */
    static Intent classify(String message) {
        String asked = message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("\\s+", " ").trim();
        String normalized = normalize(message);
        if (normalized.isEmpty() || ACTION_REQUEST.matcher(asked).find() || ACTION_REQUEST.matcher(normalized).find()
                || HOW_TO.matcher(normalized).find() || POLICY.matcher(normalized).find()) {
            return null;
        }
        boolean question = asked.endsWith("?") || QUESTION_LEAD.matcher(normalized).find();

        Matcher pending = PENDING_CHECK.matcher(normalized);
        if (pending.matches() && !REVIEWER_QUEUE.matcher(normalized).find()) {
            String subject = pending.group(1) != null ? pending.group(1) : pending.group(2);
            return new Intent(Kind.PENDING_CHECK, pendingSubject(subject), Aspect.PENDING, null, null);
        }

        Intent own = REVIEWER_QUEUE.matcher(normalized).find() ? null : ownSubject(normalized);
        if (own != null) {
            Aspect aspect = aspect(normalized, own.subject(), own.field() != null);
            if (aspect == null && !question) return null;
            return new Intent(Kind.OWN, own.subject(), aspect == null ? Aspect.STATUS : aspect, own.field(),
                    own.documentType());
        }

        Subject other = otherPersonSubject(normalized);
        if (other != null && (question || STATUS_CUE.matcher(normalized).find())) {
            return new Intent(Kind.OTHER_PERSON, other, Aspect.STATUS, null, null);
        }

        // "How many timesheets have I submitted?" counts the asker's own items, not a reviewer queue.
        if (QUEUE_COUNT.matcher(normalized).find() && !OWN_COUNT.matcher(normalized).find()) {
            return new Intent(Kind.QUEUE_COUNT, countSubject(normalized), Aspect.STATUS, null, null);
        }
        return null;
    }

    private static Intent ownSubject(String normalized) {
        Matcher fieldChange = OWN_FIELD_CHANGE.matcher(normalized);
        if (fieldChange.find()) {
            return new Intent(Kind.OWN, Subject.PROFILE_CHANGE, null, field(fieldChange.group(1)), null);
        }
        Matcher fieldUpdated = OWN_FIELD_UPDATED.matcher(normalized);
        if (fieldUpdated.find()) {
            return new Intent(Kind.OWN, Subject.PROFILE_CHANGE, null, field(fieldUpdated.group(1)), null);
        }
        if (OWN_PROFILE_CHANGE.matcher(normalized).find()) {
            return new Intent(Kind.OWN, Subject.PROFILE_CHANGE, null, null, null);
        }
        if (OWN_TIMESHEET.matcher(normalized).find()) return new Intent(Kind.OWN, Subject.TIMESHEET, null, null, null);
        if (OWN_OFFER_LETTER.matcher(normalized).find()) {
            return new Intent(Kind.OWN, Subject.OFFER_LETTER, null, null, RequestedDocumentType.OFFER_LETTER);
        }
        Matcher document = OWN_DOCUMENT.matcher(normalized);
        if (document.find()) {
            return new Intent(Kind.OWN, Subject.DOCUMENT_REQUEST, null, null, documentType(document.group()));
        }
        return null;
    }

    private static Aspect aspect(String normalized, Subject subject, boolean singleField) {
        boolean rejected = REJECTED.matcher(normalized).find();
        if (REASON.matcher(normalized).find() && (rejected || subject != Subject.TIMESHEET)) {
            return rejected ? Aspect.REJECTION_REASON : Aspect.STATUS;
        }
        if (WHEN.matcher(normalized).find()) {
            if (APPROVED.matcher(normalized).find()) return Aspect.APPROVED_DATE;
            if (singleField && UPDATED.matcher(normalized).find()) return Aspect.UPDATED;
            if (REQUESTED.matcher(normalized).find()) return Aspect.REQUESTED_DATE;
            return Aspect.STATUS;
        }
        if (rejected) return Aspect.REJECTED;
        if (PENDING.matcher(normalized).find()) return Aspect.PENDING;
        if (APPROVED.matcher(normalized).find()) return Aspect.APPROVED;
        if (singleField && UPDATED.matcher(normalized).find()) return Aspect.UPDATED;
        if (STATUS_CUE.matcher(normalized).find()) return Aspect.STATUS;
        return null;
    }

    private static Subject otherPersonSubject(String normalized) {
        boolean other = OTHER_PRONOUN.matcher(normalized).find() || EMPLOYEE_ID.matcher(normalized).find();
        Matcher possessive = OTHER_POSSESSIVE.matcher(normalized);
        while (!other && possessive.find()) {
            other = !NOT_A_PERSON.matcher(possessive.group(1)).matches();
        }
        if (!other) return null;
        if (normalized.matches(".*\\btime ?sheets?\\b.*")) return Subject.TIMESHEET;
        if (normalized.matches(".*\\boffer letters?\\b.*")) return Subject.OFFER_LETTER;
        if (normalized.matches(".*\\bdocument(?: requests?|s)?\\b.*")) return Subject.DOCUMENT_REQUEST;
        if (normalized.matches(".*\\b(?:profile (?:change|update)|" + FIELD + " (?:change|update))\\b.*")) {
            return Subject.PROFILE_CHANGE;
        }
        return normalized.matches(".*\\b(?:employee id|job title|department|work email|manager|salary|pay|phone number"
                + "|address|profile)\\b.*") ? Subject.PERSONAL_RECORD : null;
    }

    private static Subject pendingSubject(String subject) {
        if (subject.startsWith("timesheet")) return Subject.TIMESHEET;
        if (subject.startsWith("document")) return Subject.DOCUMENT_REQUEST;
        if (subject.startsWith("offer letter")) return Subject.OFFER_LETTER;
        return Subject.PROFILE_CHANGE;
    }

    private static Subject countSubject(String normalized) {
        if (normalized.matches(".*\\btime ?sheets?\\b.*")) return Subject.TIMESHEET;
        if (normalized.matches(".*\\bprofile\\b.*")) return Subject.PROFILE_CHANGE;
        return Subject.DOCUMENT_REQUEST;
    }

    private static ProfileChangeField field(String field) {
        if (field.contains("phone") || field.contains("mobile") || field.contains("cell")) return ProfileChangeField.PHONE_NUMBER;
        if (field.contains("address") && !field.contains("email")) return ProfileChangeField.ADDRESS;
        if (field.contains("email")) return ProfileChangeField.PERSONAL_EMAIL;
        return ProfileChangeField.NAME;
    }

    private static RequestedDocumentType documentType(String subject) {
        if (subject.contains("verification")) return RequestedDocumentType.EMPLOYMENT_VERIFICATION;
        if (subject.contains("experience")) return RequestedDocumentType.EXPERIENCE_LETTER;
        if (subject.contains("employment letter")) return RequestedDocumentType.EMPLOYMENT_LETTER;
        if (subject.matches(".*i-?983.*")) return RequestedDocumentType.I983;
        return null;
    }
}
