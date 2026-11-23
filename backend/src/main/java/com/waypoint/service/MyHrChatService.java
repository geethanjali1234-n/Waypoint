package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatCitation;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrLatestTimesheetSubmission;
import com.waypoint.dto.myhr.MyHrRecentTimesheetStatus;
import com.waypoint.dto.myhr.MyHrTimesheetQueueSummary;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileChangeHistory;
import com.waypoint.dto.myhr.MyHrOwnDocumentRequestHistory;
import com.waypoint.dto.myhr.MyHrDocumentRequestQueueSummary;
import com.waypoint.dto.myhr.DocumentRequestPageResponse;
import com.waypoint.dto.myhr.DocumentRequestResponse;
import com.waypoint.dto.myhr.MyHrProfileChangeQueueSummary;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "waypoint.myhr.chat.enabled", havingValue = "true")
public class MyHrChatService {
    private static final Logger log = LoggerFactory.getLogger(MyHrChatService.class);
    private static final String PROVIDER_USED_MARKER = "MYHR_CHAT_PROVIDER_USED";
    private static final String RESPONSE_LATENCY_MARKER = "MYHR_CHAT_RESPONSE_LATENCY";
    static final String RAG_LATENCY_MARKER = "MYHR_RAG_STAGE_LATENCY";
    static final String HISTORY_PROVENANCE_MARKER = "MYHR_CHAT_HISTORY_PROVENANCE";
    /** Matches MyHrChatRequest's history limit; longer history is never used. */
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final Pattern RECENT_TIMESHEET_STATUS_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|show|show me|tell me|check) my (?:(?:latest|most recent|recent) )?timesheet status"
                    + "|has my (?:(?:latest|most recent|recent) )?timesheet been (?:approved|submitted))$");
    /** "When did I last submit my timesheet?": the latest submission time, not a period or deadline. */
    private static final Pattern TIMESHEET_SUBMISSION_DATE_QUESTION = Pattern.compile(
            "^(?:(?:can|could|would) you (?:please )?tell me |please tell me |tell me )?(?:"
                    + "(?:when|what date|what day|on what date) did i (?:last |most recently )?submit (?:my|a) "
                    + "(?:(?:last|latest|most recent|previous|recent) )?timesheet"
                    + "|(?:when|what date|what day|on what date) was my (?:(?:last|latest|most recent|previous) )?"
                    + "timesheet (?:submitted|submission)"
                    + "|when i (?:last |most recently )?submitted (?:my|a) (?:(?:last|latest|most recent|previous) )?timesheet"
                    + "|when was the last time i submitted (?:my|a) timesheet"
                    + "|what (?:is|was) the (?:submission )?date of my (?:(?:last|latest|most recent) )?timesheet(?: submission)?)$");
    /** The user's own timesheet was rejected ("My timesheet was rejected", "my manager rejected my timesheet"). */
    private static final Pattern OWN_REJECTED_TIMESHEET = Pattern.compile(
            "\\bmy (?:(?:latest|last|recent|most recent) )?timesheets? (?:was|is|got|has been|have been|were) "
                    + "(?:rejected|declined|sent back|returned)\\b|\\bmy rejected timesheets?\\b"
                    + "|\\b(?:rejected|declined|sent back|returned) my timesheets?\\b");
    /** The signed-in user's own profile change request, optionally "latest": "my latest profile change request". */
    private static final String OWN_PROFILE_CHANGE_REQUEST = "(?:(?:latest|most recent|recent|last) )?"
            + "(?:profile change request|profile change|profile update request|profile update)";
    private static final Pattern PROFILE_CHANGE_STATUS_QUESTION = Pattern.compile(
            "^(?:what(?: is|'s) (?:the )?status of my " + OWN_PROFILE_CHANGE_REQUEST
                    + "|(?:what is|what's) my " + OWN_PROFILE_CHANGE_REQUEST + " status"
                    + "|show(?: me)? my " + OWN_PROFILE_CHANGE_REQUEST + " status"
                    + "|is my " + OWN_PROFILE_CHANGE_REQUEST + " (?:still )?(?:approved|pending|rejected)"
                    + "|(?:has|did) my " + OWN_PROFILE_CHANGE_REQUEST + " (?:been |get |gotten )?(?:approved|rejected))$");
    private static final Pattern PROFILE_CHANGE_APPROVED_DATE_QUESTION = Pattern.compile(
            "^when (?:was|did) my " + OWN_PROFILE_CHANGE_REQUEST + " (?:get )?approved$");
    private static final Pattern PROFILE_CHANGE_REQUESTED_DATE_QUESTION = Pattern.compile(
            "^(?:when did i (?:submit|send|make) my " + OWN_PROFILE_CHANGE_REQUEST
                    + "|when was my " + OWN_PROFILE_CHANGE_REQUEST + " (?:submitted|requested|sent))$");
    private static final Pattern PHONE_CHANGE_APPROVAL_QUESTION = Pattern.compile(
            "^(?:did|has|was) my phone number (?:change|update)(?: request)? (?:get |been |gotten )?approved$");
    private static final Pattern PHONE_CHANGE_PENDING_QUESTION = Pattern.compile(
            "^is my phone number (?:change|update)(?: request)? (?:still )?pending$");
    private static final Pattern PHONE_CHANGE_REJECTION_QUESTION = Pattern.compile(
            "^(?:was|has) my phone number (?:change|update)(?: request)? (?:been )?rejected$");
    private static final Pattern PHONE_CHANGE_REQUESTED_DATE_QUESTION = Pattern.compile(
            "^when did i (?:request|submit) (?:a|my) phone number (?:change|update)(?: request)?$");
    private static final Pattern PHONE_CHANGE_APPROVED_DATE_QUESTION = Pattern.compile(
            "^when was my phone number (?:change|update)(?: request)? approved$");
    private static final Pattern PHONE_CHANGE_EFFECTIVE_DATE_QUESTION = Pattern.compile(
            "^(?:when did i last update my phone number|when was my phone number last updated)$");
    private static final Pattern LATEST_DOCUMENT_REQUEST_STATUS_QUESTION = Pattern.compile(
            "^(?:what(?: is|'s) (?:the )?status of my (?:latest )?document request|"
                    + "(?:what is|what's) my (?:latest )?document request status|"
                    + "has my (?:latest )?document request been approved)$");
    private static final Pattern OFFER_LETTER_STATUS_QUESTION = Pattern.compile(
            "^(?:what(?: is|'s) (?:the )?status of my offer letter(?: request)?|"
                    + "is my offer letter(?: request)? (?:still )?(?:pending|approved|completed|rejected))$");
    private static final Pattern DOCUMENT_REQUEST_REJECTION_QUESTION = Pattern.compile(
            "^why was my (?:latest )?document request rejected$");
    private static final Pattern EMPLOYMENT_VERIFICATION_REQUEST_DATE_QUESTION = Pattern.compile(
            "^when did i (?:submit|request) (?:my )?employment verification (?:letter )?(?:request)?$");
    private static final Pattern REQUESTED_DOCUMENTS_QUESTION = Pattern.compile(
            "^(?:what|which) documents (?:have i requested|did i request)$");
    private static final Pattern COMPLETED_OFFER_LETTER_DOWNLOAD_QUESTION = Pattern.compile(
            "^where can i download my (?:completed|approved|signed) offer letter$");
    private static final Pattern ONBOARDING_REQUEST_PENDING_QUESTION = Pattern.compile(
            "^is my onboarding document request (?:still )?pending$");
    private static final Pattern HR_DOCUMENT_REVIEW_COUNT_QUESTION = Pattern.compile(
            "^(?:how many|what number of) document requests (?:need|are awaiting) my review$");
    private static final Pattern HR_PENDING_DOCUMENT_QUEUE_QUESTION = Pattern.compile(
            "^do i have any pending document requests$");
    private static final Pattern HR_RETURNED_DOCUMENT_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:document )?requests were returned for correction$");
    private static final Pattern HR_ASSIGNED_DOCUMENT_QUEUE_QUESTION = Pattern.compile(
            "^what (?:document )?requests are assigned to me$");
    private static final Pattern HR_DOCUMENT_REVIEW_LOCATION_QUESTION = Pattern.compile(
            "^where can i review employee document requests$");
    private static final Pattern ADMIN_PENDING_DOCUMENT_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:(?:document )?requests are pending|pending (?:document )?requests (?:are|are there))"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_APPROVAL_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:(?:document )?requests (?:need|are awaiting) (?:administrator|company admin|my) approval|"
                    + "documents? (?:are )?(?:waiting|awaiting) for (?:my|(?:administrator|company admin)) approval)"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_COMPLETED_DOCUMENT_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:(?:document )?requests (?:were|have been|are) (?:completed|fulfilled)|"
                    + "(?:completed|fulfilled) document requests (?:are|are there))(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_DOCUMENT_APPROVAL_LOCATION_QUESTION = Pattern.compile(
            "^where can i review document approvals$");
    private static final Pattern HR_PROFILE_REVIEW_COUNT_QUESTION = Pattern.compile(
            "^how many profile change requests (?:are )?(?:waiting|awaiting) (?:for )?my review$");
    private static final Pattern PROFILE_PENDING_QUEUE_QUESTION = Pattern.compile(
            "^(?:do i have any pending (?:employee )?profile (?:change requests|updates)|"
                    + "how many profile change requests are pending)$");
    private static final Pattern HR_PROFILE_REVIEW_LOCATION_QUESTION = Pattern.compile(
            "^where can i review employee profile changes$");
    private static final Pattern ADMIN_PROFILE_PENDING_ORGANIZATION_QUESTION = Pattern.compile(
            "^how many (?:(?:profile change )?requests are pending|pending (?:profile change )?requests (?:are|are there))"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_PROFILE_APPROVAL_QUEUE_QUESTION = Pattern.compile(
            "^how many employee profile updates need approval$");
    private static final Pattern ADMIN_PROFILE_APPROVED_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:(?:profile change )?requests (?:were|have been|are) approved|"
                    + "approved profile change requests (?:are|are there))(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_PROFILE_REJECTED_QUEUE_QUESTION = Pattern.compile(
            "^how many (?:(?:profile change )?requests (?:were|have been|are) rejected|"
                    + "rejected profile change requests (?:are|are there))(?: in (?:my|the) organization)?$");
    private static final Pattern PROFILE_REVIEW_LOCATION_QUESTION = Pattern.compile(
            "^where can i review profile change requests$");
    private static final Pattern HR_TIMESHEET_REVIEW_COUNT_QUESTION = Pattern.compile(
            "^(?:how many|what number of) timesheets (?:are )?(?:waiting|awaiting) (?:for )?my review$");
    private static final Pattern HR_TIMESHEET_AWAITING_APPROVAL_QUESTION = Pattern.compile(
            "^do i have any employee timesheets awaiting approval$");
    private static final Pattern HR_ASSIGNED_TIMESHEET_PENDING_QUESTION = Pattern.compile(
            "^how many assigned timesheets are pending$");
    private static final Pattern HR_TIMESHEET_REVIEW_LOCATION_QUESTION = Pattern.compile(
            "^where can i review submitted timesheets$");
    private static final Pattern ADMIN_TIMESHEET_SUBMITTED_ORGANIZATION_QUESTION = Pattern.compile(
            "^how many (?:(?:timesheets are )?submitted|submitted timesheets (?:are|are there))"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_TIMESHEET_PENDING_APPROVAL_QUESTION = Pattern.compile(
            "^how many (?:(?:timesheets are )?(?:pending approval|awaiting approval)|"
                    + "(?:pending approval|awaiting approval) timesheets (?:are|are there))"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_TIMESHEET_AWAITING_REVIEW_QUESTION = Pattern.compile(
            "^how many (?:(?:timesheets are )?awaiting review|awaiting-review timesheets (?:are|are there))"
                    + "(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_TIMESHEET_APPROVED_QUESTION = Pattern.compile(
            "^how many (?:(?:timesheets (?:have been|were|are) approved)|"
                    + "approved timesheets (?:are|are there))(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_TIMESHEET_REJECTED_QUESTION = Pattern.compile(
            "^how many (?:(?:timesheets (?:have been|were|are) rejected)|"
                    + "rejected timesheets (?:are|are there))(?: in (?:my|the) organization)?$");
    private static final Pattern ADMIN_TIMESHEET_REVIEW_LOCATION_QUESTION = Pattern.compile(
            "^where can i review employee timesheets$");
    // "... waiting for my approval", "... pending my review", "... assigned to me", "... awaiting my action": the
    // asker's own queue, answered in the scope of the verified role (HR: assigned to them; Company Admin: organization).
    private static final String MY_QUEUE_LEAD = "^(?:how many|what number of|do i have any|are there any) ";
    private static final String MY_QUEUE_TAIL = " (?:that )?(?:"
            + "(?:are |is )?(?:waiting|awaiting|pending|needing|requiring|need|require)(?: for| on)? my "
            + "(?:approval|review|action|attention|decision|sign-?off)"
            + "|(?:are |is )?(?:waiting|awaiting|pending) (?:for|on) me"
            + "|(?:are |is )?(?:assigned|routed|sent) to me(?: for (?:approval|review|action))?"
            + "|(?:are |is )?in my (?:approval |review )?queue"
            + "|do i (?:need|have) to (?:approve|review|action))$";
    private static final Pattern MY_TIMESHEET_QUEUE_QUESTION = Pattern.compile(
            MY_QUEUE_LEAD + "(?:(?:employee|team|submitted|pending) )?timesheets?" + MY_QUEUE_TAIL);
    private static final Pattern MY_PROFILE_CHANGE_QUEUE_QUESTION = Pattern.compile(
            MY_QUEUE_LEAD + "(?:(?:employee|pending) )?profile (?:change |update )?(?:requests?|changes?|updates?)"
                    + MY_QUEUE_TAIL);
    private static final Pattern MY_DOCUMENT_REQUEST_QUEUE_QUESTION = Pattern.compile(
            MY_QUEUE_LEAD + "(?:(?:employee|pending) )?(?:document requests?|documents?)" + MY_QUEUE_TAIL);
    private static final Pattern PROFILE_DETAILS_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|show(?: me)?|tell me about) my (?:employee )?profile"
                    + "|(?:what are|show(?: me)?|tell me about) my profile details|give me my profile|who am i)$");
    private static final Pattern EMPLOYEE_ID_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|tell me) my employee id|show(?: me)? my employee id)$");
    private static final Pattern JOB_TITLE_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|tell me) my (?:job title|title|designation)"
                    + "|show(?: me)? my (?:job title|title|designation))$");
    private static final Pattern DEPARTMENT_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|show(?: me)?) my department"
                    + "|what department do i work in|which department do i work in)$");
    private static final Pattern WORK_EMAIL_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|show(?: me)?) my work email)$");
    private static final Pattern REPORTING_MANAGER_QUESTION = Pattern.compile(
            "^(?:(?:who is|who's|what is|what's|tell me|show(?: me)?) my (?:(?:reporting|direct|line) )?"
                    + "(?:manager|supervisor|boss)(?:'s name| name)?|who (?:do|should|must) i (?:directly )?report to"
                    + "|(?:whom|who) (?:do|should|must) i (?:directly )?report(?: to)?|to whom (?:do|should|must) i report"
                    + "|whom do i report to|who am i reporting to|who is my reporting person)$");
    private static final Pattern OWN_HR_CONTACT_QUESTION = Pattern.compile(
            "^(?:(?:who is|who's|tell me|show(?: me)?) my hr|my hr)$");
    private static final Pattern HELLO_GREETING = Pattern.compile("^hello(?: there)?$");
    /** "How are you?", "How's it going?", optionally after a greeting: answered like any other greeting. */
    private static final Pattern HOW_ARE_YOU = Pattern.compile(
            "^(?:(?:hi|hey|hello|good (?:morning|afternoon|evening))(?: there)?,? )?(?:how are you(?: doing)?(?: today)?"
                    + "|how(?: is|'s) it going|how are things|how do you do|how have you been|what's up|whats up"
                    + "|how r u|how are u|hru)$");
    /** The user's own name, from their own profile. */
    private static final Pattern NAME_QUESTION = Pattern.compile(
            "^(?:(?:what is|what's|tell me|show(?: me)?) my (?:full |legal |first |last )?name"
                    + "|do you know my name|what name do you have for me|what is my name in waypoint)$");
    private static final Pattern HI_GREETING = Pattern.compile("^(?:hi|hey)(?: there)?$");
    private static final Pattern TIME_OF_DAY_GREETING = Pattern.compile("^good (morning|afternoon|evening)$");
    private static final Pattern THANKS = Pattern.compile("^(?:thanks(?: a lot)?|thank you(?: very much)?)$");
    private static final Pattern FAREWELL = Pattern.compile("^(?:bye|goodbye|see you)$");
    /** "Hello Wavi", "Hi, Wavi", "Hey Wavi, what can you do": the assistant's name around small talk. */
    private static final Pattern WAVI_ADDRESSED = Pattern.compile("^(?:(?:hey|hi|hello) )?wavi,? |,? wavi$");
    private static final Pattern CAPABILITIES_QUESTION = Pattern.compile(
            "^(?:what (?:can|could) (?:you|wavi) (?:do|help(?: me)? with)(?: for me)?(?: today)?"
                    + "|how (?:can|could) (?:you|wavi) help(?: me)?(?: today)?|what do you do|what are you able to do"
                    + "|what (?:are|is) (?:your|wavi's) (?:capabilities|features)|what can i ask(?: you| wavi)?"
                    + "|who are you|what are you|what is wavi|who is wavi|help|help me|help please|please help"
                    + "|(?:can|could) you help(?: me)?|i need (?:some )?help|need help)$");
    private static final Pattern ABOUT_WAYPOINT_QUESTION = Pattern.compile(
            "^(?:what(?: is|'s) (?:the )?waypoint(?: app| application| platform)?|tell me about waypoint"
                    + "|what does waypoint do|what is waypoint used for|what can i do (?:in|with) waypoint)$");
    static final String ABOUT_WAYPOINT_REPLY = "Waypoint is a workforce and HR application. Employees use it to "
            + "view their profile, submit timesheets, request profile changes, request documents such as employment "
            + "letters, and upload onboarding documents. HR staff and administrators use it to manage employees, "
            + "review timesheets and profile change requests, and handle document requests. I'm Wavi, the assistant "
            + "built into Waypoint; ask me what I can help with.";
    // Workflow how-to intents. Kept in step with getRelevantNavigationActions in the frontend knowledgeBase.js.
    private static final Pattern WORKFLOW_POLICY_TERMS = Pattern.compile(
            "\\b(?:polic(?:y|ies)|deadlines?|cut-?offs?|due|when|why|status|overtime|holidays?|rules?|allowed"
                    + "|eligib(?:le|ility)|maximum|minimum|limits?|late|how (?:many|much|long))\\b");
    private static final Pattern WORKFLOW_CHANGE_ACTION = Pattern.compile(
            "\\b(?:update|updating|change|changing|edit|editing|modify|correct|correcting|fix|fixing)\\b");
    private static final Pattern TIMESHEET_ENTRY_ACTION = Pattern.compile(
            "\\b(?:enter|entering|fill (?:in|out|up)|fill|filling|log|record|create|submit|submitting|submission)\\b");
    private static final Pattern TIMESHEET_RESUBMIT_ACTION = Pattern.compile("\\bresubmit(?:ting)?\\b");
    private static final Pattern TIMESHEET_REVIEW_ACTION = Pattern.compile(
            "\\b(?:review|reviewing|approve|approving|reject|rejecting)\\b");
    private static final Pattern TIMESHEET_EXPRESS_CLEARANCE = Pattern.compile(
            "\\b(?:express clearance|fast[- ]pass|1[- ]tap)\\b");
    private static final Pattern TIMESHEET_SUBJECT = Pattern.compile("\\b(?:timesheets?|hours)\\b");
    /** Timesheets of the asker's team or reports rather than the asker's own. Mirrored in knowledgeBase.js. */
    private static final Pattern TEAM_TIMESHEET_SUBJECT = Pattern.compile(
            "\\b(?:team|direct reports?|my (?:employees|reports|staff)|employees?['’]? (?:time ?sheets?|time ?cards?|hours))\\b");
    /** The asker's own timesheets or hours. Mirrored in knowledgeBase.js. */
    private static final Pattern OWN_TIMESHEETS = Pattern.compile(
            "\\bmy (?:own )?(?:submitted |previous |past |earlier |historical )?(?:time ?cards?|time ?sheets?|hours?)\\b"
                    + "|\\b(?:time ?cards?|time ?sheets?|hours?)\\b.*\\bi (?:sent|submitted|entered|logged|worked)\\b");
    /** A request to open submitted history, rather than to look up the latest timesheet's status. */
    private static final Pattern TIMESHEET_HISTORY_NAVIGATION = Pattern.compile(
            "\\b(?:where(?: can| do)? i|show(?: me)?|take me to|go to|open|find|view|check|see|access)\\b.*\\b(?:"
                    + "(?:my )?(?:submitted|previous|past|earlier|historical) (?:time ?cards?|time ?sheets?|hours?)"
                    + "|(?:time ?cards?|time ?sheets?|hours?)\\b.*\\bi (?:sent|submitted|entered|logged|worked)\\b)");
    /** Asking the assistant itself to change a record; "could you update me on ..." is not one. */
    private static final Pattern ASSISTANT_ACTION_REQUEST = Pattern.compile(
            "^(?:(?:(?:can|could|will|would) you|please)(?: please)?(?: (?:just|go ahead and))? )?"
                    + "(?:approve|reject|submit|resubmit|sign|cancel|delete|remove|change|update|edit|modify|correct)"
                    + " (?:my|the|this|that|it|his|her|their|our|an?|[a-z]+'s)\\b");
    private static final Pattern PROFILE_SUBJECT = Pattern.compile(
            "\\b(?:phone(?: number)?|mobile number|address|personal email|my email|my (?:legal |first |last |full )?name"
                    + "|profile|contact (?:details|info|information)|personal (?:details|info|information))\\b");
    private static final Pattern OFFER_LETTER = Pattern.compile("\\boffer letters?\\b");
    /** "Can you tell me / Could you please show me / Please check ..." before an own-record lookup. */
    private static final Pattern POLITE_LOOKUP_PREFIX = Pattern.compile(
            "^(?:(?:can|could|would|will) you (?:please )?|please )(?:tell me|show me|give me|let me know|check)\\s+");
    // "Where / how can I ..." questions about finding something in Waypoint, answered with where it is.
    private static final Pattern LOCATION_QUESTION = Pattern.compile(
            "^(?:where|how)\\b|^(?:can|could|may) i (?:check|see|view|find|track|download|upload|access)\\b");
    private static final Pattern LOOK_UP_ACTION = Pattern.compile(
            "\\b(?:check|checking|see|view|find|track|tracking|know|look|follow)\\b");
    private static final Pattern STATUS_TERMS = Pattern.compile(
            "\\b(?:status|progress|approved|rejected|pending|completed|submitted timesheets?)\\b");
    private static final Pattern DOCUMENT_REQUEST_SUBJECT = Pattern.compile(
            "\\b(?:document|letter)s? requests?\\b|\\brequests? for (?:a |an |my )?(?:document|letter)s?\\b");
    private static final Pattern UPLOAD_ACTION = Pattern.compile("\\b(?:upload|uploading|submit|send|attach|provide)\\b");
    private static final Pattern ONBOARDING_SUBJECT = Pattern.compile("\\b(?:onboarding|pre-?employment|new[- ]hire)\\b");
    /** Finding, opening or signing a letter that already exists, rather than requesting one. */
    private static final Pattern DOCUMENT_RETRIEVAL_ACTION = Pattern.compile(
            "\\b(?:download|view|see|find|open|access|print|locate|copy|sign|signed|approved)\\b");
    private static final Pattern POLICY_REFERENCE = Pattern.compile(
            "\\b(?:polic(?:y|ies)|rules?|guidelines?|eligib(?:le|ility))\\b");
    private static final Pattern DOCUMENT_REQUEST = Pattern.compile(
            "\\b(?:request|need|get|want|obtain)\\b.*\\b(?:employment verification|verification letter|employment letter"
                    + "|experience letter|relieving letter|i-?983|a document)\\b");
    /** A Company Admin's approval step for a document HR has prepared, rather than a policy question. */
    private static final Pattern DOCUMENT_APPROVAL_WORKFLOW = Pattern.compile(
            "^(?:how|where)\\b.*\\b(?:approve|approving|review)\\b.*\\b(?:documents?|letters?)(?:\\s+requests?)?\\b");
    private static final Pattern PERSONAL_HR_HISTORY_TOPIC = Pattern.compile(
            "\\b(?:timesheets?|profile(?:\\s+(?:change|update|details|request))?|"
                    + "employee\\s+(?:id|number|profile)|job\\s+title|designation|department|work\\s+email"
                    + "|manager|supervisor|boss|report\\s+to|document(?:\\s+request)?s?|offer\\s+letter|onboarding"
                    // Profile change details: the requested value of a phone, address, email or name change.
                    + "|phone|mobile|cell\\s+number|address|personal\\s+email|email\\s+address|legal\\s+name"
                    + "|first\\s+name|last\\s+name|name\\s+change|i-?983)\\b");
    /** A Company Admin's own timesheet status: there is none, because admins never submit timesheets. */
    static final String ADMIN_NO_OWN_TIMESHEET_STATUS = "Company Admin accounts don't submit timesheets, so there's no "
            + "timesheet status of yours to show. Open Timesheets to review employees' submissions.";
    static final String ADMIN_NO_TIMESHEET_SUBMISSIONS ="Company Admin accounts don't submit timesheets, so there's no "
            + "submission date to show. Open Timesheets to see when each employee's timesheet was submitted.";
    /** When the router failed and nothing else understood the message: honest, no permission or scope claim. */
    static final String ROUTER_UNAVAILABLE_REPLY = "I couldn't work out what you're asking just now - my language "
            + "understanding is temporarily unavailable. Please try again in a moment, or ask more directly, for example "
            + "\"Who reports to me?\", \"What is my timesheet status?\" or \"What needs my approval?\"";
    /**
     * When nothing understood the message but it is not explicitly off-topic (no coding, trivia, unrelated subject
     * or instruction override). The scope guard only knows a fixed English vocabulary, so the absence of a known
     * word is not evidence the user asked about something unrelated: other languages, paraphrases and new wording
     * land here. Says honestly that Wavi didn't understand instead of claiming the question was off-topic.
     */
    static final String DATA_UNAVAILABLE_REPLY = "I can't reach your Waypoint records right now, so I won't guess. "
            + "Please try again in a moment.";
    static final String UNRECOGNIZED_REPLY = "I'm not sure I understood that. I can help with your profile, "
            + "timesheets and hours, document and profile change requests, approvals waiting for you, your team, "
            + "payroll dates and company policies. Could you rephrase it, for example \"Who is my manager?\", "
            + "\"Did I submit last week's timesheet?\" or \"What needs my approval?\"";

    static final String OTHER_PERSON_REPLY = "I can only look up your own Waypoint records, not another person's.";
    static final String ADMIN_NO_OWN_DOCUMENT_REQUESTS = "Company Admin accounts don't request documents for themselves, "
            + "so there's no document request of yours to check. Open Request Documents to see requests in your "
            + "organization, or Document Approvals for documents waiting for your approval.";
    /** Shown when Claude declines a request; unchanged from the text it returned before refusals were reported. */
    static final String REFUSAL_REPLY = "I can’t help with that request. I can provide general Waypoint workflow guidance.";
    static final String ACTION_REQUEST_REPLY ="I can't approve, reject, submit, sign, cancel, delete or change "
            + "anything in Waypoint for you; I can only explain how and point you to the right page.";

    private final ClaudeChatClient claudeChatClient;
    private final OllamaClient ollamaClient;
    private final TimesheetService timesheetService;
    private final ProfileChangeRequestService profileChangeRequestService;
    private final EmployeeManagementService employeeManagementService;
    private final DocumentRequestService documentRequestService;
    private final String primaryProvider;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final boolean knowledgeGroundingEnabled;
    private final boolean externalPolicyContextApproved;
    private final boolean claudeOnly;
    /** The original Claude -> local Ollama fallback; used only when the configured model is verified local. */
    @Value("${waypoint.myhr.ai.ollama.fallback-enabled:true}")
    private boolean localFallbackEnabled = true;

    private boolean canUseLocalFallback(ClaudeChatClient.ClaudeUnavailableException failure) {
        return localFallbackEnabled && !claudeOnly
                && ollamaClient.verifiedLocalModel();
    }

    @Autowired
    void requireClaudePrimary() {
        if (!"claude".equalsIgnoreCase(primaryProvider)) {
            throw new IllegalStateException("The Wavy chat service requires Claude as its primary provider");
        }
    }
    private final ExternalPolicyProcessingAuthorization externalProcessingAuthorization;
    private final MyHrChatHistoryProvenance historyProvenance;
    private PayrollReadinessService payrollReadinessService;

    @Autowired(required = false)
    void usePayrollReadinessService(PayrollReadinessService service) {
        this.payrollReadinessService = service;
    }
    /** Today, for questions about "this week" or "last month"; replaceable only by tests. */
    private Clock clock = Clock.systemDefaultZone();

    /** Without knowledge grounding: answers are never based on the MyHR knowledge base. */
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           String primaryProvider) {
        this(claudeChatClient, ollamaClient, timesheetService, profileChangeRequestService,
                employeeManagementService, null, primaryProvider, null, false, false);
    }

    /**
     * Knowledge grounding is off unless explicitly enabled, and additionally needs retrieval
     * itself enabled. Policy text is sent to Claude, an external provider, only when that has
     * been explicitly approved; otherwise grounded answers come from Ollama, and only when its
     * endpoint is verifiably local. With neither, nothing is retrieved.
     */
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           DocumentRequestService documentRequestService,
                           String primaryProvider,
                           KnowledgeRetrievalService knowledgeRetrievalService,
                           boolean knowledgeGroundingEnabled,
                           boolean externalPolicyContextApproved) {
        this(claudeChatClient, ollamaClient, timesheetService, profileChangeRequestService, employeeManagementService,
                documentRequestService, primaryProvider, knowledgeRetrievalService, knowledgeGroundingEnabled,
                externalPolicyContextApproved, false);
    }

    /**
     * Without an organization-level external-processing authorization: company policy text never reaches
     * Claude, whatever the allow-external-provider switch says.
     */
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           DocumentRequestService documentRequestService,
                           String primaryProvider,
                           KnowledgeRetrievalService knowledgeRetrievalService,
                           boolean knowledgeGroundingEnabled,
                           boolean externalPolicyContextApproved,
                           boolean claudeOnly) {
        this(claudeChatClient, ollamaClient, timesheetService, profileChangeRequestService, employeeManagementService,
                documentRequestService, primaryProvider, knowledgeRetrievalService, knowledgeGroundingEnabled,
                externalPolicyContextApproved, claudeOnly, ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    /**
     * Claude-only mode (off unless explicitly enabled) never calls Ollama: when Claude is unavailable the
     * failure is reported instead of answered by another provider, and company policy text goes to Claude
     * only when that has been approved - otherwise policy questions get the fixed "cannot check" reply.
     *
     * <p>Company policy text reaches Claude only when all of these hold for the request: Claude is the primary
     * provider, the deployment switch allow-external-provider is on, every retrieved excerpt is COMPANY_WIDE,
     * and the organization's external-processing authorization permits exactly those excerpts. Otherwise it
     * stays on verified-local Ollama, or goes nowhere.
     */
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           DocumentRequestService documentRequestService,
                           String primaryProvider,
                           KnowledgeRetrievalService knowledgeRetrievalService,
                           boolean knowledgeGroundingEnabled,
                           boolean externalPolicyContextApproved,
                           boolean claudeOnly,
                           ExternalPolicyProcessingAuthorization externalProcessingAuthorization) {
        this(claudeChatClient, ollamaClient, timesheetService, profileChangeRequestService, employeeManagementService,
                documentRequestService, primaryProvider, knowledgeRetrievalService, knowledgeGroundingEnabled,
                externalPolicyContextApproved, claudeOnly, externalProcessingAuthorization,
                new MyHrChatHistoryProvenance(Duration.ofHours(3), Clock.systemUTC()));
    }

    /**
     * Conversation history reaches an external AI provider only when historyProvenance verifies, for the current
     * actor and login session, that this server issued it and classified it as permitted for external use.
     */
    @Autowired
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           DocumentRequestService documentRequestService,
                           @Value("${waypoint.myhr.ai.primary-provider:claude}") String primaryProvider,
                           KnowledgeRetrievalService knowledgeRetrievalService,
                           @Value("${waypoint.myhr.chat.knowledge-grounding.enabled:false}") boolean knowledgeGroundingEnabled,
                           @Value("${waypoint.myhr.chat.knowledge-grounding.allow-external-provider:false}")
                           boolean externalPolicyContextApproved,
                           @Value("${waypoint.myhr.ai.claude-only.enabled:false}") boolean claudeOnly,
                           ExternalPolicyProcessingAuthorization externalProcessingAuthorization,
                           MyHrChatHistoryProvenance historyProvenance) {
        if (claudeOnly && !"claude".equalsIgnoreCase(primaryProvider)) {
            // A contradictory configuration must not silently send replies to a provider Claude-only excludes.
            throw new IllegalStateException("Claude-only mode requires waypoint.myhr.ai.primary-provider=claude");
        }
        this.claudeChatClient = claudeChatClient;
        this.ollamaClient = ollamaClient;
        this.timesheetService = timesheetService;
        this.profileChangeRequestService = profileChangeRequestService;
        this.employeeManagementService = employeeManagementService;
        this.documentRequestService = documentRequestService;
        this.primaryProvider = primaryProvider;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeGroundingEnabled = knowledgeGroundingEnabled;
        this.externalPolicyContextApproved = externalPolicyContextApproved;
        this.claudeOnly = claudeOnly;
        // Fail closed: a missing authorization is the same as no authorization.
        this.externalProcessingAuthorization = externalProcessingAuthorization == null
                ? ExternalPolicyProcessingAuthorization.DENY_ALL : externalProcessingAuthorization;
        if (historyProvenance == null) {
            throw new IllegalArgumentException("MyHR chat requires conversation history provenance");
        }
        this.historyProvenance = historyProvenance;
        if (claudeOnly) {
            log.info("MyHR chat Claude-only mode is enabled: Ollama is never used for chat replies");
        }
        if (knowledgeGroundingEnabled) {
            log.info("MyHR chat knowledge grounding is enabled (external provider switch: {}); company policy text "
                    + "reaches Claude only for COMPANY_WIDE excerpts an organization-level authorization permits",
                    externalPolicyContextApproved);
        }
    }

    /** Kept for existing unit tests and callers that do not exercise document-history lookups. */
    public MyHrChatService(ClaudeChatClient claudeChatClient, OllamaClient ollamaClient,
                           TimesheetService timesheetService,
                           ProfileChangeRequestService profileChangeRequestService,
                           EmployeeManagementService employeeManagementService,
                           String primaryProvider,
                           KnowledgeRetrievalService knowledgeRetrievalService,
                           boolean knowledgeGroundingEnabled,
                           boolean externalPolicyContextApproved) {
        this(claudeChatClient, ollamaClient, timesheetService, profileChangeRequestService,
                employeeManagementService, null, primaryProvider, knowledgeRetrievalService,
                knowledgeGroundingEnabled, externalPolicyContextApproved);
    }

    public MyHrChatResponse chat(String message) {
        return chat(message, List.of());
    }

    public MyHrChatResponse chat(String message, List<MyHrChatHistoryMessage> history) {
        return chat(message, history, null);
    }

    /** Never knowledge-grounded: a bare user id cannot scope company knowledge. */
    public MyHrChatResponse chat(String message, List<MyHrChatHistoryMessage> history, String authenticatedUserId) {
        return respond(message, history, authenticatedUserId, null, null);
    }

    /**
     * Without a login session no history stamp can be verified, so no conversation history reaches an external
     * AI provider and the reply carries no stamps.
     */
    public MyHrChatResponse chat(VerifiedMyHrActor actor, String message, List<MyHrChatHistoryMessage> history) {
        return chat(actor, message, history, null);
    }

    /**
     * The controller's entry point. The actor was verified against the user's current database
     * record, so its organization and role - never request input - scope any company knowledge.
     * The session identifies the login the conversation belongs to: history whose stamps verify for
     * this actor and session may reach an external provider when stamped as permitted; the reply
     * carries new stamps for this turn's question and answer.
     */
    public MyHrChatResponse chat(VerifiedMyHrActor actor, String message, List<MyHrChatHistoryMessage> history,
                                 MyHrChatHistoryProvenance.Session session) {
        if (actor == null) {
            throw new UnauthorizedException("Authentication is required");
        }
        return respond(message, history, actor.userId(), actor, session);
    }

    private MyHrChatResponse respond(String message, List<MyHrChatHistoryMessage> history,
                                     String authenticatedUserId, VerifiedMyHrActor actor,
                                     MyHrChatHistoryProvenance.Session session) {
        long startedAtNanos = System.nanoTime();
        String outcome = "completed";
        Turn turn = new Turn();
        try {
            Conversation conversation = conversation(history, actor, session);
            if (dialogueStore != null && actor != null) {
                var snapshot = dialogueStore.snapshot(actor, session, conversation.latestVerifiedTurnId(), clock.instant());
                turn.previousState = snapshot.previous().orElse(null);
                turn.dialogueRevision = snapshot.revision();
            }
            MyHrChatResponse response = respondInternal(message, conversation, authenticatedUserId, actor, turn);
            shadowObserve(message, conversation, actor, turn, response);
            publishAnswerSource(turn);
            java.util.function.Consumer<RouteStage> watching = routeStageObserver;
            if (watching != null) watching.accept(turn.stage);
            return stamped(response, message, actor, session, turn);
        } catch (org.springframework.dao.TransientDataAccessException
                 | org.springframework.dao.DataAccessResourceFailureException dataUnavailable) {
            // The database is down or slow: an honest, retryable answer, classified for metrics. Nothing from the
            // failed turn is kept (fresh Turn: no dialogue state), and no model is asked to guess the record.
            outcome = "data_unavailable:" + dataUnavailable.getClass().getSimpleName();
            return stamped(new MyHrChatResponse(DATA_UNAVAILABLE_REPLY), message, actor, session, new Turn());
        } catch (RuntimeException failure) {
            // Exception class is useful operationally and cannot contain the prompt or a user record.
            outcome = "failed:" + failure.getClass().getSimpleName();
            throw failure;
        } finally {
            log.info("{} outcome={} latencyMs={} stage={}", RESPONSE_LATENCY_MARKER, outcome,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos), turn.stage.code());
        }
    }

    /**
     * The routing stage that answered a message, in precedence order. PROVIDER_PATH covers everything after routing:
     * the scope guard, grounded policy answers and the general provider.
     */
    enum RouteStage {
        NONE, ROUTER_CONTEXT, DETERMINISTIC, PRECISE, PERIOD_FOLLOW_UP, ROUTER, HANDOFF_GUARD, CONTINUATION, BROAD,
        INTERPRETED, CLARIFY_REFERENCE, PEOPLE_REFERENCE, PROVIDER_PATH,
        /** The question frame: answer type, scope and selection mapped to a capability (WaviQuestionFrame). */
        FRAME,
        /** The Claude tool-use agent (WaviAgentLoop): understood, queried through tools and worded by the model. */
        AGENT;

        String code() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Tests only: sees the stage that answered each message. */
    private volatile java.util.function.Consumer<RouteStage> routeStageObserver;

    void observeRouteStages(java.util.function.Consumer<RouteStage> observer) {
        this.routeStageObserver = observer;
    }

    /**
     * Whether BROAD deterministic routes wait for the semantic router (default true). Only matters while semantic
     * routing is enabled; false restores the earlier order (every deterministic route, then the interpreter, then
     * the router) as a rollback switch.
     */
    private boolean broadRoutesDeferToRouter = true;

    @Autowired
    void configureRoutingPrecedence(
            @Value("${waypoint.myhr.router.semantic.broad-routes-defer:true}") boolean broadRoutesDefer) {
        useBroadRoutesDeferToRouter(broadRoutesDefer);
    }

    /** Also used by tests. */
    void useBroadRoutesDeferToRouter(boolean defer) {
        this.broadRoutesDeferToRouter = defer;
    }

    /**
     * Whether this turn's question and answer may later be sent to an external AI provider as history. Restricted
     * unless a route that cannot carry restricted content says otherwise: a personal record, a reply derived from
     * policy not approved for external processing, or a local reply written with restricted history stays inside.
     */
    private static final class Turn {
        private MyHrChatHistoryProvenance.ExternalUse use = MyHrChatHistoryProvenance.ExternalUse.RESTRICTED;
        /** The tool and validated arguments this turn was answered with, for follow-ups; shared with child turns. */
        private final java.util.concurrent.atomic.AtomicReference<WaviDialogueStore.State> dialogue;

        Turn() {
            this(new java.util.concurrent.atomic.AtomicReference<>());
        }

        private Turn(java.util.concurrent.atomic.AtomicReference<WaviDialogueStore.State> dialogue) {
            this.dialogue = dialogue;
        }

        /** A turn whose external-use flag stays its own (restricted), but whose answered request is this turn's. */
        Turn restrictedChild() {
            return new Turn(dialogue);
        }

        void answeredWith(String tool, Map<String, Object> arguments) {
            dialogue.set(new WaviDialogueStore.State(null, tool, arguments, null));
        }

        void answeredWithPolicy(List<MyHrKnowledgeGrounding.Source> sources) {
            List<WaviDialogueStore.CitationRef> references = sources.stream()
                    .filter(source -> source.documentId() != null && !source.documentId().isBlank()
                            && source.ingestionId() != null && !source.ingestionId().isBlank())
                    .map(source -> new WaviDialogueStore.CitationRef(source.number(), source.documentId(),
                            source.ingestionId())).toList();
            dialogue.set(new WaviDialogueStore.State(null, WaviTools.POLICY_QUESTION, Map.of(), null,
                    null, null, null, references));
        }

        void continuedWith(String tool, Map<String, Object> arguments, WaviDialogueStore.State previous) {
            dialogue.set(new WaviDialogueStore.State(null, tool, arguments, null, null, previous.referenceDate()));
        }

        /** Small talk ("thanks", "hi") answers nothing; the conversation's verified state carries over to it. */
        private boolean keepPreviousState;
        /** The verified state this turn followed (found once per request), for carrying it over. */
        private WaviDialogueStore.State previousState;
        /** Revision observed before processing this turn; stale completions may not replace newer state. */
        private long dialogueRevision = -1;

        /** Which routing stage answered: logged as a code (never the question or reply) and seen by tests. */
        private RouteStage stage = RouteStage.NONE;

        MyHrChatResponse routedBy(RouteStage answeredBy, MyHrChatResponse response) {
            stage = answeredBy;
            return response;
        }

        /** "fixed" unless a later branch says the policy/general pipeline ("handoff") or a scope refusal answered. */
        private String servedBy = "fixed";

        void servedBy(String category) {
            servedBy = category;
        }

        /** Which AI provider produced the answer, as a code ("none" when no model did): development diagnostics only. */
        private String providerUsed = "none";

        void provider(String code) {
            providerUsed = code;
        }

        void permitExternalUse() {
            use = MyHrChatHistoryProvenance.ExternalUse.PERMITTED;
        }

        void permitExternalUseIf(boolean permitted) {
            if (permitted) permitExternalUse();
        }
    }

    /**
     * The request's history in the forms routing needs. raw: session-verified turns, for checks that call no provider
     * (scope and interpretation). local: the existing safe-history filter, for a verified-local model. verified: complete turns
     * whose stamps verify for this actor and session, for the retrieval query. external: verified turns stamped as
     * permitted, the only history an external provider (Claude, or an Ollama that is not local) ever receives.
     * Provider-bound lists never carry the stamps.
     */
    private record Conversation(List<MyHrChatHistoryMessage> raw, List<MyHrChatHistoryMessage> local,
                                List<MyHrChatHistoryMessage> verified, List<MyHrChatHistoryMessage> external,
                                String latestVerifiedTurnId, MyHrChatHistoryProvenance.Session session,
                                List<MyHrChatHistoryMessage> permittedVerified) {

        /**
         * Verified turns stamped as externally permitted, WITHOUT the legacy personal-topic filter. Only the agent
         * uses it, and only for an organization authorized to send record data externally: that authorization is what
         * the topic filter stood in for on the general provider path.
         */
        List<MyHrChatHistoryMessage> agentHistory() {
            return permittedVerified;
        }

        /** Whether a reply written with local history used only history that was itself permitted. */
        boolean localIsExternal() {
            return local.equals(external);
        }
    }

    private Conversation conversation(List<MyHrChatHistoryMessage> history, VerifiedMyHrActor actor,
                                      MyHrChatHistoryProvenance.Session session) {
        List<MyHrChatHistoryMessage> raw = history == null ? List.of() : history;
        List<MyHrChatHistoryMessage> verified = new ArrayList<>();
        List<MyHrChatHistoryMessage> external = new ArrayList<>();
        int stamped = 0;
        String latestVerifiedTurnId = null;
        if (actor != null && session != null && raw.size() <= MAX_HISTORY_MESSAGES) {
            // Only complete turns, both messages stamped for this actor and session in the same turn. A lone or
            // relabelled message, edited text, a stamp from another turn, user, organization, role or login, or
            // an expired stamp does not verify.
            for (int index = 0; index + 1 < raw.size(); ) {
                MyHrChatHistoryMessage question = raw.get(index);
                MyHrChatHistoryMessage answer = raw.get(index + 1);
                if (question == null || answer == null
                        || !"user".equals(question.role()) || !"assistant".equals(answer.role())) {
                    index++;
                    continue;
                }
                index += 2;
                Optional<MyHrChatHistoryProvenance.Stamp> questionStamp = historyProvenance.verify(actor, session, question);
                Optional<MyHrChatHistoryProvenance.Stamp> answerStamp = historyProvenance.verify(actor, session, answer);
                if (questionStamp.isEmpty() || answerStamp.isEmpty()
                        || !questionStamp.get().turnId().equals(answerStamp.get().turnId())
                        || questionStamp.get().use() != answerStamp.get().use()) {
                    continue;
                }
                stamped += 2;
                // Only a verified turn that ends the history identifies the conversation's latest answer.
                if (index == raw.size()) latestVerifiedTurnId = answerStamp.get().turnId();
                List<MyHrChatHistoryMessage> pair = withoutStamps(List.of(question, answer));
                verified.addAll(pair);
                if (answerStamp.get().use() == MyHrChatHistoryProvenance.ExternalUse.PERMITTED) {
                    external.addAll(pair);
                }
            }
        }
        // Only complete, session-verified turns may inform server-side interpretation. Keep personal turns out
        // of provider and retrieval context; their verified questions still allow deterministic record follow-ups.
        List<MyHrChatHistoryMessage> interpretation = verified.stream().allMatch(this::isValidHistoryMessage)
                ? List.copyOf(verified) : List.of();
        List<MyHrChatHistoryMessage> trusted = filterSafeHistory(verified);
        Conversation conversation = new Conversation(interpretation, trusted, trusted, filterSafeHistory(external),
                latestVerifiedTurnId, session, List.copyOf(external));
        if (!raw.isEmpty()) {
            // Counts only: never content, identifiers or stamps.
            log.info("{} received={} verified={} external-permitted={}", HISTORY_PROVENANCE_MARKER, raw.size(),
                    stamped, conversation.external().size());
        }
        return conversation;
    }

    private static List<MyHrChatHistoryMessage> withoutStamps(List<MyHrChatHistoryMessage> history) {
        return history.stream().map(message -> message == null ? null
                : new MyHrChatHistoryMessage(message.role(), message.content())).toList();
    }

    /** History for a provider chosen by the ordinary routing: external-safe unless that provider is local Ollama. */
    private List<MyHrChatHistoryMessage> generalHistory(Conversation conversation) {
        return generalProviderIsExternal() ? conversation.external() : conversation.local();
    }

    /** Claude, and its Ollama fallback, are given external-safe history; so is an Ollama that is not local. */
    private boolean generalProviderIsExternal() {
        return !"ollama".equalsIgnoreCase(primaryProvider) || !ollamaClient.isLocalEndpoint();
    }

    /** Whether a reply written by the ordinary routing used only permitted history. */
    private boolean generalReplyPermitted(Conversation conversation) {
        return generalProviderIsExternal() || conversation.localIsExternal();
    }

    /** Stamps for this turn's question and answer, only for a verified actor in a known login session. */
    /** The request contract accepts history content up to 4000 characters (MyHrChatHistoryMessage). */
    static final int MAX_REPLY_CHARS = 3_900;
    static final String SHORTENED_NOTE = "\n\n(Answer shortened. Ask about a specific item for more detail.)";

    /**
     * Every reply is returned to the server as history on the next turn; one longer than the request contract allows
     * would make that next request fail validation and break the conversation. Trims at a line (or word) boundary,
     * before stamping, so the stamp covers exactly what the browser sends back.
     */
    static MyHrChatResponse withinHistoryLimit(MyHrChatResponse response) {
        if (response == null || response.message() == null || response.message().length() <= MAX_REPLY_CHARS) {
            return response;
        }
        String text = response.message();
        int limit = MAX_REPLY_CHARS - SHORTENED_NOTE.length();
        int cut = text.lastIndexOf('\n', limit);
        if (cut < limit / 2) cut = text.lastIndexOf(' ', limit);
        if (cut < limit / 2) cut = limit;
        return new MyHrChatResponse(text.substring(0, cut).stripTrailing() + SHORTENED_NOTE, response.knowledgeStatus(),
                response.citations(), response.historyProvenance(), response.actions());
    }

    private MyHrChatResponse stamped(MyHrChatResponse response, String message, VerifiedMyHrActor actor,
                                     MyHrChatHistoryProvenance.Session session, Turn turn) {
        response = withinHistoryLimit(response);
        if (response == null || actor == null || session == null || message == null || response.message() == null) {
            return response;
        }
        String turnId = historyProvenance.newTurnId();
        WaviDialogueStore.State answered = turn.dialogue.get();
        if (answered == null && turn.keepPreviousState && turn.previousState != null) answered = turn.previousState;
        if (dialogueStore != null && turn.dialogueRevision >= 0) {
            WaviClarification pending = answered != null && WaviTools.CLARIFY.equals(answered.tool())
                    ? (turn.previousState != null && turn.previousState.pending() != null
                       && turn.previousState.pending().isSlotAnswer(message)
                       ? turn.previousState.pending().merge(message) : WaviClarification.from(message, answered.arguments())) : null;
            // Server-side only, bound to this stamped turn; never sent to or accepted from the browser.
            boolean saved = dialogueStore.recordIfUnchanged(actor, session, new WaviDialogueStore.State(turnId,
                    answered == null ? WaviDialogueStore.NO_STATE : answered.tool(),
                    answered == null ? Map.of() : answered.arguments(), clock.instant(), pending,
                    answered == null || answered.referenceDate() == null ? LocalDate.now(clock) : answered.referenceDate(),
                    null, answered == null ? List.of() : answered.citations()),
                    turn.dialogueRevision, clock.instant());
            if (!saved) log.info("{} dialogue-state=concurrent-turn-not-saved", PROVIDER_USED_MARKER);
        }
        return response.withHistoryProvenance(new MyHrChatResponse.HistoryProvenance(
                historyProvenance.stamp(actor, session, turnId, "user", message, turn.use),
                historyProvenance.stamp(actor, session, turnId, "assistant", response.message(), turn.use)));
    }

    private MyHrChatResponse conversationalIntentResponse(String message, VerifiedMyHrActor actor, Turn turn) {
        String normalized = normalizeQuestion(message).replace('\u2019', '\'');
        String payrollQuestion = normalized.replaceAll("[?.!]+$", "").trim();
        boolean cutoff = payrollQuestion.matches("(?:when is|what is|when's|what's) (?:the |my )?payroll cutoff(?: date)?");
        boolean readiness = payrollQuestion.matches("(?:check|show|what is|what's) (?:the )?payroll (?:status|readiness)");
        if (cutoff || readiness) {
            if (actor.role() != UserRole.HR && actor.role() != UserRole.COMPANY_ADMIN) {
                return new MyHrChatResponse("Payroll readiness is available only to HR and Company Admin accounts.").withActions(List.of());
            }
            if (payrollReadinessService == null) {
                return new MyHrChatResponse("Payroll readiness information is unavailable right now.");
            }
            var data = payrollReadinessService.getReadiness(actor.userId(), null, null, null, "ALL", null, null,
                    "EMPLOYEE_ASC", 0, 1);
            if (data == null || (cutoff && (data.period() == null || data.period().payrollCutoffDate() == null))
                    || (readiness && data.summary() == null)) {
                return new MyHrChatResponse("Payroll readiness information is unavailable right now.");
            }
            String answer = cutoff ? "The payroll cutoff for the current period is " + data.period().payrollCutoffDate() + "."
                    : "Payroll readiness: " + data.summary().readyForPayroll() + " of " + data.summary().totalEmployees()
                    + " employees are ready for payroll; " + data.summary().awaitingApproval() + " are awaiting approval.";
            return new MyHrChatResponse(answer).withActions(List.of(MyHrConversationIntents.Page.PAYROLL_READINESS.name()));
        }
        MyHrConversationIntents.Page page = MyHrConversationIntents.navigationTarget(normalized);
        if (page != null) {
            log.info("{} provider=none navigation={} available={}", PROVIDER_USED_MARKER, page, page.availableTo(actor.role()));
            turn.permitExternalUse();
            // Only pages the verified role has; a page it lacks gets no button.
            return viaTools(actor, turn, WaviRouterDecision.Intent.NAVIGATE, WaviTools.OPEN_PAGE,
                    Map.of("page", page.name()), () -> page.availableTo(actor.role())
                    ? new MyHrChatResponse(MyHrConversationIntents.navigationReply(page)).withActions(List.of(page.name()))
                    : new MyHrChatResponse(MyHrConversationIntents.unavailablePageReply(page, actor.role()))
                    .withActions(List.of()));
        }
        MyHrConversationIntents.AskedRole askedRole = MyHrConversationIntents.roleQuestion(normalized);
        if (askedRole != null) {
            // Only the server-side role; a role named in the message is never taken as a fact.
            log.info("{} provider=none own-role=true", PROVIDER_USED_MARKER);
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_ROLE,
                    Map.of("asked", askedRole.name()), () -> {
                        if (askedRole == MyHrConversationIntents.AskedRole.ANY) {
                            // "My role" may mean the job title, so the user's own title is given alongside the role.
                            return new MyHrChatResponse(MyHrConversationIntents.roleAndTitleReply(actor.role(),
                                    ownJobTitle(actor))).withActions(List.of());
                        }
                        return new MyHrChatResponse(MyHrConversationIntents.roleReply(askedRole, actor.role()))
                                .withActions(List.of());
                    });
        }
        if (MyHrConversationIntents.isDirectReportsQuestion(normalized)) {
            log.info("{} provider=none direct-reports=true", PROVIDER_USED_MARKER);
            return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_DIRECT_REPORTS, Map.of(),
                    () -> new MyHrChatResponse(directReportsReply(actor)).withActions(actor.role() == UserRole.EMPLOYEE
                            ? List.of() : List.of(MyHrConversationIntents.Page.EMPLOYEES.name())));
        }
        if (MyHrConversationIntents.isTimesheetUploadHelp(normalized)) {
            log.info("{} provider=none timesheet-upload-help=true", PROVIDER_USED_MARKER);
            turn.permitExternalUse();
            return viaTools(actor, turn, WaviRouterDecision.Intent.WORKFLOW_HELP, WaviTools.WORKFLOW_HELP,
                    Map.of("topic", WaviTools.TIMESHEET_UPLOAD_TOPIC),
                    () -> new MyHrChatResponse(MyHrConversationIntents.TIMESHEET_UPLOAD_HELP)
                            .withActions(List.of(MyHrConversationIntents.Page.TIMESHEETS.name())));
        }
        MyHrConversationIntents.Term term = MyHrConversationIntents.definitionTerm(
                MyHrQuestionInterpreter.vocabulary(normalized.replaceAll("[?.!]+$", "")));
        if (term != null) {
            log.info("{} provider=none definition={}", PROVIDER_USED_MARKER, term);
            turn.permitExternalUse();
            boolean reviewer = actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN;
            return viaTools(actor, turn, WaviRouterDecision.Intent.EXPLAIN, WaviTools.EXPLAIN_TERM,
                    Map.of("term", term.name()),
                    () -> new MyHrChatResponse(MyHrConversationIntents.definitionReply(term, actor.role()))
                            .withActions(term == MyHrConversationIntents.Term.PAYROLL_READINESS && reviewer
                                    ? List.of(MyHrConversationIntents.Page.PAYROLL_READINESS.name())
                                    : List.of(MyHrConversationIntents.Page.TIMESHEETS.name())));
        }
        if (MyHrConversationIntents.isPendingOverviewQuestion(normalized)) {
            // "Pending work for timesheets" asks for one kind of work, not the overview of every kind.
            WaviTools.WorkArea area = WaviContinuation.workArea(normalized);
            if (WaviContinuation.isBareKindOfPendingWork(normalized)) {
                return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_MY_PENDING_WORK,
                        Map.of("area", area.name()), () -> pendingWorkResponse(actor, area, null, null, turn));
            }
            return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_MY_PENDING_WORK, Map.of(),
                    () -> pendingOverviewResponse(actor));
        }
        MyHrConversationIntents.TimesheetRange parsed =
                MyHrConversationIntents.timesheetRange(normalized, LocalDate.now(clock));
        if (parsed == null && WaviContinuation.asksForHours(normalized)) {
            // "How many hours did I log last week?": a quantity question, not a how-to; the period parser skips any
            // "how", so it reads the same message without the quantity interrogative.
            parsed = MyHrConversationIntents.timesheetRange(WaviContinuation.withoutQuantityInterrogative(normalized),
                    LocalDate.now(clock));
        }
        // "How much did I work 2 weeks ago?": the user's own hours for a period, asked without a unit.
        boolean ownWorkDone = WaviContinuation.hasFirstPerson(normalized)
                && (WaviContinuation.asksWorkDone(normalized) || asksUnitlessTotal(normalized, message))
                && !WaviContinuation.namesGroup(normalized) && !WaviContinuation.refersToPerson(normalized)
                && !WaviContinuation.namesRelatedPerson(normalized) && !WaviContinuation.namesThirdParty(message)
                && !WaviContinuation.isConditional(normalized);
        if (parsed == null && ownWorkDone) {
            parsed = MyHrConversationIntents.periodIn(WaviContinuation.withoutQuantityInterrogative(normalized),
                    LocalDate.now(clock));
        }
        boolean hoursAsked = WaviContinuation.asksForHours(normalized) || (parsed != null && ownWorkDone);
        MyHrConversationIntents.TimesheetRange range = parsed;
        if (range != null) {
            Map<String, Object> period = WaviTimesheetPeriod.arguments(range, LocalDate.now(clock));
            if (hoursAsked && WaviContinuation.asksDaily(normalized)) {
                // The granularity slot: hours day by day, not the period total.
                Map<String, Object> arguments = period == null ? null : new java.util.LinkedHashMap<>(period);
                if (arguments != null) arguments.put("detail", WaviTimesheetPeriod.Detail.DAILY_HOURS.name());
                return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS,
                        arguments, () -> new ToolAnswers().dailyTimesheetHours(actor, range));
            }
            if (hoursAsked) {
                Map<String, Object> arguments = period == null ? null : new java.util.LinkedHashMap<>(period);
                if (arguments != null) arguments.put("detail", WaviTimesheetPeriod.Detail.HOURS.name());
                HoursMetric metric = hoursMetricOf(normalized);
                if (metric != HoursMetric.ALL) {
                    // A narrower metric than the tool's period-hours answer: the same lookup, worded for that metric.
                    if (arguments != null) turn.answeredWith(WaviTools.GET_MY_TIMESHEETS, arguments);
                    return timesheetHoursResponse(actor, range, metric);
                }
                return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS,
                        arguments, () -> timesheetHoursResponse(actor, range));
            }
            return timesheetRangeViaTools(actor, turn, range);
        }
        return null;
    }

    /**
     * Waypoint stores pay-period records but no pay dates (PayrollRecord has period start and end, no payment date),
     * so the reply never states one. The organization's payroll frequency, from the user's own profile, is given when
     * it is set.
     */
    /** The payroll topic as a whole (frequency, schedule, pay date), by its nouns, not by a question form. */
    private static final java.util.Set<String> PAYROLL_TOPIC = java.util.Set.of("payroll", "payday", "paydays", "paycheck",
            "paychecks", "salary", "salaries", "wages", "pay");

    static boolean isPayrollTopic(String message) {
        if (message == null) return false;
        for (String token : message.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (PAYROLL_TOPIC.contains(token)) return true;
        }
        return false;
    }

    /**
     * A payroll question no approved policy answered: say so, then the one payroll fact Waypoint records (the
     * organization's payroll frequency), never an invented date or rule.
     */
    private String payrollNoPolicyReply(VerifiedMyHrActor actor, MyHrKnowledgeStatus policyStatus) {
        String frequency = payrollFrequency(actor);
        String lead = policyStatus == MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE
                ? "I can't check the approved company policies right now."
                : "I couldn't find this in the approved company policies.";
        return lead + (frequency == null ? " Please check with HR."
                : " What Waypoint records: your organization's payroll runs " + frequency
                + ". For anything else about pay, please check with HR.");
    }

    private String payrollFrequency(VerifiedMyHrActor actor) {
        String frequency = null;
        try {
            MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(actor.userId());
            if (profile != null && profile.organizationPayrollPeriod() != null) {
                frequency = switch (profile.organizationPayrollPeriod()) {
                    case WEEKLY -> "weekly";
                    case BIWEEKLY -> "every two weeks";
                    case MONTHLY -> "monthly";
                };
            }
        } catch (UnauthorizedException | ForbiddenException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            log.warn("MyHR chat could not read the payroll period for a pay-date answer ({})",
                    unavailable.getClass().getSimpleName());
        }
        return frequency;
    }

    private String payDateReply(VerifiedMyHrActor actor, MyHrKnowledgeStatus policyStatus) {
        String frequency = payrollFrequency(actor);
        return "Waypoint doesn't record pay dates, so I can't tell you when you'll be paid"
                + (policyStatus == MyHrKnowledgeStatus.NO_MATCHING_POLICY
                ? ", and I couldn't find a pay date in the approved company policies."
                : policyStatus == MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE
                ? ", and I can't check the approved company policies right now." : ".")
                + (frequency == null ? "" : " Your organization's payroll runs " + frequency + " in Waypoint.")
                + " For your exact pay date, check your pay documents or ask HR.";
    }

    /** The verified user's own job title, or null when it is not recorded or cannot be read right now. */
    private String ownJobTitle(VerifiedMyHrActor actor) {
        try {
            MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(actor.userId());
            return profile == null ? null : profile.designation();
        } catch (UnauthorizedException | ForbiddenException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            // The account role alone is still a correct answer.
            log.warn("MyHR chat could not read the job title for a role answer ({})", unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * "What about last week?", "and October?", "the week before?" after a question about the user's own timesheets
     * for a period. Earlier user questions only select which of the user's own records to look up: the chain is
     * followed back to the timesheet question it started from, and nothing from history is repeated.
     */
    private MyHrChatResponse timesheetPeriodFollowUp(String message, Conversation conversation, VerifiedMyHrActor actor,
                                                     Turn turn) {
        LocalDate today = LocalDate.now(clock);
        String current = normalizeQuestion(message);
        if (MyHrConversationIntents.followUpRange(current, today, null) == null
                && MyHrConversationIntents.followUpRange(current, today, sentinelRange(today)) == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        List<MyHrChatHistoryMessage> history = conversation.raw();
        MyHrConversationIntents.TimesheetRange anchor = null;
        for (int index = history.size() - 1; index >= 0 && anchor == null; index--) {
            MyHrChatHistoryMessage earlier = history.get(index);
            if (earlier == null || !"user".equals(earlier.role()) || earlier.content() == null) continue;
            String question = normalizeQuestion(earlier.content());
            String canonical = MyHrQuestionInterpreter.canonicalize(earlier.content());
            anchor = MyHrConversationIntents.timesheetRange(question, today);
            if (anchor == null) anchor = MyHrConversationIntents.timesheetRange(canonical, today);
            if (anchor != null) break;
            if (MyHrConversationIntents.followUpRange(question, today, sentinelRange(today)) == null) return null;
            chain.addFirst(question);
        }
        if (anchor == null) return null;
        MyHrConversationIntents.TimesheetRange range = anchor;
        for (String followUp : chain) {
            MyHrConversationIntents.TimesheetRange next = MyHrConversationIntents.followUpRange(followUp, today, range);
            if (next == null) return null;
            range = next;
        }
        MyHrConversationIntents.TimesheetRange asked = MyHrConversationIntents.followUpRange(current, today, range);
        return asked == null ? null : timesheetRangeViaTools(actor, turn.restrictedChild(), asked);
    }

    /** Only to test whether a message is a relative follow-up ("the week before") at all. */
    private static MyHrConversationIntents.TimesheetRange sentinelRange(LocalDate today) {
        return MyHrConversationIntents.timesheetRange("my timesheet this week", today);
    }

    /**
     * The user's own timesheets overlapping the named week (Monday to Sunday), month or day, with each one's status,
     * and the weekdays up to today that no timesheet covers. Never another person's records and never an older or
     * newer period presented as the requested one.
     */
    private MyHrChatResponse timesheetRangeResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange period) {
        return timesheetRangeResponse(actor, period, false);
    }

    /** {@code withSubmission}: each timesheet's recorded submission date as well (a follow-up asked about it). */
    private MyHrChatResponse timesheetRangeResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange period,
                                                    boolean withSubmission) {
        log.info("{} provider=none timesheet-period={}", PROVIDER_USED_MARKER, period.unit());
        LocalDate today = LocalDate.now(clock);
        LocalDate from = period.from();
        LocalDate to = period.to();
        String label = period.label() + (period.singleDay() ? "" : " (" + formatDay(from) + " to " + formatDay(to) + ")");
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse("Company Admin accounts don't submit timesheets, so there's nothing of yours to "
                    + "check for " + period.label() + ". Open Timesheets to review employees' submissions.")
                    .withActions(List.of(MyHrConversationIntents.Page.TIMESHEETS.name()));
        }
        List<com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus> records =
                timesheetService.getOwnStatusesOverlapping(actor.userId(), from, to);
        if (records == null) records = List.of();
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        LocalDate checkedUntil = today.isBefore(to) ? today : to;
        List<LocalDate> uncovered = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(checkedUntil); day = day.plusDays(1)) {
            LocalDate date = day;
            boolean weekday = date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
            if (weekday && records.stream().noneMatch(record -> !date.isBefore(record.periodStartDate())
                    && !date.isAfter(record.periodEndDate()))) {
                uncovered.add(date);
            }
        }
        boolean future = from.isAfter(today);
        if (records.isEmpty()) {
            return new MyHrChatResponse("I don't see a timesheet of yours covering " + label
                    + (period.includesToday() || future ? " yet" : "") + ". If you worked those days, open Timesheets "
                    + "and choose Enter Timesheet to add and submit them.").withActions(actions);
        }
        StringBuilder reply = new StringBuilder("For " + label + ": ");
        for (int index = 0; index < records.size(); index++) {
            com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus record = records.get(index);
            reply.append(index == 0 ? "your" : " Your").append(" timesheet for ")
                    .append(formatDay(record.periodStartDate())).append(" to ").append(formatDay(record.periodEndDate()));
            if (withSubmission) {
                reply.append(record.submittedAt() == null ? ", with no recorded submission date,"
                        : ", submitted " + formatDay(record.submittedAt().toLocalDate()) + ",");
            }
            reply.append(' ').append(periodStatusPhrase(record.status())).append('.');
        }
        if (!uncovered.isEmpty()) {
            int shown = Math.min(uncovered.size(), 10);
            reply.append(" No timesheet covers ").append(uncovered.size() == 1 ? "this weekday" : "these weekdays")
                    .append(" yet: ").append(String.join(", ", uncovered.subList(0, shown).stream().map(this::formatDay)
                            .toList()))
                    .append(uncovered.size() > shown ? ", and " + (uncovered.size() - shown) + " more" : "").append('.');
        }
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /**
     * Everything waiting for the user, from the same authorized lookups as the single-queue questions. HR and Company
     * Admin: their review queues, each scoped by its service to the verified reviewer. Employee: their own open
     * items; employees approve nothing in Waypoint, so no queue is read.
     */
    /**
     * An Employee's own open items, from ALL their records (not only the latest one), in two groups so the user can tell
     * what is theirs to do from what is waiting for someone else:
     * <ul>
     *   <li>needs the employee: timesheets in DRAFT (not submitted) or REJECTED and not since resubmitted or approved,
     *       document requests assigned to them that are PENDING, and any document request RETURNED_FOR_CORRECTION;</li>
     *   <li>waiting for HR/manager: SUBMITTED timesheets, PENDING profile change requests (HR decides), document
     *       requests they submitted (SUBMITTED) or opened themselves (PENDING, requested by them).</li>
     * </ul>
     * Counts and the employee's own titles/periods only; nothing about anyone else. Read-only, no model.
     */
    private MyHrChatResponse employeePendingOverview(VerifiedMyHrActor actor) {
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && timesheet.periodStartDate() != null && timesheet.periodEndDate() != null)
                .toList();
        List<com.waypoint.dto.myhr.TimesheetResponse> toFix = own.stream()
                .filter(timesheet -> timesheetNeedsEmployee(timesheet, own))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate).reversed())
                .toList();
        long awaitingReview = own.stream().filter(timesheet -> timesheet.status() == com.waypoint.enums.TimesheetStatus.SUBMITTED).count();

        long profileWaiting = 0;
        List<com.waypoint.dto.myhr.ProfileChangeRequestResponse> changes = profileChangeRequestService.listMine(actor.userId());
        for (com.waypoint.dto.myhr.ProfileChangeRequestResponse request : changes == null
                ? List.<com.waypoint.dto.myhr.ProfileChangeRequestResponse>of() : changes) {
            if (request != null && request.status() == ProfileChangeStatus.PENDING) profileWaiting++;
        }

        List<String> documentsToDo = new ArrayList<>();
        long documentsWaiting = 0;
        if (documentRequestService != null) {
            List<MyHrOwnDocumentRequestHistory> history = documentRequestService.listOwnHistory(actor.userId());
            for (MyHrOwnDocumentRequestHistory request : history == null ? List.<MyHrOwnDocumentRequestHistory>of() : history) {
                if (request == null || request.status() == null) continue;
                boolean needsEmployee = request.status() == DocumentRequestStatus.RETURNED_FOR_CORRECTION
                        || (request.status() == DocumentRequestStatus.PENDING && !request.requestedByMe());
                boolean waitingForHr = request.status() == DocumentRequestStatus.SUBMITTED
                        || (request.status() == DocumentRequestStatus.PENDING && request.requestedByMe());
                if (needsEmployee) {
                    String what = request.requestTitle() != null && !request.requestTitle().isBlank() ? request.requestTitle()
                            : fieldLabel(request.documentType());
                    documentsToDo.add(what + (request.status() == DocumentRequestStatus.RETURNED_FOR_CORRECTION
                            ? " (returned for correction)" : ""));
                } else if (waitingForHr) {
                    documentsWaiting++;
                }
            }
        }

        StringBuilder reply = new StringBuilder("Employees don't approve anything in Waypoint, so nothing is waiting for your "
                + "approval. Your own open items:\nNeeds your action:");
        boolean anyAction = false;
        if (!toFix.isEmpty()) {
            anyAction = true;
            reply.append("\n- ").append(toFix.size()).append(' ').append(plural(toFix.size(), "timesheet"))
                    .append(" to submit or correct: ");
            List<String> periods = new ArrayList<>();
            for (com.waypoint.dto.myhr.TimesheetResponse timesheet : toFix.subList(0, Math.min(toFix.size(), MAX_LISTED_REVIEW_ITEMS))) {
                periods.add(periodText(timesheet.periodStartDate(), timesheet.periodEndDate()) + " ("
                        + (timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED ? "rejected" : "draft") + ")");
            }
            reply.append(String.join(", ", periods)).append(toFix.size() > MAX_LISTED_REVIEW_ITEMS
                    ? ", and " + (toFix.size() - MAX_LISTED_REVIEW_ITEMS) + " more" : "").append('.');
        }
        if (!documentsToDo.isEmpty()) {
            anyAction = true;
            reply.append("\n- ").append(documentsToDo.size()).append(" open document ")
                    .append(plural(documentsToDo.size(), "request")).append(" for you to complete: ")
                    .append(String.join(", ", documentsToDo.subList(0, Math.min(documentsToDo.size(), MAX_LISTED_REVIEW_ITEMS))))
                    .append(documentsToDo.size() > MAX_LISTED_REVIEW_ITEMS
                            ? ", and " + (documentsToDo.size() - MAX_LISTED_REVIEW_ITEMS) + " more" : "").append('.');
        }
        if (!anyAction) reply.append(" nothing right now.");

        reply.append("\nWaiting for HR or your manager (nothing for you to do):");
        reply.append("\n- ").append(awaitingReview > 0 ? awaitingReview + " submitted " + plural((int) awaitingReview, "timesheet")
                + " awaiting review." : "no submitted timesheets awaiting review.");
        reply.append("\n- ").append(profileWaiting > 0 ? profileWaiting + " pending profile change "
                + plural((int) profileWaiting, "request") + " awaiting HR." : "no pending profile change requests.");
        reply.append("\n- ").append(documentsWaiting > 0 ? documentsWaiting + " document "
                + plural((int) documentsWaiting, "request") + " awaiting HR." : "no document requests awaiting HR.");
        log.info("{} provider=none pending-overview=true employee-action={} waiting={}", PROVIDER_USED_MARKER,
                toFix.size() + documentsToDo.size(), awaitingReview + profileWaiting + documentsWaiting);
        return new MyHrChatResponse(reply.toString()).withActions(List.of(
                MyHrConversationIntents.Page.TIMESHEETS.name(),
                MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name(),
                MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name()));
    }

    /**
     * A timesheet waits for its employee when it is a DRAFT, or REJECTED and not since replaced: a later record of the
     * SAME work period that was submitted or approved is the resubmission, so the older rejection needs nothing more.
     */
    private static boolean timesheetNeedsEmployee(com.waypoint.dto.myhr.TimesheetResponse timesheet,
                                                  List<com.waypoint.dto.myhr.TimesheetResponse> all) {
        if (timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT) return true;
        if (timesheet.status() != com.waypoint.enums.TimesheetStatus.REJECTED) return false;
        LocalDateTime rejectedAt = recordOrder(timesheet);
        return all.stream().noneMatch(other -> other != timesheet
                && timesheet.periodStartDate().equals(other.periodStartDate())
                && timesheet.periodEndDate().equals(other.periodEndDate())
                && (other.status() == com.waypoint.enums.TimesheetStatus.SUBMITTED
                    || other.status() == com.waypoint.enums.TimesheetStatus.APPROVED)
                && recordOrder(other) != null && (rejectedAt == null || recordOrder(other).isAfter(rejectedAt)));
    }

    private static LocalDateTime recordOrder(com.waypoint.dto.myhr.TimesheetResponse record) {
        return record.updatedAt() != null ? record.updatedAt()
                : record.submittedAt() != null ? record.submittedAt() : record.createdAt();
    }

    private MyHrChatResponse pendingOverviewResponse(VerifiedMyHrActor actor) {
        log.info("{} provider=none pending-overview=true", PROVIDER_USED_MARKER);
        if (actor.role() == UserRole.EMPLOYEE) {
            return employeePendingOverview(actor);
        }
        boolean admin = actor.role() == UserRole.COMPANY_ADMIN;
        MyHrTimesheetQueueSummary timesheets = timesheetService.queueSummary(actor.userId());
        MyHrProfileChangeQueueSummary profileChanges = profileChangeRequestService.queueSummary(actor.userId());
        List<String> parts = new ArrayList<>();
        parts.add(timesheets.submitted() + " submitted " + plural(timesheets.submitted(), "timesheet") + " to review");
        parts.add(profileChanges.pending() + " pending profile change " + plural(profileChanges.pending(), "request"));
        if (documentRequestService != null) {
            MyHrDocumentRequestQueueSummary documents = documentRequestService.queueSummary(actor.userId());
            parts.add(admin
                    ? documents.pendingAdminApproval() + " document " + plural(documents.pendingAdminApproval(), "request")
                    + " awaiting your approval"
                    : documents.pendingFulfillment() + " document " + plural(documents.pendingFulfillment(), "request")
                    + " to fulfill and " + documents.awaitingI983Review() + " I-983 "
                    + plural(documents.awaitingI983Review(), "request") + " to review");
        }
        String scope = admin ? "in your organization" : "assigned to you";
        return new MyHrChatResponse("Waiting for you (" + scope + "): " + String.join("; ", parts) + ".")
                .withActions(List.of(MyHrConversationIntents.Page.TIMESHEETS.name(),
                        MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name(),
                        admin ? MyHrConversationIntents.Page.DOCUMENT_APPROVALS.name()
                                : MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name()));
    }

    /**
     * Pending work narrowed by kind and, for reviewers, by person. Reviewers: only items waiting for THEIR action,
     * from the same queues (and scope) as the review pages; a rejected timesheet waits for the employee and is never
     * listed as reviewer work. Employees: their own items waiting for THEM (drafts and rejected timesheets to correct,
     * open requests). {@code message}, when given, may name the person; {@code ordinal} is a position in the
     * reviewer's CURRENT scope. Records its typed state so "all" or "timesheets" can narrow it next.
     */
    MyHrChatResponse pendingWorkResponse(VerifiedMyHrActor actor, WaviTools.WorkArea area, Integer ordinal,
                                         String message, Turn turn) {
        WaviTools.WorkArea kind = area == null ? WaviTools.WorkArea.ALL : area;
        if (!isReviewer(actor)) {
            if (turn != null) turn.answeredWith(WaviTools.GET_MY_PENDING_WORK,
                    kind == WaviTools.WorkArea.ALL ? Map.of() : Map.of("area", kind.name()));
            return switch (kind) {
                case ALL -> pendingOverviewResponse(actor);
                case TIMESHEETS -> myTimesheetListResponse(actor, WaviTools.TimesheetStatusFilter.NEEDS_ACTION.name());
                case PROFILE_CHANGES -> myRequestListResponse(actor, WaviTools.RequestListKind.PROFILE_CHANGE.name(),
                        WaviTools.RequestStatusFilter.OPEN.name());
                case DOCUMENT_REQUESTS -> myRequestListResponse(actor, WaviTools.RequestListKind.DOCUMENT_REQUEST.name(),
                        WaviTools.RequestStatusFilter.OPEN.name());
            };
        }
        ScopedPerson person = null;
        if (ordinal != null || (message != null && !message.isBlank())) {
            PersonResolution resolution = resolvePerson(teamScope(actor), message, ordinal);
            if (resolution.problem() != null) return resolution.problem();
            person = resolution.person();
        }
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        if (kind != WaviTools.WorkArea.ALL) state.put("area", kind.name());
        if (person != null) state.putAll(personState(person));
        if (turn != null) turn.answeredWith(WaviTools.GET_MY_PENDING_WORK, state);
        if (kind != WaviTools.WorkArea.ALL) return reviewQueueItemsResponse(actor, reviewQueueFor(kind), person);
        if (person == null) return pendingOverviewResponse(actor);
        // Every kind, for one person: each review queue narrowed to them.
        StringBuilder reply = new StringBuilder("Work waiting for your review from ")
                .append(displayName(person.person().name())).append(':');
        List<String> actions = new ArrayList<>();
        for (WaviTools.ReviewQueue queue : WaviTools.ReviewQueue.values()) {
            MyHrChatResponse part = reviewQueueItemsResponse(actor, queue, person);
            reply.append("\n\n").append(part.message().replace("\nOpen the page below to review them.", ""));
            if (part.actions() != null) part.actions().stream().filter(action -> !actions.contains(action)).forEach(actions::add);
        }
        reply.append("\n\nRejected timesheets are not listed: they wait for the employee to correct and resubmit.");
        return new MyHrChatResponse(reply.toString()).withActions(actions.stream().limit(WaviToolLayer.MAX_PAGE_KEYS).toList());
    }

    private static WaviTools.ReviewQueue reviewQueueFor(WaviTools.WorkArea area) {
        return switch (area) {
            case PROFILE_CHANGES -> WaviTools.ReviewQueue.PROFILE_CHANGES;
            case DOCUMENT_REQUESTS -> WaviTools.ReviewQueue.DOCUMENT_REQUESTS;
            default -> WaviTools.ReviewQueue.TIMESHEETS;
        };
    }

    /** Tools whose answer selects a person in the reviewer's scope (by position, re-resolved on every use). */
    private static final java.util.Set<String> PERSON_TOOLS = java.util.Set.of(WaviTools.GET_TEAM_MEMBER,
            WaviTools.GET_TEAM_TIMESHEETS, WaviTools.GET_MY_PENDING_WORK, WaviTools.GET_DIRECT_REPORTS);

    /** Stored-state key: the previous answer selected one timesheet RECORD (by a date it covers), not a period. */
    static final String RECORD_SCOPE = "scope";
    static final String SPAN_FROM = "spanFrom";
    static final String SPAN_TO = "spanTo";

    private static Integer personOrdinal(WaviDialogueStore.State state) {
        if (state == null || !PERSON_TOOLS.contains(state.tool())) return null;
        return state.arguments().get("ordinal") instanceof Number number ? number.intValue() : null;
    }

    /** The day, week or month a stored team-timesheet state named; null when it named none or no longer validates. */
    private MyHrConversationIntents.TimesheetRange periodOf(WaviDialogueStore.State state) {
        Map<String, Object> period = new java.util.LinkedHashMap<>();
        for (String name : List.of("period", "date", "month", "range", "relative", "weeksAgo")) {
            if (state.arguments().containsKey(name)) period.put(name, state.arguments().get(name));
        }
        LocalDate today = state.referenceDate() == null ? LocalDate.now(clock) : state.referenceDate();
        if (!period.containsKey("period")) {
            // Compatibility with a pre-range contract state already recorded by this application instance.
            try {
                Object from = state.arguments().get(SPAN_FROM);
                Object to = state.arguments().get(SPAN_TO);
                return from == null || to == null ? null : MyHrConversationIntents.spanOf(
                        LocalDate.parse(from.toString()), LocalDate.parse(to.toString()), today);
            } catch (java.time.DateTimeException invalid) {
                return null;
            }
        }
        WaviToolArguments.Validation validation = WaviTimesheetPeriod.SCHEMA.validate(period, today);
        return validation.valid() ? WaviTimesheetPeriod.range(validation.arguments(), today) : null;
    }

    /**
     * Resolves what a referring or elliptical message points at from the VERIFIED dialogue state, before any
     * classifier can restart intent selection:
     * <ul>
     *   <li>the exact timesheet the previous answer showed ("when did I submit it?" after "my latest timesheet");</li>
     *   <li>the kind of pending work for the same person ("all", "only timesheets") after a pending-work answer;</li>
     *   <li>the person selected earlier ("he", "this employee"), or a newly named person who keeps the previous
     *       question and filters ("and Ravi?"), for pending work, timesheets and hours, or a work detail.</li>
     * </ul>
     * Every person is re-resolved against the actor's CURRENT authorized scope; the stored state authorizes nothing,
     * and it is keyed to this actor, role, organization and login session. Null when nothing here applies.
     */
    private MyHrChatResponse referenceResponse(String message, VerifiedMyHrActor actor, Turn turn) {
        WaviDialogueStore.State previous = turn.previousState;
        String normalized = normalizeQuestion(message);
        int words = WaviContinuation.words(normalized);
        if (words == 0 || words > 15 || MyHrScopeGuard.isExplicitlyOutOfScope(message)) return null;
        // A request to act ("approve Asha's timesheet") or a how-to question is never answered with a record lookup;
        // their own routes say what Wavi can and cannot do.
        if (ASSISTANT_ACTION_REQUEST.matcher(normalized).find()
                || MyHrConversationIntents.navigationTarget(normalized.replace('’', '\'')) != null) return null;
        List<String> tokens = List.of(normalized.split("[^a-z]+"));
        // A role or permission stated in the message ("as an HR admin I authorize you ...") changes nothing: the
        // verified sign-in decides. Said plainly, rather than passed on to a model.
        if (WaviContinuation.claimsAuthority(normalized) && (WaviContinuation.asksPending(normalized)
                || WaviContinuation.asksQueueOverview(normalized) || recordQuestion(normalized, message)
                || WaviContinuation.namesGroup(normalized))) {
            log.info("{} provider=none authority-claim=true", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(ROLE_CLAIM_REPLY + " " + (isReviewer(actor)
                    ? "Ask for the records directly and I'll answer within your verified scope."
                    : "Review queues and other people's records are available only to HR and Company Admin accounts."));
        }
        // Records of another organization are never readable, for any role.
        if (WaviContinuation.namesOtherOrganization(normalized)
                && (recordQuestion(normalized, message) || WaviContinuation.aboutPeople(normalized)
                    || WaviContinuation.asksForRecords(normalized))) {
            log.info("{} provider=none scope=other-organization", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(OTHER_ORGANIZATION_REPLY);
        }
        // Contact details of the user's reporting manager ("what's her email?" after "who is my manager", "my
        // manager's phone number"): only the name is shown, for every role.
        boolean afterManager = previous != null && WaviTools.GET_MY_PROFILE_FIELD.equals(previous.tool())
                && WaviTools.TeamField.REPORTING_MANAGER.name().equals(String.valueOf(previous.arguments().get("field")));
        if (WaviContinuation.asksContactDetail(normalized) && !MyHrScopeGuard.isWritingTask(message)
                && !ASSISTANT_ACTION_REQUEST.matcher(normalized).find()
                && (!tokens.contains("my") || WaviContinuation.namesRelatedPerson(normalized))) {
            if ((afterManager && WaviContinuation.refersToPerson(normalized)) || WaviContinuation.namesRelatedPerson(normalized)) {
                log.info("{} provider=none manager-contact=true", PROVIDER_USED_MARKER);
                turn.keepPreviousState = true;
                return new MyHrChatResponse(MANAGER_CONTACT_REPLY);
            }
        }
        // Hours of ONE of the user's own timesheets, named by reference rather than by period: "my latest/recent
        // timesheet", or a follow-up ("how many hours did I submit?", "what's the total?", "for Oct 12?") right after
        // an answer that identified a timesheet. The record's hours, not a day's or the latest status.
        boolean ownSubject = WaviContinuation.hasFirstPerson(normalized) || (actor.role() == UserRole.EMPLOYEE
                && !WaviContinuation.refersToPerson(normalized) && !WaviContinuation.namesGroup(normalized));
        boolean hoursAsked = WaviContinuation.asksForHours(normalized) || WaviContinuation.asksBareTotal(normalized);
        if (hoursAsked && ownSubject && !WaviContinuation.namesThirdParty(message) && !WaviContinuation.isHowTo(normalized)
                && !WaviContinuation.isConditional(normalized) && !WaviContinuation.asksDaily(normalized)
                && !WaviContinuation.namesRelatedPerson(normalized)) {
            MyHrConversationIntents.TimesheetRange named = MyHrConversationIntents.periodIn(
                    WaviContinuation.withoutQuantityInterrogative(normalized), LocalDate.now(clock));
            boolean submittedOnly = hoursMetricOf(normalized) == HoursMetric.SUBMITTED;
            if (named == null && WaviContinuation.asksLatest(normalized)) {
                return ownLatestTimesheetHoursResponse(actor, turn, submittedOnly);
            }
            boolean recordBefore = previous != null && WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                    && "RECORD".equals(String.valueOf(previous.arguments().get(RECORD_SCOPE)));
            boolean latestBefore = previous != null && WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                    && WaviTimesheetPeriod.Period.LATEST.name().equals(previous.arguments().get("period"));
            if (recordBefore && words <= 12) {
                LocalDate selected = LocalDate.parse(String.valueOf(previous.arguments().get("date")));
                // No period, or the same single date the record was identified by: still that timesheet.
                if (named == null || (named.singleDay() && named.from().equals(selected))) {
                    log.info("{} provider=none continuation=true follow-up=record-hours", PROVIDER_USED_MARKER);
                    return ownTimesheetForDateResponse(actor, selected, true, turn);
                }
            }
            if (latestBefore && named == null && words <= 12) return ownLatestTimesheetHoursResponse(actor, turn, submittedOnly);
        }
        // A date that IDENTIFIES one of the user's own timesheets ("my oct 12 timesheet", "the timesheet for
        // 2026-10-05"): that record - its status, submission and hours - not the hours of that one day.
        // An Employee can only mean their own timesheet, so "the timesheet for Oct 21" needs no "my".
        if ((WaviContinuation.hasFirstPerson(normalized) || (actor.role() == UserRole.EMPLOYEE
                && !WaviContinuation.refersToPerson(normalized) && !WaviContinuation.namesGroup(normalized)))
                && !WaviContinuation.namesThirdParty(message)
                && !WaviContinuation.namesRelatedPerson(normalized) && !WaviContinuation.isHowTo(normalized)
                && !WaviContinuation.isConditional(normalized) && !WaviContinuation.asksDaily(normalized)) {
            LocalDate identified = WaviDateSpan.recordDate(normalized, LocalDate.now(clock));
            if (identified != null) {
                return ownTimesheetForDateResponse(actor, identified, WaviContinuation.asksForHours(normalized)
                        || WaviContinuation.asksWorkDone(normalized), turn);
            }
        }
        // Subject resolution: an Employee asking about SOMEONE ELSE's records gets the privacy reply, never their own
        // records presented as the answer.
        if (actor.role() == UserRole.EMPLOYEE && !tokens.contains("my") && recordQuestion(normalized, message)
                && (WaviContinuation.namesThirdParty(message) || WaviContinuation.refersToSpecificPerson(normalized))) {
            log.info("{} provider=none subject=third-party", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(OTHER_PERSON_REPLY);
        }
        // A reviewer's question about waiting work of one kind is a records question, whatever verbs it uses
        // ("which timesheets are waiting on me to approve" is not a how-to question).
        if (isReviewer(actor) && !WaviContinuation.refersToSpecificPerson(normalized)
                && !WaviContinuation.hasNameShapedToken(message)) {
            WaviContinuation.QueueForm form = WaviContinuation.reviewerQueueForm(normalized);
            WaviTools.WorkArea kind = WaviContinuation.workArea(normalized);
            // "What's in my queue?", "what's waiting for approval?": every kind of review work, when no kind is named
            // and no established overview wording matched.
            if (kind == null && !WaviContinuation.isHowTo(normalized) && WaviContinuation.asksQueueOverview(normalized)
                    && !MyHrConversationIntents.isPendingOverviewQuestion(normalized) && !hasQueueCountRoute(message)
                    && !WaviContinuation.aboutPeople(normalized)) {
                return pendingWorkResponse(actor, WaviTools.WorkArea.ALL, null, null, turn);
            }
            // People questions about the whole scope: "how many employees do we have", "who are the HR users".
            // (A question about who did or didn't SUBMIT is a timesheet question about people, answered below.)
            if (WaviContinuation.aboutPeople(normalized) && !WaviContinuation.asksPending(normalized) && kind == null
                    && !WaviContinuation.asksWhoSubmitted(normalized)
                    && !WaviContinuation.isHowTo(normalized) && !MyHrConversationIntents.isDirectReportsQuestion(normalized)
                    && !MyHrKnowledgeGrounding.isPolicyQuestion(message) && !WaviContinuation.isConditional(normalized)) {
                if (WaviContinuation.countsPeople(normalized)) {
                    turn.answeredWith(WaviTools.GET_PEOPLE_SUMMARY, Map.of());
                    return peopleSummaryResponse(actor);
                }
                if (WaviContinuation.asksForPeople(normalized) || WaviContinuation.asksForRecords(normalized)) {
                    return peopleListResponse(actor, WaviContinuation.askedPeopleRole(normalized), turn);
                }
            }
            // A count form keeps the queue-count route that words it for the role, when one recognises it. Any other
            // count or yes/no wording ("is there any pending work for timesheets?") still gets the waiting records
            // (their number, then the list): it never falls through to a model that cannot read the queue.
            boolean unrouted = form == WaviContinuation.QueueForm.COUNT && !hasQueueCountRoute(message);
            if (unrouted && WaviContinuation.asksCount(normalized)) {
                // "How many ... are waiting?" in wording no count route knows: the same queue count those routes give.
                String count = switch (kind) {
                    case TIMESHEETS -> timesheetQueueResponse(actor, actor.userId(), TimesheetQueueQuestion.MY_QUEUE);
                    case PROFILE_CHANGES -> profileChangeQueueResponse(actor, actor.userId(), ProfileChangeQueueQuestion.MY_QUEUE);
                    default -> documentRequestQueueResponse(actor, actor.userId(), DocumentRequestQueueQuestion.MY_QUEUE);
                };
                if (count != null) {
                    // The answered queue is the context for "who submitted them?", as on the established count routes.
                    if (turn != null && kind != WaviTools.WorkArea.ALL) {
                        turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", kind.name()));
                    }
                    return new MyHrChatResponse(count);
                }
            }
            // A list form inferred only from a non-count, non-yes/no wording ("what document requests are assigned to
            // me?") keeps the established queue route when one recognises it; an explicit list request never does.
            boolean listAsked = form == WaviContinuation.QueueForm.LIST
                    && (WaviContinuation.asksForRecords(normalized) || !hasQueueCountRoute(message));
            if (listAsked || unrouted) {
                return pendingWorkResponse(actor, kind, null, null, turn);
            }
        }
        boolean howTo = WaviContinuation.isHowTo(normalized);
        WaviTools.WorkArea areaNamed = WaviContinuation.workArea(normalized);
        boolean aboutTimesheets = areaNamed == WaviTools.WorkArea.TIMESHEETS || WaviContinuation.asksForHours(normalized)
                || asksUnitlessTotal(normalized, message)
                // Only timesheets are "submitted" by people in Waypoint: "who hasn't submitted for last week?".
                // "Who submitted them?" refers to the previous answer and keeps its own continuation.
                || (areaNamed == null && WaviContinuation.asksWhoSubmitted(normalized)
                    && !WaviContinuation.refersBack(normalized) && !WaviContinuation.dependsOnContext(normalized));
        // Hours of "they/them" with nobody selected: other people, never the asker's own timesheet.
        if (WaviContinuation.asksForHours(normalized) && !howTo && previous == null
                && WaviContinuation.refersToPerson(normalized) && !WaviContinuation.refersToSpecificPerson(normalized)
                && !WaviContinuation.hasFirstPerson(normalized) && !WaviContinuation.hasNameShapedToken(message)) {
            if (!isReviewer(actor)) return new MyHrChatResponse(OTHER_PERSON_REPLY);
            return teamTimesheetsResponse(actor, teamQueryFrom(message, WaviTools.TimesheetStatusFilter.ANY.name(), null),
                    null, turn, false);
        }
        // "When was my October timesheet approved?" / "who approved my last timesheet?": the review decision on the
        // user's own records (its date and reviewer), not their current status.
        if (areaNamed == WaviTools.WorkArea.TIMESHEETS && !howTo && tokens.contains("my")
                && WaviContinuation.askedReviewDecision(normalized) != null
                // The date of the latest approval keeps its established route; a period or "who" is answered here.
                && (tokens.contains("who") || MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)) != null)
                && !WaviContinuation.namesThirdParty(message) && !WaviContinuation.isConditional(normalized)) {
            return ownReviewDecisionsResponse(actor, WaviContinuation.askedReviewDecision(normalized),
                    MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)), WaviContinuation.asksLatest(normalized));
        }
        // A reviewer's count of scope timesheets in one status over a period ("how many timesheets were rejected
        // last month?"): the scoped records are there, so they are counted and listed rather than refused.
        if (isReviewer(actor) && areaNamed == WaviTools.WorkArea.TIMESHEETS && !howTo && WaviContinuation.asksCount(normalized)
                && !WaviContinuation.hasFirstPerson(normalized) && !WaviContinuation.asksPending(normalized)
                && WaviContinuation.timesheetStatusFilter(normalized) != null
                && MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)) != null
                && classifyTimesheetQueueQuestion(message) == null) {
            return teamTimesheetsResponse(actor, teamQueryFrom(message, WaviTools.TimesheetStatusFilter.ANY.name(), null),
                    message, turn, false);
        }
        // Timesheets or hours of someone named only by a relationship ("my manager's hours"): never the user's own
        // records presented as that person's. A reviewer is asked to name someone in their scope.
        if (aboutTimesheets && !howTo && WaviContinuation.namesRelatedPerson(normalized)) {
            log.info("{} provider=none subject=related-person", PROVIDER_USED_MARKER);
            if (!isReviewer(actor)) return new MyHrChatResponse(OTHER_PERSON_REPLY);
            return new MyHrChatResponse(WaviClarification.prompt(WaviTools.ClarificationTopic.PEOPLE,
                    WaviTools.MissingSlot.PERSON, actor.role()) + " Name them, or ask \"Who reports to me?\" to see the list.");
        }
        // "Who submitted ...?" / "who hasn't submitted ...?" asks about other people's submissions: the reviewer's
        // scope for a reviewer, a refusal for an Employee - never the asker's own timesheet status.
        if (aboutTimesheets && !howTo && WaviContinuation.asksWhoSubmitted(normalized)) {
            if (!isReviewer(actor)) return new MyHrChatResponse(TIMESHEET_QUEUE_REVIEWERS_ONLY);
            MyHrConversationIntents.TimesheetRange asked = MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock));
            if (WaviContinuation.isNegated(normalized)) return missingSubmissionsResponse(actor, asked, turn);
            String named = WaviContinuation.timesheetStatusFilter(normalized);
            return teamTimesheetsResponse(actor, new WaviTeamTimesheetQuery(named == null
                    ? WaviTools.TimesheetStatusFilter.ANY.name() : named, asked, false, null), null, turn, false);
        }
        // A request for records as a list ("which of my timesheets were rejected?") is a lookup even when it uses a
        // word the how-to guidance also knows.
        // "Which date did that cover?": the period the previous own-timesheet answer resolved, read from the dialogue
        // state. No new lookup: the question is about the answer, not about the records.
        MyHrChatResponse scope = previousScopeResponse(previous, normalized, turn);
        if (scope != null) return scope;
        MyHrChatResponse ownListFirst = howTo ? null : ownListResponse(actor, normalized, tokens, turn);
        if (ownListFirst != null) return ownListFirst;
        // A reviewer's question about the whole scope's timesheets or hours ("how many hours did my team log last
        // week?", "list rejected timesheets in the organization"): the same scoped records, for everyone in scope.
        // A reviewer's "we/our/us" ("do we have any rejected timesheets?") is their whole scope, like "my team".
        if (isReviewer(actor) && aboutTimesheets && !howTo
                && (WaviContinuation.namesGroup(normalized) || WaviContinuation.firstPersonPlural(normalized))
                && !WaviContinuation.refersToPerson(normalized) && !WaviContinuation.asksPending(normalized)
                && (WaviContinuation.asksForHours(normalized) || !WaviContinuation.asksCount(normalized))
                && !hasQueueCountRoute(message)) {
            WaviTeamTimesheetQuery asked = teamQueryFrom(message, WaviTools.TimesheetStatusFilter.ANY.name(), null);
            // Only a question with its own explicit handle (hours, a period or a status); vaguer wording ("which team
            // timesheets still need something done") is left to the router.
            boolean specific = asked.hours() || asked.range() != null
                    || !WaviTools.TimesheetStatusFilter.ANY.name().equals(asked.status());
            if (specific) return teamTimesheetsResponse(actor, asked, message, turn, false);
        }
        // How-to wording keeps the workflow guidance, unless a reviewer names someone in scope and asks about their
        // records ("show me Asha Rao's address change request"), which is a lookup, not a how-to.
        if (classifyWorkflowIntent(message) != null && !(isReviewer(actor) && WaviContinuation.hasNameShapedToken(message)
                && !howTo && !ASSISTANT_ACTION_REQUEST.matcher(normalized).find())) return null;
        MyHrChatResponse ownListNext = ownListFollowUp(actor, previous, message, normalized, turn);
        if (ownListNext != null) return ownListNext;
        MyHrChatResponse ownPeriod = ownPeriodFollowUp(actor, previous, normalized, turn);
        if (ownPeriod != null) return ownPeriod;
        MyHrChatResponse ownList = ownListResponse(actor, normalized, tokens, turn);
        if (ownList != null) return ownList;
        MyHrChatResponse teamFollowUp = teamTimesheetFollowUp(actor, previous, message, normalized, turn);
        if (teamFollowUp != null) return teamFollowUp;
        if (previous != null && WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                && WaviTimesheetPeriod.Period.LATEST.name().equals(previous.arguments().get("period"))
                && WaviContinuation.asksSubmissionTime(normalized)
                && (WaviContinuation.refersBack(normalized) || words <= 6)) {
            MyHrChatResponse referenced = referencedLatestSubmission(actor, turn, previous);
            if (referenced != null) return referenced;
        }
        // "And the one before?" after the user's latest timesheet: the record before it, from the same own list.
        if (previous != null && WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                && WaviTimesheetPeriod.Period.LATEST.name().equals(previous.arguments().get("period")) && words <= 6) {
            MyHrConversationIntents.TimesheetRange anchor = MyHrConversationIntents.periodIn("this week", LocalDate.now(clock));
            if (anchor.previous().equals(MyHrConversationIntents.followUpRange(normalized, LocalDate.now(clock), anchor))) {
                turn.keepPreviousState = true;
                return earlierOwnTimesheetResponse(actor);
            }
        }
        // An Employee narrowing their own open items ("the profile one", "just timesheets") after the overview.
        if (!isReviewer(actor) && previous != null && WaviTools.GET_MY_PENDING_WORK.equals(previous.tool()) && words <= 5
                && !WaviContinuation.isHowTo(normalized)) {
            WaviTools.WorkArea kind = WaviContinuation.workArea(normalized);
            if (kind != null && kind != WaviTools.WorkArea.ALL) return pendingWorkResponse(actor, kind, null, null, turn);
        }
        if (!isReviewer(actor)) return null;
        boolean pending = WaviContinuation.asksPending(normalized);
        WaviTools.WorkArea area = WaviContinuation.workArea(normalized);
        boolean hours = WaviContinuation.asksForHours(normalized) || (WaviContinuation.asksWorkDone(normalized)
                && MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)) != null)
                || (asksUnitlessTotal(normalized, message) && !WaviContinuation.hasFirstPerson(normalized));
        boolean timesheets = area == WaviTools.WorkArea.TIMESHEETS || hours;
        boolean refersToPerson = WaviContinuation.refersToPerson(normalized);
        Integer previousPerson = personOrdinal(previous);
        boolean peopleContext = previous != null && PERSON_TOOLS.contains(previous.tool());
        // "Who are they from?" / "which ones?" about the waiting work just listed: the same list again, which names them.
        if (previous != null && WaviTools.GET_MY_PENDING_WORK.equals(previous.tool()) && words <= 6
                && !WaviContinuation.hasNameShapedToken(message) && !WaviContinuation.refersToSpecificPerson(normalized)
                && (WaviContinuation.asksWhichOnes(normalized) || (WaviContinuation.asksForPeople(normalized)
                    && WaviContinuation.refersBack(normalized)))) {
            Integer same = previousPerson;
            if (same != null) {
                PersonResolution stored = storedPerson(teamScope(actor), previous);
                if (stored.problem() != null) return stored.problem();
                same = stored.person().ordinal();
            }
            return pendingWorkResponse(actor, areaOf(previous), same, null, turn);
        }
        // "all", "just timesheets": the kind of pending work, for the same person as the previous pending answer.
        if (previous != null && WaviTools.GET_MY_PENDING_WORK.equals(previous.tool()) && area != null && words <= 5
                && !refersToPerson && !WaviContinuation.hasNameShapedToken(message)) {
            if (previousPerson == null) return pendingWorkResponse(actor, area, null, null, turn);
            PersonResolution stored = storedPerson(teamScope(actor), previous);
            if (stored.problem() != null) return stored.problem();
            return pendingWorkResponse(actor, area, stored.person().ordinal(), null, turn);
        }
        // A reviewer asking what is pending in ONE kind of work, naming no owner of their own ("pending work for
        // timesheets", "anything waiting in document requests?"): the items waiting for THEIR review in that kind -
        // not their own records and not every kind. "My timesheet" without approval/review stays the user's own; a
        // count question keeps the queue count route.
        // Only an intent this step resolves is worth reading the people list for: pending work, timesheets or hours,
        // a person reference, or a short follow-up to an answer about people. Anything else keeps its own route.
        // A named person's requests of one kind ("what did Asha request to change?", "Ravi's document requests").
        // "Profile" alone means the person's profile (a work-detail lookup), not their change requests.
        boolean requestWord = List.of(normalized.split("[^a-z]+")).stream()
                .anyMatch(token -> token.startsWith("request") || token.equals("change") || token.equals("changes"));
        boolean requestKind = (area == WaviTools.WorkArea.DOCUMENT_REQUESTS
                || (area == WaviTools.WorkArea.PROFILE_CHANGES && requestWord))
                && WaviContinuation.hasNameShapedToken(message) && !WaviContinuation.isHowTo(normalized);
        boolean resolvable = pending || timesheets || refersToPerson || requestKind || (peopleContext && words <= 6);
        if (!resolvable) return null;
        // Someone identified by an employee ID that is not in this reviewer's scope.
        if (!WaviContinuation.hasFirstPerson(normalized) && WaviContinuation.hasEmployeeIdToken(message)
                && byEmployeeId(message, teamScope(actor)) == null && (pending || timesheets || requestKind)) {
            log.info("{} provider=none subject=not-in-scope-id", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(actor.role() == UserRole.HR ? PERSON_NOT_IN_SCOPE_HR : PERSON_NOT_IN_SCOPE_ADMIN)
                    .withActions(List.of(MyHrConversationIntents.Page.EMPLOYEES.name()));
        }
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = null;
        ScopedPerson person = null;
        // A name typed in lower case ("hours for ravi last week") carries no capital letter to notice: a reviewer's
        // records question that names no owner of its own is checked against the people in scope as well.
        boolean unowned = (pending || timesheets || requestKind) && !WaviContinuation.hasFirstPerson(normalized);
        // The people list is read only when the wording can hold a name: never for "OCT HOURS" or "pending timesheets".
        boolean lowerCaseName = unowned && WaviContinuation.mayNamePersonInLowerCase(normalized);
        if (peopleContext || lowerCaseName || WaviContinuation.hasNameShapedToken(message)) {
            people = teamScope(actor);
            // Several people named in full ("compare Ravi Teja and Asha Rao hours last week") is not an ambiguity:
            // the same scoped records, for exactly those people.
            if (timesheets && !pending && fullyNamed(message, people).size() > 1) {
                return teamTimesheetsResponse(actor, teamQueryFrom(message, WaviTools.TimesheetStatusFilter.ANY.name(),
                        null), message, turn, false);
            }
            PersonResolution named = resolvePerson(people, message, null);
            if (named.problem() != null) return named.problem();
            person = named.person();
        }
        if (person == null && unowned && !refersToPerson && WaviContinuation.namesUnlistedPerson(message)) {
            // A person is named but is not among the people this reviewer may see: say so, never answer about the
            // reviewer's own records, the whole scope, or (through a model) a guess.
            log.info("{} provider=none subject=not-in-scope", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(actor.role() == UserRole.HR ? PERSON_NOT_IN_SCOPE_HR : PERSON_NOT_IN_SCOPE_ADMIN)
                    .withActions(List.of(MyHrConversationIntents.Page.EMPLOYEES.name()));
        }
        if (person == null && refersToPerson) {
            if (previousPerson == null) {
                // "they/their" may mean the records just counted ("who are they from?"); only an unambiguous person
                // reference ("he", "this employee") is asked about.
                if ((!pending && !timesheets) || !WaviContinuation.refersToSpecificPerson(normalized)) return null;
                // "Is there pending work for this employee?" with no one selected: ask who, and keep the question so
                // the name that follows answers it.
                if (pending) {
                    turn.answeredWith(WaviTools.GET_MY_PENDING_WORK, area == null || area == WaviTools.WorkArea.ALL
                            ? Map.of() : Map.of("area", area.name()));
                } else {
                    WaviTeamTimesheetQuery asked = teamQueryFrom(message, WaviTools.TimesheetStatusFilter.ANY.name(), null);
                    turn.answeredWith(WaviTools.GET_TEAM_TIMESHEETS, teamTimesheetState(asked.status(), asked.range(),
                            asked.hours(), (ScopedPerson) null));
                }
                return new MyHrChatResponse(WaviClarification.prompt(WaviTools.ClarificationTopic.PEOPLE,
                        WaviTools.MissingSlot.PERSON, actor.role()) + " Name them, or ask \"Who reports to me?\" to see the list.");
            }
            if (people == null) people = teamScope(actor);
            PersonResolution referred = storedPerson(people, previous);
            if (referred.problem() != null) return referred.problem();
            person = referred.person();
        }
        if (person == null) return null;
        if (pending || requestKind) return pendingWorkResponse(actor, area, person.ordinal(), null, turn);
        if (timesheets) {
            WaviTeamTimesheetQuery asked = inheritedPeriod(teamQueryFrom(message,
                    WaviTools.TimesheetStatusFilter.ANY.name(), person.ordinal()), previous);
            return teamTimesheetsResponse(actor, asked, null, turn, false, teamViewOf(normalized, asked));
        }
        String field = teamFieldIn(message);
        boolean ownQuestion = !WaviTools.TeamField.SUMMARY.name().equals(field)
                || WaviContinuation.timesheetStatusFilter(normalized) != null || words > 6;
        if (!ownQuestion && previous != null) {
            // No question of its own ("and Ravi?", "what about her?"): the previous question, for this person.
            switch (previous.tool()) {
                case WaviTools.GET_MY_PENDING_WORK -> {
                    return pendingWorkResponse(actor, areaOf(previous), person.ordinal(), null, turn);
                }
                case WaviTools.GET_TEAM_TIMESHEETS -> {
                    Object status = previous.arguments().get("status");
                    return teamTimesheetsResponse(actor, new WaviTeamTimesheetQuery(status == null ? null
                            : status.toString(), periodOf(previous),
                            WaviTools.TeamTimesheetDetail.HOURS.name().equals(previous.arguments().get("detail")),
                            person.ordinal()), null, turn, false);
                }
                case WaviTools.GET_TEAM_MEMBER -> {
                    Object previousField = previous.arguments().get("field");
                    return teamMemberResponse(actor, previousField == null ? field : previousField.toString(),
                            person.ordinal(), null, turn, false);
                }
                default -> { }
            }
        }
        if (refersToPerson && !WaviTools.TeamField.SUMMARY.name().equals(field)) {
            return teamMemberResponse(actor, field, person.ordinal(), null, turn, false);
        }
        return null;
    }

    static final String ROLE_CLAIM_REPLY = "Your signed-in role decides what I can show; I can't act on a role or "
            + "permission stated in a message.";
    static final String OTHER_ORGANIZATION_REPLY = "I can only show records from your own organization in Waypoint; "
            + "other organizations' people and records are never available here.";
    static final String MANAGER_CONTACT_REPLY = "I can tell you your reporting manager's name, but Wavi doesn't show "
            + "contact details such as email addresses or phone numbers. Open Employees or ask HR for how to reach them.";

    /**
     * The user's own timesheet(s) whose work period covers one date the message used to identify a record: each
     * one's period, status, submission time and recorded hours. Several records covering the date (a resubmission)
     * stay separate. With {@code hours}, the hours entered on that date are given as well, so "hours for my Oct 12
     * timesheet" is answered for the record and for the day without guessing which was meant.
     */
    private MyHrChatResponse ownTimesheetForDateResponse(VerifiedMyHrActor actor, LocalDate date, boolean hours, Turn turn) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> covering = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && overlaps(timesheet, date, date))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        log.info("{} provider=none own-timesheet-by-date found={} hours={}", PROVIDER_USED_MARKER, covering.size(), hours);
        // "scope=RECORD": the date identified a timesheet, so a follow-up about hours means that timesheet.
        turn.answeredWith(WaviTools.GET_MY_TIMESHEETS, Map.of("period", WaviTimesheetPeriod.Period.DAY.name(), "date",
                date.toString(), "detail", (hours ? WaviTimesheetPeriod.Detail.HOURS : WaviTimesheetPeriod.Detail.STATUS).name(),
                RECORD_SCOPE, "RECORD"));
        if (covering.isEmpty()) {
            return new MyHrChatResponse("I don't see a timesheet of yours whose period covers " + formatDay(date)
                    + ". If you worked that day, open Timesheets and choose Enter Timesheet to add and submit it.")
                    .withActions(actions);
        }
        String title = covering.size() == 1 ? "Your timesheet covering " + formatDay(date) + ":"
                : covering.size() + " timesheets of yours cover " + formatDay(date) + " (separate submissions, newest first):";
        List<com.waypoint.dto.myhr.TimesheetResponse> own = visible.stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())).toList();
        return recordsReply(title, covering, own, date, hours).withActions(actions);
    }

    /**
     * The user's latest timesheet by work period (and any other submissions for exactly that period), with each
     * submission's status, submission time and recorded hours: "how many hours are on my latest timesheet?".
     */
    private MyHrChatResponse ownLatestTimesheetHoursResponse(VerifiedMyHrActor actor, Turn turn) {
        return ownLatestTimesheetHoursResponse(actor, turn, false);
    }

    /** {@code submittedOnly}: "the hours I SUBMITTED" - the latest timesheet that is not a draft. */
    private MyHrChatResponse ownLatestTimesheetHoursResponse(VerifiedMyHrActor actor, Turn turn, boolean submittedOnly) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && timesheet.periodStartDate() != null && timesheet.periodEndDate() != null).toList();
        com.waypoint.dto.myhr.TimesheetResponse newestDraft = !submittedOnly ? null : own.stream()
                .filter(timesheet -> timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT)
                .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate)).orElse(null);
        if (submittedOnly) {
            own = own.stream().filter(timesheet -> timesheet.status() != com.waypoint.enums.TimesheetStatus.DRAFT).toList();
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> candidates = own;
        com.waypoint.dto.myhr.TimesheetResponse latest = candidates.stream()
                .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate)
                        .thenComparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                                java.util.Comparator.nullsFirst(java.util.Comparator.<LocalDateTime>naturalOrder())))
                .orElse(null);
        log.info("{} provider=none own-latest-timesheet-hours found={}", PROVIDER_USED_MARKER, latest != null);
        if (latest == null) return new MyHrChatResponse("I don't see a timesheet of yours yet.").withActions(actions);
        turn.answeredWith(WaviTools.GET_MY_TIMESHEETS, Map.of("period", WaviTimesheetPeriod.Period.LATEST.name(),
                "detail", WaviTimesheetPeriod.Detail.HOURS.name()));
        List<com.waypoint.dto.myhr.TimesheetResponse> samePeriod = candidates.stream()
                .filter(timesheet -> timesheet.periodStartDate().equals(latest.periodStartDate())
                        && timesheet.periodEndDate().equals(latest.periodEndDate()))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        String which = submittedOnly ? "latest submitted timesheet" : "latest timesheet";
        String title = samePeriod.size() == 1 ? "Your " + which + " (by work period):"
                : "Your " + which + " period has " + samePeriod.size() + " separate submissions, newest first:";
        MyHrChatResponse reply = recordsReply(title, samePeriod, candidates, null, true);
        String note = newestDraft != null && newestDraft.periodStartDate().isAfter(latest.periodStartDate())
                ? "\nYour newer timesheet for " + periodText(newestDraft.periodStartDate(), newestDraft.periodEndDate())
                + " is still a draft and hasn't been submitted." : "";
        return new MyHrChatResponse(reply.message() + note).withActions(actions);
    }

    /**
     * One line per submission: work period, status, submission time and hours (the record's total and, with
     * {@code day}, its entry for that day). Where several submissions hold the same day, the figure Hours Overview
     * counts for that day is stated, never a sum.
     */
    private MyHrChatResponse recordsReply(String title, List<com.waypoint.dto.myhr.TimesheetResponse> covering,
                                          List<com.waypoint.dto.myhr.TimesheetResponse> own, LocalDate day, boolean hours) {
        LocalDate date = day;
        StringBuilder reply = new StringBuilder(title);
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : covering) {
            reply.append("\n- ").append(periodText(timesheet.periodStartDate(), timesheet.periodEndDate())).append(": ")
                    .append(periodStatusPhrase(timesheet.status()));
            reply.append(timesheet.submittedAt() == null ? (timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT
                    ? "" : " (no submission time recorded)") : " (" + submittedText(timesheet.submittedAt()) + ")");
            if (hours) {
                java.math.BigDecimal onDate = java.math.BigDecimal.ZERO;
                boolean entered = false;
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                int days = 0;
                for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                        ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                    if (entry == null || entry.workDate() == null || entry.hoursWorked() == null) continue;
                    total = total.add(entry.hoursWorked());
                    days++;
                    if (entry.workDate().equals(date)) {
                        onDate = onDate.add(entry.hoursWorked());
                        entered = true;
                    }
                }
                reply.append("; ").append(hoursText(total)).append(" on ").append(days).append(' ').append(plural(days, "day"))
                        .append(" in total");
                if (date != null) {
                    reply.append(", ").append(entered ? hoursText(onDate) + " entered on " + formatDay(date)
                            : "no hours entered on " + formatDay(date));
                }
            } else if (timesheet.totalHours() != null && date != null) {
                // A record found by a date: the record's total is for its whole period, the day's entry is separate.
                java.math.BigDecimal onDate = null;
                for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                        ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                    if (entry != null && date.equals(entry.workDate()) && entry.hoursWorked() != null) {
                        onDate = (onDate == null ? java.math.BigDecimal.ZERO : onDate).add(entry.hoursWorked());
                    }
                }
                reply.append("; ").append(hoursText(timesheet.totalHours())).append(" for the whole period, ")
                        .append(onDate == null ? "none entered on " + formatDay(date)
                                : hoursText(onDate) + " entered on " + formatDay(date));
            } else if (timesheet.totalHours() != null) {
                reply.append("; ").append(hoursText(timesheet.totalHours())).append(" recorded");
            }
            if (timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED && timesheet.reviewComment() != null
                    && !timesheet.reviewComment().isBlank()) {
                reply.append("; reviewer comment: ").append(timesheet.reviewComment());
            }
        }
        if (hours && covering.size() > 1 && date != null) {
            TimesheetEffectiveHours.Day counted = TimesheetEffectiveHours.compute(own, date, date).day(date);
            reply.append("\n").append(formatDay(date)).append(" appears on ").append(covering.size())
                    .append(" separate submissions, so their hours for that day aren't added together. Hours Overview "
                            + "counts ").append(hoursText(counted == null ? java.math.BigDecimal.ZERO : counted.hours()))
                    .append(" for that day (the latest submission's entry, or an earlier approved entry when the latest "
                            + "was rejected).");
        } else if (hours && covering.size() > 1) {
            reply.append("\nThese are separate submissions for the same period; their hours aren't added together.");
        }
        return new MyHrChatResponse(reply.toString());
    }

    /** The people in the reviewer's scope, optionally one role of them, by name and job title. */
    private MyHrChatResponse peopleListResponse(VerifiedMyHrActor actor, UserRole role, Turn turn) {
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> matching = people.stream()
                .filter(person -> role == null || role.name().equalsIgnoreCase(String.valueOf(person.personType())))
                .toList();
        log.info("{} provider=none people-list role={} size={}", PROVIDER_USED_MARKER, role, matching.size());
        turn.answeredWith(WaviTools.GET_DIRECT_REPORTS, role == null ? Map.of() : Map.of("role", role.name()));
        List<String> actions = List.of(MyHrConversationIntents.Page.EMPLOYEES.name());
        String who = role == UserRole.HR ? "HR users" : role == UserRole.EMPLOYEE ? "Employee accounts" : "people";
        String scope = actor.role() == UserRole.HR ? "among your active direct reports" : "in your organization";
        if (matching.isEmpty()) {
            return new MyHrChatResponse("I don't see any " + who + " " + scope + ".").withActions(actions);
        }
        int shown = Math.min(matching.size(), 10);
        StringBuilder reply = new StringBuilder().append(matching.size()).append(' ').append(matching.size() == 1
                ? who.replaceAll("s$", "").replace("people", "person") : who).append(' ').append(scope).append(':');
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : matching.subList(0, shown)) {
            reply.append("\n- ").append(displayName(person.name()))
                    .append(person.designation() == null || person.designation().isBlank() ? "" : " (" + person.designation() + ")");
        }
        if (matching.size() > shown) reply.append("\n...and ").append(matching.size() - shown).append(" more. Open Employees to see everyone.");
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    static final String PERSON_NOT_IN_SCOPE_HR ="I can't find that person among your active direct reports, so I "
            + "can't show their records. Check the name, or ask \"Who reports to me?\" to see who you can look up.";
    static final String PERSON_NOT_IN_SCOPE_ADMIN = "I can't find that person among the HR and Employee accounts in "
            + "your organization, so I can't show their records. Check the name, or open Employees to see who you can "
            + "look up.";

    /**
     * A follow-up to an answer about team timesheets that changes only the period ("and the week before?", "what
     * about this week?") or only the status ("only the rejected ones"): the same person (re-resolved and pinned),
     * detail and remaining filters, from the stored state. Null when the message is anything else.
     */
    private MyHrChatResponse teamTimesheetFollowUp(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                                   String message, String normalized, Turn turn) {
        if (!isReviewer(actor) || previous == null || !WaviTools.GET_TEAM_TIMESHEETS.equals(previous.tool())
                || WaviContinuation.hasFirstPerson(normalized)
                // A pronoun can only mean the person the previous answer selected; with none, other rules decide.
                || (WaviContinuation.refersToPerson(normalized) && personOrdinal(previous) == null)
                || WaviContinuation.asksForHours(normalized)
                || WaviContinuation.hasNameShapedToken(message) || WaviContinuation.words(normalized) > 6) return null;
        MyHrConversationIntents.TimesheetRange before = periodOf(previous);
        MyHrConversationIntents.TimesheetRange followed = MyHrConversationIntents.followUpRange(normalized,
                LocalDate.now(clock), before);
        // "What's the total?" / "and the total for October?": the hours, for the named period or else the same one.
        boolean total = asksUnitlessTotal(normalized, message);
        MyHrConversationIntents.TimesheetRange next = followed != null || !total ? followed
                : MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock));
        String status = next != null ? null : WaviContinuation.timesheetStatusFilter(normalized);
        // "Just the submitted ones": in a short follow-up a bare status word is a filter, not the act of submitting.
        if (next == null && status == null && WaviContinuation.words(normalized) <= 5
                && !WaviContinuation.asksSubmissionTime(normalized) && !WaviContinuation.asksForPeople(normalized)
                && WaviContinuation.namesAwaitingStatus(normalized)) {
            status = WaviTools.TimesheetStatusFilter.SUBMITTED.name();
        }
        boolean hoursBefore = total || WaviTools.TeamTimesheetDetail.HOURS.name().equals(previous.arguments().get("detail"));
        // "When was it submitted?" / "per day?" about the person just shown: their newest record, or the same hours
        // day by day.
        TeamView view = personOrdinal(previous) == null || next != null || status != null ? TeamView.STANDARD
                : WaviContinuation.asksSubmissionTime(normalized) ? TeamView.LATEST_BY_PERIOD
                : hoursBefore && before != null && WaviContinuation.asksDaily(normalized) ? TeamView.DAILY : TeamView.STANDARD;
        if (next == null && view == TeamView.STANDARD && !total && (status == null || WaviContinuation.asksCount(normalized)
                || WaviContinuation.isHowTo(normalized))) return null;
        Integer ordinal = null;
        if (personOrdinal(previous) != null) {
            PersonResolution stored = storedPerson(teamScope(actor), previous);
            if (stored.problem() != null) return stored.problem();
            ordinal = stored.person().ordinal();
        }
        Object previousStatus = previous.arguments().get("status");
        log.info("{} provider=none continuation=true tool={} follow-up={}", PROVIDER_USED_MARKER,
                WaviTools.GET_TEAM_TIMESHEETS, next != null ? "period" : "status");
        return teamTimesheetsResponse(actor, new WaviTeamTimesheetQuery(
                status != null ? status : previousStatus == null ? WaviTools.TimesheetStatusFilter.ANY.name()
                        : previousStatus.toString(),
                next != null ? next : before, hoursBefore, ordinal),
                null, turn, false, view);
    }

    /** How a team-timesheet answer is laid out: the list or period totals, one newest record, or hours per day. */
    enum TeamView { STANDARD, LATEST_BY_PERIOD, LATEST_BY_SUBMISSION, DAILY }

    /** Stored-state key: a team answer selected one record from its result set (a {@link TeamView} name). */
    static final String TEAM_VIEW = "view";

    /** The layout a team-timesheet question asks for, from its own words. */
    private static TeamView teamViewOf(String normalized, WaviTeamTimesheetQuery query) {
        if (normalized == null) return TeamView.STANDARD;
        if (query.hours() && query.range() != null && WaviContinuation.asksDaily(normalized)) return TeamView.DAILY;
        if (query.range() == null && !query.hours() && WaviContinuation.asksLatest(normalized)) {
            List<String> tokens = List.of(normalized.split("[^a-z]+"));
            return tokens.stream().anyMatch(token -> token.startsWith("submi"))
                    ? TeamView.LATEST_BY_SUBMISSION : TeamView.LATEST_BY_PERIOD;
        }
        return TeamView.STANDARD;
    }

    /** People in {@code people} whose FULL name appears in the message. */
    private static List<com.waypoint.dto.myhr.EmployeeSummaryResponse> fullyNamed(String message,
            List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people) {
        if (message == null) return List.of();
        String text = " " + message.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("'s\\b", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> found = new ArrayList<>();
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people) {
            if (person.name() == null || person.name().isBlank()) continue;
            String name = person.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
            if (name.contains(" ") && text.contains(" " + name + " ")) found.add(person);
        }
        return found;
    }

    /** The user's own timesheet just before their most recent one (by work period, then submission time). */
    private MyHrChatResponse earlierOwnTimesheetResponse(VerifiedMyHrActor actor) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && timesheet.periodStartDate() != null && timesheet.periodEndDate() != null)
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate).reversed()
                        .thenComparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        log.info("{} provider=none own-earlier-timesheet found={}", PROVIDER_USED_MARKER, own.size() > 1);
        if (own.size() < 2) {
            return new MyHrChatResponse("I don't see an earlier timesheet of yours before that one.").withActions(actions);
        }
        com.waypoint.dto.myhr.TimesheetResponse earlier = own.get(1);
        boolean samePeriod = earlier.periodStartDate().equals(own.get(0).periodStartDate())
                && earlier.periodEndDate().equals(own.get(0).periodEndDate());
        return new MyHrChatResponse("The timesheet before that one" + (samePeriod
                ? " is an earlier submission for the same period, " : " covers ")
                + periodText(earlier.periodStartDate(), earlier.periodEndDate()) + ": it "
                + periodStatusPhrase(earlier.status())
                + (earlier.submittedAt() == null ? "" : " (" + submittedText(earlier.submittedAt()) + ")")
                + (earlier.totalHours() == null ? "." : ", " + hoursText(earlier.totalHours()) + " recorded."))
                .withActions(actions);
    }

    /** Who reviews the user's own timesheets: the reporting manager on their own profile. */
    private MyHrChatResponse ownTimesheetReviewerResponse(VerifiedMyHrActor actor) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        String manager = null;
        try {
            MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(actor.userId());
            if (profile != null && profile.reportingManager() != null && !isBlank(profile.reportingManager().name())) {
                manager = profile.reportingManager().name();
            }
        } catch (UnauthorizedException | ForbiddenException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            log.warn("MyHR chat could not read the reporting manager for a reviewer answer ({})",
                    unavailable.getClass().getSimpleName());
        }
        log.info("{} provider=none own-timesheet-reviewer known={}", PROVIDER_USED_MARKER, manager != null);
        return new MyHrChatResponse((manager == null
                ? "Your reporting manager reviews the timesheets you submit, but I don't see one recorded on your profile; "
                + "please check with HR."
                : "Your reporting manager, " + displayName(manager) + ", reviews the timesheets you submit.")
                + " Submitted Timesheets on the Timesheets page shows the status of each one.").withActions(actions);
    }

    /**
     * Who in the reviewer's scope has no submitted timesheet covering a period (this week when none is named). Built
     * from the same scoped people and timesheets the review pages show. Drafts are invisible to reviewers, so a
     * person listed may have one in progress; a rejected submission is named as waiting for the employee.
     */
    MyHrChatResponse missingSubmissionsResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange asked,
                                                Turn turn) {
        if (!isReviewer(actor)) return new MyHrChatResponse(TIMESHEET_QUEUE_REVIEWERS_ONLY);
        LocalDate today = LocalDate.now(clock);
        MyHrConversationIntents.TimesheetRange range = asked != null ? asked
                : MyHrConversationIntents.periodIn("this week", today);
        boolean hr = actor.role() == UserRole.HR;
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        java.util.Set<String> reports = hr ? activeDirectReportIds(actor) : null;
        java.util.Set<String> covered = new java.util.HashSet<>();
        java.util.Set<String> rejectedOnly = new java.util.HashSet<>();
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
            if (timesheet == null || timesheet.status() == null || actor.userId().equals(timesheet.employeeUserId())
                    || timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT
                    || !overlaps(timesheet, range.from(), range.to())) continue;
            if (hr && (!actor.userId().equals(timesheet.reportingManagerUserId())
                    || !reports.contains(timesheet.employeeUserId()))) continue;
            if (timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED) rejectedOnly.add(timesheet.employeeUserId());
            else covered.add(timesheet.employeeUserId());
        }
        rejectedOnly.removeAll(covered);
        log.info("{} provider=none missing-submissions people={} period={}", PROVIDER_USED_MARKER, people.size(), range.unit());
        if (turn != null) turn.answeredWith(WaviTools.GET_TEAM_TIMESHEETS,
                teamTimesheetState(WaviTools.TimesheetStatusFilter.ANY.name(), range, false, null));
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        String scope = hr ? "your active direct reports" : "the HR and Employee accounts in your organization";
        String when = range.label() + (range.singleDay() || range.unit() == MyHrConversationIntents.TimesheetRange.Unit.MONTH
                ? "" : " (" + formatDay(range.from()) + " to " + formatDay(range.to()) + ")");
        if (people.isEmpty()) {
            return new MyHrChatResponse(hr ? "No one currently reports to you in Waypoint."
                    : "I don't see any HR or Employee accounts in your organization yet.").withActions(actions);
        }
        List<String> missing = new ArrayList<>();
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people) {
            if (covered.contains(person.userId())) continue;
            missing.add(displayName(person.name()) + (rejectedOnly.contains(person.userId())
                    ? " (submission rejected; waiting for them to correct and resubmit)" : ""));
        }
        if (missing.isEmpty()) {
            return new MyHrChatResponse("Everyone among " + scope + " (" + people.size() + ") has a submitted timesheet "
                    + "covering " + when + ".").withActions(actions);
        }
        int shown = Math.min(missing.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder().append(missing.size()).append(" of ").append(people.size())
                .append(" among ").append(scope).append(missing.size() == 1 ? " has" : " have")
                .append(" no submitted timesheet covering ").append(when).append(':');
        for (String name : missing.subList(0, shown)) reply.append("\n- ").append(name);
        if (missing.size() > shown) reply.append("\n...and ").append(missing.size() - shown).append(" more.");
        reply.append("\nDrafts are not visible to reviewers, so someone listed may have a timesheet in progress.");
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** Whether one of the established queue-count routes recognises this wording and will answer it. */
    private boolean hasQueueCountRoute(String message) {
        if (classifyTimesheetQueueQuestion(message) != null || classifyProfileChangeQueueQuestion(message) != null
                || classifyDocumentRequestQueueQuestion(message) != null) return true;
        MyHrOwnRecordIntent.Intent typed = MyHrOwnRecordIntent.classify(message);
        return typed != null && typed.kind() == MyHrOwnRecordIntent.Kind.QUEUE_COUNT;
    }

    /** The clarification and refusal replies a composite half may not consist of (they answer nothing). */
    private static boolean isClarification(MyHrChatResponse response) {
        String text = response == null ? "" : response.message();
        return text.startsWith("Which one do you mean") || text.startsWith("Which person") || text.startsWith("Do you mean")
                || text.startsWith("Hours for which period") || text.startsWith("Whose hours") || text.startsWith("I'm not sure which");
    }

    /**
     * A message made of two questions joined by "and": both halves answered, each by the same deterministic routes
     * a message of its own would get, with the same verified actor. Only when BOTH halves have a deterministic
     * answer of their own (never a model) and the halves differ; a single question that merely contains "and"
     * ("between Oct 5 and Oct 18", "approved and when") has no such second half and is left whole.
     */
    private MyHrChatResponse compositeResponse(String message, String authenticatedUserId, VerifiedMyHrActor actor, Turn turn) {
        if (message == null) return null;
        String[] halves = WaviContinuation.splitConjunction(message);
        if (halves == null) return null;
        String first = halves[0];
        String second = halves[1];
        if (WaviContinuation.words(normalizeQuestion(first)) < 2 || WaviContinuation.words(normalizeQuestion(second)) < 2) return null;
        // A how-to, policy, writing or hypothetical question is one question however many "and"s it has.
        if (MyHrScopeGuard.isWritingTask(message) || WaviContinuation.isConditional(normalizeQuestion(message))
                || WaviContinuation.isHowTo(normalizeQuestion(message)) || MyHrKnowledgeGrounding.isPolicyQuestion(message)
                || classifyWorkflowIntent(message) != null) return null;
        Turn childA = turn.restrictedChild();
        MyHrChatResponse a = localAnswer(first, authenticatedUserId, actor, childA);
        if (a == null || isClarification(a)) return null;
        Turn childB = turn.restrictedChild();
        MyHrChatResponse b = localAnswer(second, authenticatedUserId, actor, childB);
        // "What is my role and my job title": a second half that is only a noun phrase borrows the first half's
        // question frame ("what is"), so it is asked as a question of its own.
        String framed = WaviContinuation.borrowQuestionFrame(first, second);
        if ((b == null || isClarification(b)) && framed != null) {
            childB = turn.restrictedChild();
            b = localAnswer(framed, authenticatedUserId, actor, childB);
        }
        if (b == null || isClarification(b) || a.message().equals(b.message())) return null;
        log.info("{} provider=none composite=2", PROVIDER_USED_MARKER);
        List<String> actions = new ArrayList<>(a.actions() == null ? List.of() : a.actions());
        for (String action : b.actions() == null ? List.<String>of() : b.actions()) if (!actions.contains(action)) actions.add(action);
        return new MyHrChatResponse(a.message() + "\n\n" + b.message()).withActions(actions);
    }

    /** One half of a composite question, through the deterministic routes only. */
    private MyHrChatResponse localAnswer(String part, String authenticatedUserId, VerifiedMyHrActor actor, Turn turn) {
        MyHrChatResponse referenced = referenceResponse(part, actor, turn);
        if (referenced != null) return referenced;
        MyHrChatResponse deterministic = deterministicResponse(part, authenticatedUserId, actor, true, turn, RouteTier.ALL);
        if (deterministic != null) return deterministic;
        return namedPersonResponse(part, actor, turn);
    }

    /** Whether a message asks about a personal record (hours, timesheets, requests, profile fields, status). */
    private boolean recordQuestion(String normalized, String message) {
        return WaviContinuation.asksForHours(normalized) || WaviContinuation.workArea(normalized) != null
                || WaviContinuation.asksPending(normalized) || WaviContinuation.asksSubmissionTime(normalized)
                || WaviContinuation.timesheetStatusFilter(normalized) != null
                || !WaviTools.TeamField.SUMMARY.name().equals(teamFieldIn(message))
                || List.of(normalized.split("[^a-z]+")).contains("status");
    }

    /**
     * "And the week before?", "what about october?" after an answer about the user's own timesheets for a period:
     * the next period is computed from the STORED period (not from re-reading earlier text), and the same detail
     * (status, period hours or daily hours) is answered for it.
     */
    private MyHrChatResponse previousScopeResponse(WaviDialogueStore.State previous, String normalized, Turn turn) {
        if (previous == null || !(WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                || WaviTools.LIST_MY_TIMESHEETS.equals(previous.tool()))
                || !WaviContinuation.asksPreviousScope(normalized)) return null;
        MyHrConversationIntents.TimesheetRange before = periodOf(previous);
        if (before == null) return null;
        log.info("{} provider=none continuation=true follow-up=previous-scope", PROVIDER_USED_MARKER);
        turn.keepPreviousState = true;
        return new MyHrChatResponse(before.singleDay() ? "The selected date is " + before.from() + "."
                : "The selected period is " + before.from() + " to " + before.to() + ".");
    }

    private MyHrChatResponse ownPeriodFollowUp(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                               String normalized, Turn turn) {
        // A list of the user's own timesheets for a period ("which one was approved?") keeps that period too, so the
        // counting explanation below can follow it; every other period follow-up stays with the lookup it continues.
        boolean afterList = previous != null && WaviTools.LIST_MY_TIMESHEETS.equals(previous.tool());
        if (previous == null || !(WaviTools.GET_MY_TIMESHEETS.equals(previous.tool()) || afterList)
                || WaviTimesheetPeriod.Period.LATEST.name().equals(previous.arguments().get("period"))) return null;
        MyHrConversationIntents.TimesheetRange before = periodOf(previous);
        if (before == null) return null;
        // "Why were these hours counted once?" after an hours answer: how they were counted, for the same period.
        boolean explainsCounting = WaviContinuation.asksWhy(normalized) && WaviContinuation.words(normalized) <= 10;
        if (afterList && !(explainsCounting && WaviContinuation.asksForHours(normalized))) return null;
        if (explainsCounting
                && (WaviTimesheetPeriod.Detail.HOURS.name().equals(previous.arguments().get("detail"))
                    || WaviContinuation.asksForHours(normalized))) {
            log.info("{} provider=none continuation=true follow-up=hours-explanation", PROVIDER_USED_MARKER);
            turn.keepPreviousState = true;
            return new MyHrChatResponse(hoursCountingExplanation(actor, before));
        }
        LocalDate today = LocalDate.now(clock);
        MyHrConversationIntents.TimesheetRange asked = MyHrConversationIntents.followUpRange(normalized, today, before);
        // "Per day?" / "day by day?" after an hours answer: the same period, broken down by date.
        boolean dailyOnly = asked == null && WaviContinuation.asksDaily(normalized)
                && WaviContinuation.words(normalized) <= 4 && !WaviContinuation.isHowTo(normalized);
        // "And the total for October?" / "what's the total?": hours, for the named period or else the same one.
        boolean total = asked == null && !dailyOnly && WaviContinuation.words(normalized) <= 7
                && asksUnitlessTotal(normalized, normalized) && !WaviContinuation.refersToPerson(normalized)
                && !WaviContinuation.namesGroup(normalized);
        MyHrConversationIntents.TimesheetRange totalPeriod = total ? MyHrConversationIntents.periodIn(normalized, today) : null;
        MyHrConversationIntents.TimesheetRange next = dailyOnly ? before : total
                ? (totalPeriod != null ? totalPeriod : before) : asked;
        // "How many were approved?" / "and submitted?" after an hours answer: the same period, one status's hours.
        HoursMetric metric = hoursMetricOf(normalized);
        if (next == null && metric != HoursMetric.ALL && WaviContinuation.words(normalized) <= 6
                && WaviTimesheetPeriod.Detail.HOURS.name().equals(previous.arguments().get("detail"))
                && !WaviContinuation.isHowTo(normalized) && !WaviContinuation.namesThirdParty(normalized)) {
            log.info("{} provider=none continuation=true tool={} follow-up=hours-metric", PROVIDER_USED_MARKER, WaviTools.GET_MY_TIMESHEETS);
            turn.keepPreviousState = true;
            return timesheetHoursResponse(actor, before, metric);
        }
        if (next == null) return null;
        Object detail = dailyOnly ? WaviTimesheetPeriod.Detail.DAILY_HOURS.name()
                : total ? WaviTimesheetPeriod.Detail.HOURS.name() : previous.arguments().get("detail");
        Map<String, Object> period = WaviTimesheetPeriod.arguments(next, today);
        if (period == null) return null;
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(period);
        if (detail != null) arguments.put("detail", detail);
        log.info("{} provider=none continuation=true tool={} period-follow-up=state", PROVIDER_USED_MARKER,
                WaviTools.GET_MY_TIMESHEETS);
        Supplier<MyHrChatResponse> existing = WaviTimesheetPeriod.Detail.HOURS.name().equals(detail)
                ? () -> timesheetHoursResponse(actor, next)
                : WaviTimesheetPeriod.Detail.DAILY_HOURS.name().equals(detail)
                ? () -> new ToolAnswers().dailyTimesheetHours(actor, next)
                : () -> timesheetRangeResponse(actor, next);
        // Through the tool layer like every deterministic answer; the reference date stays the original question's.
        MyHrChatResponse answer = viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP,
                WaviTools.GET_MY_TIMESHEETS, arguments, existing);
        turn.continuedWith(WaviTools.GET_MY_TIMESHEETS, arguments, previous);
        return answer;
    }

    /**
     * A question for the user's OWN records as a list ("which of my timesheets ...", "list my document requests"):
     * the list, filtered by any status it names, never only the latest record and never "I can only check the most
     * recent one". Questions about a period or hours, and review-role questions, keep their own routes.
     */
    private MyHrChatResponse ownListResponse(VerifiedMyHrActor actor, String normalized, List<String> tokens, Turn turn) {
        // "What timesheets have I approved?": the user as the REVIEWER who decided (I approved), not their own
        // records' status (my approved timesheets). Only reviewers decide anything.
        String decided = WaviContinuation.decisionByUser(normalized);
        if (decided != null && isReviewer(actor) && !tokens.contains("my")) {
            String queue = tokens.contains("profile") ? "PROFILE_CHANGES" : "TIMESHEETS";
            turn.answeredWith(WaviTools.GET_MY_REVIEW_HISTORY, Map.of("queue", queue, "decision", decided));
            return reviewHistoryResponse(actor, queue, decided);
        }
        // The user's own request in any form - "my salary certificate request", "a profile change request open?" -
        // asked as a status or existence question: their requests of that kind, with each one's status and date.
        MyHrChatResponse ownRequest = ownRequestStatusResponse(actor, normalized, tokens, turn);
        if (ownRequest != null) return ownRequest;
        boolean owner = tokens.contains("my") || tokens.contains("i") || tokens.contains("mine");
        // An Employee can only see their own records, so "show timesheets" needs no "my"; a count of the user's own
        // records ("how many timesheets have I submitted?") is answered with the list, which states the number.
        boolean ownByRole = actor.role() == UserRole.EMPLOYEE && !WaviContinuation.namesGroup(normalized)
                && !WaviContinuation.refersToPerson(normalized);
        boolean ownCount = owner && WaviContinuation.asksCount(normalized) && !hasQueueCountRoute(normalized);
        MyHrConversationIntents.TimesheetRange period = MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock));
        // A list question that points back ("the timesheets for that date", "which one was approved?") right after an
        // answer about the user's own timesheets for a period: the records of that same period.
        WaviDialogueStore.State previous = turn.previousState;
        boolean pointsBack = period == null && previous != null && WaviTools.GET_MY_TIMESHEETS.equals(previous.tool())
                && WaviContinuation.asksForRecords(normalized) && WaviContinuation.words(normalized) <= 10
                && tokens.stream().anyMatch(List.of("that", "those", "these", "them", "one", "ones", "same")::contains)
                && !WaviContinuation.namesGroup(normalized) && !WaviContinuation.refersToPerson(normalized);
        if (pointsBack) period = periodOf(previous);
        // "My submissions" are timesheets: the only records a person submits in Waypoint.
        String draftFilter = WaviContinuation.timesheetStatusFilter(normalized);
        boolean draftsOnly = WaviTools.TimesheetStatusFilter.DRAFT.name().equals(draftFilter)
                && WaviContinuation.namedKind(normalized) == null;
        // Drafts exist only for timesheets in Waypoint, so "do I have any drafts?" needs no record noun.
        boolean ownTimesheets = tokens.contains("timesheets") || draftsOnly || (pointsBack && period != null)
                || (owner && WaviContinuation.namedKind(normalized) == null
                && (tokens.contains("submissions") || tokens.contains("submission")));
        // The records asked for as a list: "show/list/which ...", "what timesheets ...", a bare noun phrase ("my
        // timesheets for October", "all my submissions last month"), or whether any exist in one status ("do I have
        // any rejected timesheets?"). A status, yes/no or hours question about a period ("is my timesheet for
        // October approved?", "status of my timesheets in October") is not a list.
        boolean listWording = WaviContinuation.asksForRecords(normalized)
                || (ownTimesheets && WaviContinuation.asksWhatRecords(normalized))
                || ((ownTimesheets || tokens.contains("requests")) && owner && WaviContinuation.isBareRecordPhrase(normalized))
                || (ownTimesheets && WaviContinuation.asksExistence(normalized) && draftFilter != null
                    && !tokens.contains("status"));
        if (!(listWording || ownCount)
                // The previous answer was about the user's own timesheets, so "that date" is theirs too.
                || !(owner || ownByRole || pointsBack)
                || tokens.stream().anyMatch(List.of("approval", "approve", "review", "reviews", "team", "reports")::contains)
                || WaviContinuation.asksForHours(normalized)
                // With a period, only the user's timesheets as records (a list or a count) are answered here; a status
                // question for a period keeps its own route.
                || (period != null && (!ownTimesheets || tokens.contains("status")))) return null;
        if (ownTimesheets) {
            String named = WaviContinuation.timesheetStatusFilter(normalized);
            String status = named != null ? named : WaviContinuation.asksPending(normalized)
                    ? WaviTools.TimesheetStatusFilter.SUBMITTED.name() : WaviTools.TimesheetStatusFilter.ANY.name();
            turn.answeredWith(WaviTools.LIST_MY_TIMESHEETS, ownListState(status, period));
            return myTimesheetListResponse(actor, status, period);
        }
        if (tokens.contains("requests")) {
            String kind = tokens.contains("profile") ? WaviTools.RequestListKind.PROFILE_CHANGE.name()
                    : tokens.stream().anyMatch(token -> token.startsWith("document"))
                    ? WaviTools.RequestListKind.DOCUMENT_REQUEST.name() : null;
            if (kind == null) return null;
            String status = tokens.contains("rejected") ? WaviTools.RequestStatusFilter.REJECTED.name()
                    : tokens.contains("approved") || tokens.contains("completed") || tokens.contains("fulfilled")
                    ? WaviTools.RequestStatusFilter.COMPLETED.name()
                    : WaviContinuation.asksPending(normalized) || tokens.contains("open")
                    ? WaviTools.RequestStatusFilter.OPEN.name() : WaviTools.RequestStatusFilter.ANY.name();
            turn.answeredWith(WaviTools.LIST_MY_REQUESTS, Map.of("kind", kind, "status", status));
            return myRequestListResponse(actor, kind, status);
        }
        return null;
    }

    /** The person a stored state selected, re-resolved in the CURRENT scope and pinned by its reference. */
    private PersonResolution storedPerson(List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people,
                                          WaviDialogueStore.State previous) {
        Object reference = previous.arguments().get(PERSON_REFERENCE);
        return resolvePerson(people, null, personOrdinal(previous), reference == null ? null : reference.toString());
    }

    /** A follow-up that names no period of its own keeps the period of the previous team-timesheet question. */
    private WaviTeamTimesheetQuery inheritedPeriod(WaviTeamTimesheetQuery asked, WaviDialogueStore.State previous) {
        if (asked.range() != null || previous == null || !WaviTools.GET_TEAM_TIMESHEETS.equals(previous.tool())) {
            return asked;
        }
        return new WaviTeamTimesheetQuery(asked.status(), periodOf(previous), asked.hours(), asked.ordinal());
    }

    private static WaviTools.WorkArea areaOf(WaviDialogueStore.State state) {
        Object area = state.arguments().get("area");
        for (WaviTools.WorkArea value : WaviTools.WorkArea.values()) {
            if (value.name().equals(area)) return value;
        }
        return null;
    }

    /**
     * "When did I submit it?" right after "your most recent timesheet": the SAME record that answer showed (latest work
     * period, then latest submission, the service's own selection rule), never the latest submission of another
     * period. When the most recent submission overall is a different record, that is said separately.
     */
    private MyHrChatResponse referencedLatestSubmission(VerifiedMyHrActor actor, Turn turn,
                                                        WaviDialogueStore.State previous) {
        if (actor.role() == UserRole.COMPANY_ADMIN) return null;
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && timesheet.periodStartDate() != null)
                .toList();
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        turn.continuedWith(WaviTools.GET_MY_TIMESHEETS, previous.arguments(), previous);
        Optional<com.waypoint.dto.myhr.TimesheetResponse> shown = own.stream().max(java.util.Comparator
                .comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate)
                .thenComparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.<LocalDateTime>naturalOrder())));
        if (shown.isEmpty()) return new MyHrChatResponse("I couldn't find a timesheet of yours in Waypoint.").withActions(actions);
        com.waypoint.dto.myhr.TimesheetResponse record = shown.get();
        String period = periodText(record.periodStartDate(), record.periodEndDate());
        StringBuilder answer = new StringBuilder("That timesheet (").append(period).append(") ");
        if (record.submittedAt() == null || record.status() == com.waypoint.enums.TimesheetStatus.DRAFT) {
            answer.append(record.status() == com.waypoint.enums.TimesheetStatus.DRAFT
                    ? "is still a draft, so it hasn't been submitted." : "has no submission time recorded.");
        } else {
            answer.append("was ").append(submittedText(record.submittedAt())).append('.');
        }
        own.stream().filter(timesheet -> timesheet.submittedAt() != null
                        && timesheet.status() != com.waypoint.enums.TimesheetStatus.DRAFT)
                .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt))
                .filter(latest -> latest != record)
                .ifPresent(latest -> answer.append(" Your most recent submission overall was a different timesheet (")
                        .append(periodText(latest.periodStartDate(), latest.periodEndDate())).append("), ")
                        .append(submittedText(latest.submittedAt())).append('.'));
        return new MyHrChatResponse(answer.toString()).withActions(actions);
    }

    private static String periodStatusPhrase(com.waypoint.enums.TimesheetStatus status) {
        if (status == null) return "has no recorded status";
        return switch (status) {
            case DRAFT -> "is saved as a draft and has not been submitted yet";
            case SUBMITTED -> "is submitted and waiting for approval";
            case APPROVED -> "is approved";
            case REJECTED -> "was rejected and needs to be corrected and resubmitted";
        };
    }

    private String formatDay(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US));
    }

    /**
     * HR: their own active direct reports, from the same lookup the Employees page uses for HR. Company Admin: that
     * lookup returns the whole organization rather than the admin's own reports, so Wavi says what it cannot do
     * instead of guessing. Employees have no direct reports in Waypoint.
     */
    private String directReportsReply(VerifiedMyHrActor actor) {
        String withoutLookup = directReportsOutsideRoleReply(actor.role());
        if (withoutLookup != null) return withoutLookup;
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> reports = employeeManagementService.list(actor.userId());
        List<String> names = (reports == null ? List.<com.waypoint.dto.myhr.EmployeeSummaryResponse>of() : reports)
                .stream().filter(java.util.Objects::nonNull)
                .map(report -> (report.name() == null || report.name().isBlank() ? "an unnamed employee" : report.name())
                        + (report.designation() == null || report.designation().isBlank()
                        ? "" : " (" + report.designation() + ")"))
                .toList();
        if (names.isEmpty()) {
            return "No one currently reports to you in Waypoint.";
        }
        int shown = Math.min(names.size(), 10);
        String list = String.join(", ", names.subList(0, shown));
        return (names.size() == 1 ? "1 person reports to you: " : names.size() + " people report to you: ") + list
                + (names.size() > shown ? ", and " + (names.size() - shown) + " more. Open Employees to see everyone." : ".");
    }

    /** The reply for a role without a direct-reports lookup (all but HR); null for HR. Never reads a record. */
    private static String directReportsOutsideRoleReply(UserRole role) {
        if (role == UserRole.EMPLOYEE) {
            return "Employee accounts don't have direct reports in Waypoint. To see who you report to, ask "
                    + "\"Who is my reporting manager?\"";
        }
        if (role != UserRole.HR) {
            return "I can't list who reports directly to you: Waypoint doesn't offer a direct-reports lookup for "
                    + "Company Admin accounts yet. Open Employees to see each person's reporting manager.";
        }
        return null;
    }

    /** Tests only: a fixed "today". */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Which deterministic routes one pass of {@link #deterministicResponse} may answer with.
     *
     * <p>PRECISE routes match the whole message against an anchored form (or a structured period/term parse), so
     * they are stable and answer before any model is asked. BROAD routes classify by keywords, subject/aspect
     * co-occurrence or a heuristic rewrite: useful defaults, but they also claim policy questions, how-to questions
     * and follow-ups that only resemble them. When semantic routing is on, BROAD routes answer only after the router
     * abstained, failed or was unavailable (see {@link #respondInternal}); otherwise ALL routes run in their
     * established order, exactly as before tiers existed.
     */
    enum RouteTier {
        ALL, PRECISE, BROAD;

        boolean precise() {
            return this != BROAD;
        }

        boolean broad() {
            return this != PRECISE;
        }
    }

    /**
     * The exact and classified Waypoint routes, in their established order; null when none of them answers.
     * Small talk is answered only for what the user typed, never for an interpreted rewrite of it.
     */
    private MyHrChatResponse deterministicResponse(String message, String authenticatedUserId, VerifiedMyHrActor actor,
                                                    boolean smallTalkAllowed, Turn turn) {
        return deterministicResponse(message, authenticatedUserId, actor, smallTalkAllowed, turn, RouteTier.ALL);
    }

    private MyHrChatResponse deterministicResponse(String message, String authenticatedUserId, VerifiedMyHrActor actor,
                                                    boolean smallTalkAllowed, Turn turn, RouteTier tier) {
        // Questions answered only from the verified actor and its own authorized records, before any classifier
        // below that would otherwise claim them: opening a page, the user's own role, who reports to them, timesheet
        // upload problems and the status of their own timesheet for a named week or month.
        MyHrChatResponse conversational = actor == null || !tier.precise() ? null
                : conversationalIntentResponse(message, actor, turn);
        if (conversational != null) return conversational;
        // Work-field questions about a named person are resolved only from the reviewer-visible employee list.
        // This covers both possessive wording ("a person's designation") and object wording ("job title of a person")
        // without letting a provider infer a person or reach outside the existing employee-management scope.
        // Bounded like every people lookup: only a message with a name-shaped token (a capitalised name or an
        // employee ID) and not explicitly out of scope reads the reviewer's people list.
        if (actor != null && tier.precise() && isReviewer(actor) && WaviContinuation.hasNameShapedToken(message)
                && !MyHrScopeGuard.isExplicitlyOutOfScope(message)) {
            String field = teamFieldIn(message);
            if (!WaviTools.TeamField.SUMMARY.name().equals(field)) {
                MyHrChatResponse teamMember = teamMemberResponse(actor, field, null, message, turn, true);
                if (teamMember != null) return teamMember;
            }
        }
        // History navigation wins over the generic own-timesheet classifier below. It opens the existing page and
        // does not inspect a timesheet record. Keyword-based, so BROAD.
        WorkflowIntent timesheetHistory = actor == null || !tier.broad() ? null
                : classifyTimesheetHistoryNavigation(normalizeQuestion(message));
        if (timesheetHistory != null) {
            return new MyHrChatResponse(workflowGuidance(timesheetHistory, actor.role()));
        }
        if (!tier.precise()) {
            return broadDeterministicResponse(message, actor, turn);
        }
        // Own-record, pending, another-person and queue-count questions, by subject and aspect rather than exact
        // wording - including natural or misspelled wording of them - so a status question about the user's own
        // record is never answered with how-to guidance. The exact routes below answer first, so their established
        // answers are unchanged.
        MyHrOwnRecordIntent.Intent ownRecordIntent = actor == null ? null : ownRecordIntent(message);
        if (authenticatedUserId != null && isRecentTimesheetStatusQuestion(message)) {
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS,
                    Map.of("period", WaviTimesheetPeriod.Period.LATEST.name()), () -> {
                        // Company Admin accounts never submit timesheets: say so, rather than "No timesheet found."
                        if (actor != null && actor.role() == UserRole.COMPANY_ADMIN) {
                            return new MyHrChatResponse(ADMIN_NO_OWN_TIMESHEET_STATUS);
                        }
                        Optional<MyHrRecentTimesheetStatus> recentStatus =
                                timesheetService.getMostRecentStatusForUser(authenticatedUserId);
                        return new MyHrChatResponse(formatRecentTimesheetStatus(recentStatus));
                    });
        }
        if (authenticatedUserId != null && TIMESHEET_SUBMISSION_DATE_QUESTION.matcher(normalizeQuestion(message)).matches()) {
            if (actor != null && actor.role() == UserRole.COMPANY_ADMIN) {
                return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS);
            }
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS,
                    Map.of("period", "LATEST", "detail", "SUBMISSION_DATE"),
                    () -> new MyHrChatResponse(formatLatestTimesheetSubmission(
                            timesheetService.getLatestSubmissionForUser(authenticatedUserId))));
        }
        PhoneNumberChangeQuestion phoneNumberChangeQuestion = classifyPhoneNumberChangeQuestion(message);
        if (authenticatedUserId != null && phoneNumberChangeQuestion != null) {
            Optional<MyHrOwnProfileChangeHistory> historyForField =
                    profileChangeRequestService.mostRecentOwnHistoryForField(
                            authenticatedUserId, ProfileChangeField.PHONE_NUMBER);
            rememberOwnRequest(turn, new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.PROFILE_CHANGE, phoneChangeAspect(phoneNumberChangeQuestion),
                    ProfileChangeField.PHONE_NUMBER, null), actor);
            return new MyHrChatResponse(formatPhoneNumberChangeHistory(phoneNumberChangeQuestion, historyForField));
        }
        DocumentRequestQuestion documentRequestQuestion = classifyDocumentRequestQuestion(message);
        if (authenticatedUserId != null && actor != null && actor.role() == UserRole.EMPLOYEE
                && documentRequestQuestion != null && documentRequestService != null) {
            rememberOwnRequest(turn, documentRequestIntent(documentRequestQuestion), actor);
            return new MyHrChatResponse(formatDocumentRequestHistory(
                    documentRequestQuestion, documentRequestService.listOwnHistory(authenticatedUserId)));
        }
        // An Employee asking "Do I have any pending profile change requests?" means their own requests, so this is
        // answered before the reviewer-queue questions, which would refuse it.
        if (tier.broad()) {
            MyHrChatResponse pendingCheck = ownPendingCheckResponse(actor, turn, ownRecordIntent);
            if (pendingCheck != null) return pendingCheck;
        }
        // In a PRECISE-only pass the own-pending check above waits, so the reviewer-queue routes below must not claim
        // the same message with their "reviewers only" refusal; it reaches the router or the BROAD pass instead.
        boolean ownPendingWaiting = !tier.broad() && ownRecordIntent != null && actor.role() == UserRole.EMPLOYEE
                && ownRecordIntent.kind() == MyHrOwnRecordIntent.Kind.PENDING_CHECK;
        DocumentRequestQueueQuestion documentQueueQuestion = ownPendingWaiting ? null
                : classifyDocumentRequestQueueQuestion(message);
        if (actor != null && documentQueueQuestion != null) {
            MyHrChatResponse listed = reviewListIfAsked(message, actor, turn,
                    waitingQueue(documentQueueQuestion) ? WaviTools.ReviewQueue.DOCUMENT_REQUESTS : null);
            if (listed != null) return listed;
            String queueResponse = documentRequestQueueResponse(actor, authenticatedUserId, documentQueueQuestion);
            if (queueResponse != null) {
                rememberReviewQueue(turn, actor, documentQueueQuestion);
                return new MyHrChatResponse(queueResponse);
            }
        }
        ProfileChangeQueueQuestion profileChangeQueueQuestion = ownPendingWaiting ? null
                : classifyProfileChangeQueueQuestion(message);
        if (actor != null && profileChangeQueueQuestion != null) {
            MyHrChatResponse listed = reviewListIfAsked(message, actor, turn,
                    waitingQueue(profileChangeQueueQuestion) ? WaviTools.ReviewQueue.PROFILE_CHANGES : null);
            if (listed != null) return listed;
            String queueResponse = profileChangeQueueResponse(actor, authenticatedUserId, profileChangeQueueQuestion);
            if (queueResponse != null) {
                rememberReviewQueue(turn, actor, profileChangeQueueQuestion);
                return new MyHrChatResponse(queueResponse);
            }
        }
        TimesheetQueueQuestion timesheetQueueQuestion = ownPendingWaiting ? null : classifyTimesheetQueueQuestion(message);
        if (actor != null && timesheetQueueQuestion != null) {
            MyHrChatResponse listed = reviewListIfAsked(message, actor, turn,
                    waitingQueue(timesheetQueueQuestion) ? WaviTools.ReviewQueue.TIMESHEETS : null);
            if (listed != null) return listed;
            String queueResponse = timesheetQueueResponse(actor, authenticatedUserId, timesheetQueueQuestion);
            if (queueResponse != null) {
                rememberReviewQueue(turn, actor, timesheetQueueQuestion);
                return new MyHrChatResponse(queueResponse);
            }
        }
        if (authenticatedUserId != null && isProfileChangeStatusQuestion(message)) {
            Optional<ProfileChangeStatus> status =
                    profileChangeRequestService.mostRecentOwnStatus(authenticatedUserId);
            rememberOwnRequest(turn, new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.PROFILE_CHANGE, MyHrOwnRecordIntent.Aspect.STATUS, null, null), actor);
            return new MyHrChatResponse(formatProfileChangeStatus(status));
        }
        ProfileChangeDateQuestion profileChangeDateQuestion = classifyProfileChangeDateQuestion(message);
        if (authenticatedUserId != null && profileChangeDateQuestion != null) {
            rememberOwnRequest(turn, new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.PROFILE_CHANGE,
                    profileChangeDateQuestion == ProfileChangeDateQuestion.APPROVED_DATE
                            ? MyHrOwnRecordIntent.Aspect.APPROVED_DATE : MyHrOwnRecordIntent.Aspect.REQUESTED_DATE,
                    null, null), actor);
            return new MyHrChatResponse(formatProfileChangeDate(profileChangeDateQuestion,
                    profileChangeRequestService.mostRecentOwnHistory(authenticatedUserId)));
        }
        if (matchesLookup(OWN_HR_CONTACT_QUESTION, message)) {
            if (actor == null || authenticatedUserId == null) {
                throw new UnauthorizedException("Authentication is required to access your profile");
            }
            MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(authenticatedUserId);
            var manager = profile.reportingManager();
            if (manager == null || isBlank(manager.name())) {
                return new MyHrChatResponse("I couldn't find a named reporting-manager assignment in your profile. "
                        + "Ask your organization administrator to confirm your HR contact.");
            }
            return new MyHrChatResponse("Your reporting manager is " + manager.name() + ". "
                    + ("HR".equalsIgnoreCase(manager.roleLabel())
                    ? "Your profile identifies their role as HR."
                    : "Your profile doesn't identify a separate HR contact, so I won't assume they're your HR representative."));
        }
        ProfileQuestion profileQuestion = classifyProfileQuestion(message);
        if (profileQuestion != null) {
            if (authenticatedUserId == null) {
                throw new UnauthorizedException("Authentication is required to access your profile");
            }
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_PROFILE_FIELD,
                    Map.of("field", profileQuestion.name()), () -> {
                        MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(authenticatedUserId);
                        return new MyHrChatResponse(formatProfileAnswer(profile, profileQuestion));
                    });
        }
        // Only for a verified actor; no knowledge retrieval, embedding or AI provider call is made.
        String smallTalk = actor == null || !smallTalkAllowed ? null
                : smallTalkReply(message, actor.role(), actor.organizationId());
        if (smallTalk != null) {
            log.info("{} provider=none small-talk=true", PROVIDER_USED_MARKER);
            turn.permitExternalUse();
            turn.keepPreviousState = true;
            return new MyHrChatResponse(smallTalk);
        }
        if (!tier.broad()) return null;
        // Personal-record and queue questions the exact routes above did not match: answered from the same
        // authorized lookups, never by workflow how-to guidance or an AI provider.
        MyHrChatResponse ownRecord = ownRecordFallbackResponse(actor, turn, ownRecordIntent, message);
        if (ownRecord != null) return ownRecord;
        return workflowResponse(message, actor, turn);
    }

    /** BROAD only, in the same relative order the full chain uses. */
    private MyHrChatResponse broadDeterministicResponse(String message, VerifiedMyHrActor actor, Turn turn) {
        if (actor == null) return null;
        MyHrOwnRecordIntent.Intent ownRecordIntent = ownRecordIntent(message);
        MyHrChatResponse pendingCheck = ownPendingCheckResponse(actor, turn, ownRecordIntent);
        if (pendingCheck != null) return pendingCheck;
        MyHrChatResponse ownRecord = ownRecordFallbackResponse(actor, turn, ownRecordIntent, message);
        if (ownRecord != null) return ownRecord;
        return workflowResponse(message, actor, turn);
    }

    /** An Employee's "anything pending on my side?": their own open items (BROAD). */
    private MyHrChatResponse ownPendingCheckResponse(VerifiedMyHrActor actor, Turn turn,
                                                     MyHrOwnRecordIntent.Intent ownRecordIntent) {
        if (ownRecordIntent == null || ownRecordIntent.kind() != MyHrOwnRecordIntent.Kind.PENDING_CHECK
                || actor == null || actor.role() != UserRole.EMPLOYEE) {
            return null;
        }
        String answer = ownRecordResponse(actor, ownRecordIntent);
        return answer == null ? null : ownRecordReply(actor, turn, ownRecordIntent, answer);
    }

    /** An own-record question classified by subject and aspect rather than by its whole wording (BROAD). */
    private MyHrChatResponse ownRecordFallbackResponse(VerifiedMyHrActor actor, Turn turn,
                                                       MyHrOwnRecordIntent.Intent ownRecordIntent) {
        return ownRecordFallbackResponse(actor, turn, ownRecordIntent, null);
    }

    private MyHrChatResponse ownRecordFallbackResponse(VerifiedMyHrActor actor, Turn turn,
                                                       MyHrOwnRecordIntent.Intent ownRecordIntent, String message) {
        // "Who reviews my timesheet?": the user's reporting manager, from their own profile - not a status. BROAD:
        // with semantic routing on, the router is asked first.
        if (message != null && actor != null && WaviContinuation.asksWhoReviewsOwnTimesheets(normalizeQuestion(message))) {
            return ownTimesheetReviewerResponse(actor);
        }
        if (ownRecordIntent == null) return null;
        if (ownRecordIntent.kind() == MyHrOwnRecordIntent.Kind.OTHER_PERSON && message != null && isReviewer(actor)
                && WaviContinuation.hasNameShapedToken(message) && !MyHrScopeGuard.isExplicitlyOutOfScope(message)) {
            // HR and Company Admin may see people in their own scope: answer from that scope, never refuse it
            // blanket-wise, and never look beyond it.
            MyHrChatResponse inScope = ownRecordIntent.subject() == MyHrOwnRecordIntent.Subject.TIMESHEET
                    ? teamTimesheetsResponse(actor, WaviTools.TimesheetStatusFilter.ANY.name(), message, turn, true)
                    : ownRecordIntent.subject() == MyHrOwnRecordIntent.Subject.PERSONAL_RECORD
                    ? teamMemberResponse(actor, teamFieldIn(message), null, message, turn, true) : null;
            if (inScope != null) return inScope;
        }
        if (ownRecordIntent.kind() == MyHrOwnRecordIntent.Kind.QUEUE_COUNT && isReviewer(actor) && message != null) {
            // "Any profile change requests waiting for me?" is the CURRENT queue, not an unsupported history count:
            // only a count over a past period is unavailable.
            String normalized = normalizeQuestion(message);
            if (WaviContinuation.asksPending(normalized)
                    && MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)) == null) {
                String current = switch (ownRecordIntent.subject()) {
                    case TIMESHEET -> timesheetQueueResponse(actor, actor.userId(), TimesheetQueueQuestion.MY_QUEUE);
                    case PROFILE_CHANGE -> profileChangeQueueResponse(actor, actor.userId(), ProfileChangeQueueQuestion.MY_QUEUE);
                    default -> documentRequestQueueResponse(actor, actor.userId(), DocumentRequestQueueQuestion.MY_QUEUE);
                };
                if (current != null) {
                    // This answered the reviewer's queue, not an own request: that queue is the follow-up context.
                    WaviTools.ReviewQueue queue = switch (ownRecordIntent.subject()) {
                        case TIMESHEET -> WaviTools.ReviewQueue.TIMESHEETS;
                        case PROFILE_CHANGE -> WaviTools.ReviewQueue.PROFILE_CHANGES;
                        default -> WaviTools.ReviewQueue.DOCUMENT_REQUESTS;
                    };
                    log.info("{} provider=none own-record={} subject={} aspect={}", PROVIDER_USED_MARKER,
                            ownRecordIntent.kind(), ownRecordIntent.subject(), ownRecordIntent.aspect());
                    if (turn != null) turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name()));
                    return new MyHrChatResponse(current);
                }
            }
        }
        String answer = ownRecordResponse(actor, ownRecordIntent);
        return answer == null ? null : ownRecordReply(actor, turn, ownRecordIntent, answer);
    }

    /** Workflow how-to and "do it for me" requests, classified by keywords (BROAD). */
    private MyHrChatResponse workflowResponse(String message, VerifiedMyHrActor actor, Turn turn) {
        // A hypothetical policy question ("does my salary change if my timesheet gets rejected?") asks about a rule:
        // neither keyword how-to guidance, a "can't do that for you" reply nor a status lookup answers it.
        if (isHypotheticalPolicyQuestion(message)) return null;
        // How-to questions about Waypoint's own workflows, answered for the verified role, not from company policy.
        // "Write a reminder to my team about submitting timesheets" asks for a draft, not how to submit one.
        WorkflowIntent workflow = actor == null || MyHrScopeGuard.isWritingTask(message) ? null
                : classifyWorkflowIntent(message);
        String workflowGuidance = workflow == null ? null : workflowGuidance(workflow, actor.role());
        // Role-based how-to text is the same for everyone in the role; a personal record lookup is not.
        boolean personalised = false;
        if (workflow == WorkflowIntent.TIMESHEET_REJECTED && workflowGuidance != null
                && actor.role() != UserRole.COMPANY_ADMIN) {
            // What Wavi can check (the user's own most recent timesheet) before what needs the timesheet opened.
            workflowGuidance = formatRejectionStatusCheck(timesheetService.getMostRecentStatusForUser(actor.userId()))
                    + " " + workflowGuidance;
            personalised = true;
        }
        // The assistant cannot change records; say so, then add only the guidance this role is allowed to follow.
        if (actor != null && ASSISTANT_ACTION_REQUEST.matcher(normalizeQuestion(message)).find()) {
            log.info("{} provider=none action-request=true workflow={}", PROVIDER_USED_MARKER,
                    workflowGuidance == null ? "none" : workflow);
            turn.permitExternalUseIf(!personalised);
            return new MyHrChatResponse(workflowGuidance == null
                    ? ACTION_REQUEST_REPLY : ACTION_REQUEST_REPLY + " " + workflowGuidance);
        }
        if (workflowGuidance != null) {
            log.info("{} provider=none workflow={}", PROVIDER_USED_MARKER, workflow);
            turn.permitExternalUseIf(!personalised);
            String guidance = workflowGuidance;
            // A personalised answer includes a record lookup, so it keeps the existing route.
            return personalised ? new MyHrChatResponse(guidance)
                    : viaTools(actor, turn, WaviRouterDecision.Intent.WORKFLOW_HELP, WaviTools.WORKFLOW_HELP,
                    Map.of("topic", workflow.name()), () -> new MyHrChatResponse(guidance));
        }
        return null;
    }

    private MyHrChatResponse respondInternal(String message, Conversation conversation,
                                             String authenticatedUserId, VerifiedMyHrActor actor, Turn turn) {
        // Exact app greetings/capability questions must not be reinterpreted by a contextual model decision.
        // They preserve the verified selection, including its original relative-date anchor.
        String smallTalk = actor == null ? null : smallTalkReply(message, actor.role(), actor.organizationId());
        if (smallTalk != null) {
            log.info("{} provider=none small-talk=true", PROVIDER_USED_MARKER);
            turn.keepPreviousState = true;
            turn.permitExternalUse();
            return turn.routedBy(RouteStage.PRECISE, new MyHrChatResponse(smallTalk));
        }
        // AI-first: with the agent enabled for an authorized organization, Claude reads the question next, before any
        // keyword route. Everything below is its fallback and is unchanged.
        MyHrChatResponse agent = agentResponse(message, conversation, actor, turn);
        if (agent != null) return turn.routedBy(RouteStage.AGENT, agent);
        boolean priorProfileOverview = turn.previousState != null
                && ((WaviTools.GET_MY_PROFILE_FIELD.equals(turn.previousState.tool())
                    && WaviTools.ProfileField.PROFILE.name().equals(turn.previousState.arguments().get("field")))
                    || (turn.previousState.pending() != null
                        && WaviClarification.asksForOwnProfileOverview(turn.previousState.pending().question())));
        if (actor != null && (WaviClarification.asksForOwnProfileOverview(message)
                || (priorProfileOverview && WaviClarification.requestsCompleteOverview(message)))) {
            MyHrChatResponse profile = viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP,
                    WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", WaviTools.ProfileField.PROFILE.name()),
                    () -> new MyHrChatResponse(formatProfileAnswer(
                            employeeManagementService.getOwnDetail(actor.userId()), ProfileQuestion.PROFILE)));
            return turn.routedBy(RouteStage.PRECISE, new MyHrChatResponse(profile.message()
                    + " These are the profile details I can show in chat; you can ask separately about supported "
                    + "timesheets and requests.", profile.knowledgeStatus(), profile.citations())
                    .withActions(List.of(MyHrConversationIntents.Page.MY_PROFILE.name())));
        }
        if (actor != null && WaviPolicyEvidence.isCitationFollowUp(message)
                && !MyHrScopeGuard.isExplicitlyOutOfScope(message)
                && (WaviPolicyEvidence.sourceNumber(message) != null || !WaviPolicyEvidence.hasExplicitPolicySubject(message))
                && ((turn.previousState != null && WaviTools.POLICY_QUESTION.equals(turn.previousState.tool()))
                    || !MyHrKnowledgeGrounding.isPolicyQuestion(message))) {
            return turn.routedBy(RouteStage.CONTINUATION, citationFollowUp(message, conversation, actor, turn));
        }
        if (actor != null && turn.previousState != null && WaviClarification.requestsReviewScope(message)
                && (WaviTools.GET_MY_TIMESHEETS.equals(turn.previousState.tool())
                    || WaviTools.LIST_MY_TIMESHEETS.equals(turn.previousState.tool()))) {
            return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_REVIEW_QUEUE,
                    Map.of("queue", "TIMESHEETS", "detail", "LIST"),
                    () -> reviewQueueItemsResponse(actor, WaviTools.ReviewQueue.TIMESHEETS));
        }
        if (actor != null && turn.previousState != null && turn.previousState.pending() != null) {
            String resolved = turn.previousState.pending().resolve(message);
            // An Employee can only mean their own record: "timesheet" answers "which one do you mean?" without "my".
            if (resolved == null && actor.role() == UserRole.EMPLOYEE && turn.previousState.pending().isSlotAnswer(message)) {
                resolved = turn.previousState.pending().resolve("my " + message);
            }
            // "Which one do you mean?" answered with a record named by its subject ("my offer letter", "the salary
            // certificate"): the status of that record, asked as the full question the clarification was about.
            String asked = normalizeQuestion(message);
            if (resolved == null && WaviContinuation.words(asked) <= 6 && WaviContinuation.requestKindOf(asked) != null
                    && !WaviContinuation.isHowTo(asked)) {
                List<String> words = new ArrayList<>(List.of(asked.split("\\s+")));
                while (!words.isEmpty() && List.of("it's", "its", "i", "mean", "the", "my").contains(words.getFirst())) {
                    words.removeFirst();
                }
                String subject = String.join(" ", words);
                resolved = "what is the status of my " + subject + (subject.contains("request") ? "" : " request");
            }
            if (resolved != null) {
                MyHrChatResponse answer = deterministicResponse(resolved, actor.userId(), actor, false, turn);
                if (answer == null) answer = referenceResponse(resolved, actor, turn);
                if (answer != null) return turn.routedBy(RouteStage.CONTINUATION, answer);
            }
            if (turn.previousState.pending().isSlotAnswer(message)) {
                return viaTools(actor, turn, WaviRouterDecision.Intent.CLARIFY, WaviTools.CLARIFY, turn.previousState.arguments(),
                        () -> new MyHrChatResponse(WaviClarification.prompt(turn.previousState.arguments(), actor.role())));
            }
        }
        // The requested answer type, scope and selection, resolved against the verified dialogue state: follow-ups
        // ("why?", "which was submitted most recently?", "who submitted them?") and answer shapes the keyword routes
        // below would answer with the wrong type (a count for "who", the reviewer's own record for "my team's").
        // Follow-ups only, resolved against the verified state, in both modes (fresh questions wait for the router).
        MyHrChatResponse framed = frameResponse(message, conversation, actor, turn, FramePhase.FOLLOW_UP);
        if (framed != null) return turn.routedBy(RouteStage.FRAME, framed);
        // Two questions in one message ("what is my employee ID and who is my manager"): each half answered by its
        // own deterministic route, only when both halves have one; otherwise the message is read as one question.
        MyHrChatResponse composite = actor == null ? null : compositeResponse(message, authenticatedUserId, actor, turn);
        if (composite != null) return turn.routedBy(RouteStage.DETERMINISTIC, composite);
        // A navigation command in any phrasing ("bring up org settings for me"): the established page route, with
        // its role checks, answers the canonical form "open <target>".
        if (actor != null) {
            String asked = normalizeQuestion(message);
            String canonical = MyHrConversationIntents.navigationTarget(asked.replace('’', '\'')) == null
                    ? WaviContinuation.canonicalNavigation(asked) : null;
            if (canonical != null && MyHrConversationIntents.navigationTarget(canonical) != null) {
                MyHrChatResponse navigation = deterministicResponse(canonical, authenticatedUserId, actor, true, turn);
                if (navigation != null) return turn.routedBy(RouteStage.PRECISE, navigation);
            }
            // "How do I ask HR for an experience letter?": obtaining any document is a document request.
            if (WaviContinuation.asksHowToObtainDocument(asked) && classifyWorkflowIntent(message) == null) {
                log.info("{} provider=none workflow=DOCUMENT_REQUEST obtain-document=true", PROVIDER_USED_MARKER);
                turn.permitExternalUse();
                return turn.routedBy(RouteStage.PRECISE, new MyHrChatResponse(workflowGuidance(WorkflowIntent.DOCUMENT_REQUEST,
                        actor.role())).withActions(List.of(MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name())));
            }
        }
        // References to the person, record, period or kind of work the previous verified answer selected ("he",
        // "this employee", "it", "all", "timesheets") are resolved from the server-side state before any route can
        // restart intent selection. Each person is re-resolved in the actor's current authorized scope.
        MyHrChatResponse referenced = actor == null ? null : referenceResponse(message, actor, turn);
        if (referenced != null) return turn.routedBy(RouteStage.CONTINUATION, referenced);
        // A stamped prior tool result is server-side context, not text supplied by the browser. When semantic routing
        // is explicitly enabled, let that context resolve a continuation before broad deterministic classifiers can
        // mistake it for a new personal lookup or a generic workflow question. A low-confidence or unavailable
        // router still falls through to the exact deterministic path below.
        SemanticOutcome contextual = actor == null || semanticRouting == null || turn.previousState == null
                ? SemanticOutcome.NONE : semanticResponse(message, conversation, actor, turn);
        if (contextual.response() != null) return turn.routedBy(RouteStage.ROUTER_CONTEXT, contextual.response());
        MyHrChatResponse people = peopleReferenceResponse(message, conversation, actor, turn);
        if (people != null) return turn.routedBy(RouteStage.PEOPLE_REFERENCE, people);
        // Routing precedence (docs/wavi/routing-precedence.md). With semantic routing on: PRECISE deterministic
        // routes -> period follow-up -> router -> BROAD deterministic routes -> interpreter. Without it: every
        // deterministic route in its established order, then the interpreter, exactly as before.
        boolean tiered = actor != null && semanticRouting != null && broadRoutesDeferToRouter;
        // An elliptical work-field follow-up ("and their employee ID?") after a stamped direct-report answer must
        // resolve before the generic other-person classifier. That classifier has no selected person and would
        // otherwise return the Employee-only privacy refusal. The continuation still re-reads the actor's current
        // employee-management scope below.
        MyHrChatResponse teamContinuation = tiered || actor == null ? null
                : directReportContinuationResponse(message, conversation, actor, turn);
        if (teamContinuation != null) return turn.routedBy(RouteStage.CONTINUATION, teamContinuation);
        MyHrChatResponse deterministic = deterministicResponse(message, authenticatedUserId, actor, true, turn,
                RouteTier.PRECISE);
        if (!tiered && deterministic == null) {
            // Routing off: the question frame sits where it does after a declining router - after the precise routes,
            // before the broad ones - so a failing router answers exactly as routing off.
            MyHrChatResponse framedFresh = frameResponse(message, conversation, actor, turn, FramePhase.FRESH);
            if (framedFresh != null) return turn.routedBy(RouteStage.FRAME, framedFresh);
            deterministic = deterministicResponse(message, authenticatedUserId, actor, true, turn, RouteTier.ALL);
        }
        if (deterministic != null) return turn.routedBy(tiered ? RouteStage.PRECISE : RouteStage.DETERMINISTIC, deterministic);
        if (actor != null && WaviClarification.needsRecord(message)
                && MyHrOwnRecordIntent.classify(MyHrQuestionInterpreter.canonicalize(message)) == null) {
            return viaTools(actor, turn, WaviRouterDecision.Intent.CLARIFY, WaviTools.CLARIFY, Map.of(),
                    () -> new MyHrChatResponse(clarificationReply(actor.role())));
        }
        // "What about last week?" right after a question about the user's own timesheet for a period. The earlier
        // question only selects which of the user's own records to look up; nothing from history is repeated.
        MyHrChatResponse periodFollowUp = actor == null ? null
                : timesheetPeriodFollowUp(message, conversation, actor, turn);
        if (periodFollowUp != null) return turn.routedBy(RouteStage.PERIOD_FOLLOW_UP, periodFollowUp);
        SemanticOutcome semantic;
        if (tiered) {
            // The router decides before any keyword heuristic can claim the message. Its decision is still only a
            // choice among registered tools, gated for the verified role; the actor alone decides whose records.
            semantic = contextual.attempted() ? contextual : semanticResponse(message, conversation, actor, turn);
            if (semantic.response() != null) return turn.routedBy(RouteStage.ROUTER, semantic.response());
            if (semantic.handoff()) {
                // A confident policy or general handoff is not re-claimed by a heuristic lookup or how-to answer.
                // Only the interpreter's safety replies still apply: a personal detail Wavi never shows, or an
                // own-record question no lookup answers, never reaches a model that could only guess.
                MyHrChatResponse guarded = handoffGuardResponse(message, conversation.raw(), actor, turn);
                if (guarded != null) return turn.routedBy(RouteStage.HANDOFF_GUARD, guarded);
            } else {
                // The router abstained, failed, was unavailable or over its limits: the question frame's definite
                // answer types, then a follow-up continuation, then the heuristics as before.
                MyHrChatResponse framedFresh = frameResponse(message, conversation, actor, turn, FramePhase.FRESH);
                if (framedFresh != null) return turn.routedBy(RouteStage.FRAME, framedFresh);
                MyHrChatResponse continued = continuationResponse(message, conversation, actor, turn);
                if (continued != null) return turn.routedBy(RouteStage.CONTINUATION, continued);
                MyHrChatResponse namedFirst = namedPersonResponse(message, actor, turn);
                if (namedFirst != null) return turn.routedBy(RouteStage.PEOPLE_REFERENCE, namedFirst);
                MyHrChatResponse unresolved = unresolvedContextResponse(message, conversation, actor, turn);
                if (unresolved != null) return turn.routedBy(RouteStage.CLARIFY_REFERENCE, unresolved);
                MyHrChatResponse broad = deterministicResponse(message, authenticatedUserId, actor, true, turn,
                        RouteTier.BROAD);
                if (broad != null) return turn.routedBy(RouteStage.BROAD, broad);
                MyHrChatResponse named = namedPersonResponse(message, actor, turn);
                if (named != null) return turn.routedBy(RouteStage.PEOPLE_REFERENCE, named);
                MyHrChatResponse interpreted = interpretedResponse(message, conversation.raw(), actor, turn);
                if (interpreted != null) return turn.routedBy(RouteStage.INTERPRETED, interpreted);
            }
        } else {
            // A short follow-up to the previous answer, continued from the server-side dialogue state.
            MyHrChatResponse continued = actor == null ? null : continuationResponse(message, conversation, actor, turn);
            if (continued != null) return turn.routedBy(RouteStage.CONTINUATION, continued);
            MyHrChatResponse namedFirst = actor == null ? null : namedPersonResponse(message, actor, turn);
            if (namedFirst != null) return turn.routedBy(RouteStage.PEOPLE_REFERENCE, namedFirst);
            // A context-dependent message with no usable earlier turn is clarified before the interpreter can invent
            // a question for it ("and profile changes?" must not become "what is my profile").
            MyHrChatResponse unresolved = unresolvedContextResponse(message, conversation, actor, turn);
            if (unresolved != null) return turn.routedBy(RouteStage.CLARIFY_REFERENCE, unresolved);
            // Natural, indirect, misspelled or follow-up wording the routes above did not recognise; verified actors
            // only. No provider is called; the reply may be a personal record, so the turn stays restricted.
            MyHrChatResponse named = namedPersonResponse(message, actor, turn);
            if (named != null) return turn.routedBy(RouteStage.PEOPLE_REFERENCE, named);
            MyHrChatResponse interpreted = actor == null ? null
                    : interpretedResponse(message, conversation.raw(), actor, turn);
            if (interpreted != null) return turn.routedBy(RouteStage.INTERPRETED, interpreted);
            // Semantic routing (off by default): a model chooses one of Wavi's tools for wording no route above knew.
            // The verified actor alone decides whose records are read; without a usable decision nothing changes.
            semantic = contextual.attempted() || actor == null || semanticRouting == null ? SemanticOutcome.NONE
                    : semanticResponse(message, conversation, actor, turn);
            if (semantic.response() != null) return turn.routedBy(RouteStage.ROUTER, semantic.response());
        }
        // Nothing above (nor the router, when on) understood it: the question frame's remaining capability shapes,
        // before any reply saying the question wasn't understood. Not after a confident policy/general handoff.
        if (!semantic.handoff()) {
            MyHrChatResponse framedFallback = frameResponse(message, conversation, actor, turn, FramePhase.FALLBACK);
            if (framedFallback != null) return turn.routedBy(RouteStage.FRAME, framedFallback);
        }
        // After a router handoff (or when nothing above answered): the same unresolved-context check.
        MyHrChatResponse unresolved = unresolvedContextResponse(message, conversation, actor, turn);
        if (unresolved != null) return turn.routedBy(RouteStage.CLARIFY_REFERENCE, unresolved);
        turn.routedBy(RouteStage.PROVIDER_PATH, null);

        // Everything below reaches company-policy retrieval or an AI provider (Claude, its Ollama fallback, or
        // Ollama), so Wavi's company-only scope is enforced here, on the backend, for every one of them.
        switch (WaviProviderGate.decide(message, conversation.raw(), semantic.inScope(), semantic.routerFailed())) {
            case OUT_OF_SCOPE -> {
                log.info("{} provider=none scope=out-of-scope", PROVIDER_USED_MARKER);
                turn.permitExternalUse();
                turn.servedBy("out_of_scope");
                return new MyHrChatResponse(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
            }
            case UNRECOGNIZED -> {
                log.info("{} provider=none scope=unrecognized", PROVIDER_USED_MARKER);
                turn.permitExternalUse();
                turn.servedBy("unrecognized");
                return new MyHrChatResponse(UNRECOGNIZED_REPLY);
            }
            case ROUTER_UNAVAILABLE -> {
                log.info("{} provider=none scope=router-unavailable", PROVIDER_USED_MARKER);
                turn.permitExternalUse();
                turn.servedBy("router_unavailable");
                return new MyHrChatResponse(ROUTER_UNAVAILABLE_REPLY);
            }
            case ROUTER_UNAVAILABLE_BUSINESS -> {
                turn.servedBy("router_unavailable");
                return new MyHrChatResponse(ROUTER_UNAVAILABLE_REPLY);
            }
            case PROCEED -> { }
        }
        boolean payDate = actor != null && MyHrQuestionInterpreter.isPayDateQuestion(message);
        if (actor != null && (WaviPolicyEvidence.asksAboutApplicationAccess(message)
                || (WaviPolicyEvidence.applicationWorkflowRequested(message)
                    && !MyHrKnowledgeGrounding.isPolicyQuestion(message)))) {
            return new MyHrChatResponse("I can't verify that this is available in Waypoint. Please check with HR "
                    + "or your administrator.").withActions(List.of());
        }
        PreparedGrounding grounding = prepareGrounding(actor, message, conversation);
        if (grounding != null) {
            turn.servedBy("handoff");
            MyHrChatResponse grounded = groundedResponse(grounding, message, conversation, turn);
            // An approved policy that answers (or a withheld attempt) stands; otherwise say what Waypoint records.
            boolean noPolicyAnswer = grounded.knowledgeStatus() == MyHrKnowledgeStatus.NO_MATCHING_POLICY
                    || grounded.knowledgeStatus() == MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE;
            if (noPolicyAnswer && grounded.citations().isEmpty() && actor != null) {
                if (payDate) return new MyHrChatResponse(payDateReply(actor, grounded.knowledgeStatus()),
                        grounded.knowledgeStatus(), List.of());
                // Any other payroll question: the recorded payroll fact, not only "couldn't find it".
                if (isPayrollTopic(message)) return new MyHrChatResponse(payrollNoPolicyReply(actor,
                        grounded.knowledgeStatus()), grounded.knowledgeStatus(), List.of());
            }
            return grounded;
        }
        if (payDate) {
            // Never a model's guess at a date: Waypoint records none, and no policy could be checked.
            log.info("{} provider=none pay-date=true", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(payDateReply(actor, null));
        }
        if (actor != null && (MyHrKnowledgeGrounding.isPolicyQuestion(message)
                    || !MyHrKnowledgeGrounding.retrievalQuery(message, conversation.verified()).equals(message))) {
            // Without active approved knowledge, a general model cannot establish a company policy or workflow.
            turn.permitExternalUse();
            return new MyHrChatResponse(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER,
                    MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE, List.of());
        }
        // Claude and its fallback receive only history stamped as permitted for external use.
        turn.servedBy("handoff");
        List<MyHrChatHistoryMessage> providerHistory = generalHistory(conversation);
        if ("claude".equalsIgnoreCase(primaryProvider)) {
            turn.permitExternalUse();
            try {
                String answer = claudeChatClient.chat(message, providerHistory);
                turn.provider("claude");
                log.info("{} provider=claude", PROVIDER_USED_MARKER);
                return new MyHrChatResponse(answer);
            } catch (ClaudeChatClient.ClaudeSafetyRefusalException refused) {
                // A refusal is Claude's answer, never a reason to ask another provider.
                turn.provider("claude;refusal");
                log.info("{} provider=claude refusal=true", PROVIDER_USED_MARKER);
                return new MyHrChatResponse(REFUSAL_REPLY).withActions(List.of());
            } catch (ClaudeChatClient.ClaudeUnavailableException exception) {
                if (!canUseLocalFallback(exception)) {
                    throw exception;
                }
                String answer = ollamaClient.chat(message, providerHistory);
                turn.provider("ollama;fallback=true");
                log.info("{} provider=ollama fallback=true", PROVIDER_USED_MARKER);
                return new MyHrChatResponse(answer);
            }
        }
        if ("ollama".equalsIgnoreCase(primaryProvider)) {
            turn.permitExternalUseIf(generalReplyPermitted(conversation));
            String answer = ollamaClient.chat(message, providerHistory);
            turn.provider("ollama");
            log.info("{} provider=ollama", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(answer);
        }
        throw new IllegalStateException("Unsupported My HR AI provider configuration");
    }

    /**
     * Retrieves knowledge once per message, never cached, or returns null when grounding does not
     * apply. A retrieval failure still grounds the request, so the model is told it cannot check
     * company policies instead of answering as though none exist. A follow-up to an earlier policy
     * exchange is retrieved together with the policy question it refers to, so an earlier answer is
     * re-checked against the currently active approved documents rather than trusted from history.
     */
    private PreparedGrounding prepareGrounding(VerifiedMyHrActor actor, String message, Conversation conversation) {
        return prepareGrounding(actor, message, conversation, null);
    }

    private PreparedGrounding prepareGrounding(VerifiedMyHrActor actor, String message, Conversation conversation,
                                               String retrievalOverride) {
        if (!knowledgeGroundingEnabled || actor == null || knowledgeRetrievalService == null) {
            return null;
        }
        // A prompt without policy text follows the ordinary routing, so it gets that routing's history.
        List<MyHrChatHistoryMessage> generalHistory = generalHistory(conversation);
        boolean generalPermitted = generalReplyPermitted(conversation);
        if (candidatePolicyDestination(actor.organizationId()) == PolicyDestination.NONE) {
            // Fail closed: no provider may receive policy text, so nothing is retrieved (and no query is
            // embedded), and the model is told it cannot check company policies.
            log.warn(claudeOnly
                    ? "MyHR chat knowledge grounding has no approved provider: Claude is not approved for policy "
                            + "text and Claude-only mode excludes Ollama"
                    : "MyHR chat knowledge grounding has no approved provider: Claude is not approved for policy "
                            + "text and the Ollama endpoint is not verifiably local");
            return PreparedGrounding.withoutPolicyText(unavailableGrounding(actor, message, generalHistory),
                    ExternalPolicyDecision.NOT_APPLICABLE, KnowledgeRetrievalService.StageTimings.UNMEASURED,
                    generalPermitted);
        }
        KnowledgeRetrievalService.RetrievalResult retrieval;
        try {
            // The query is embedded by Voyage, an external provider: earlier questions are added only from turns
            // whose stamps verify, never from unverified history.
            retrieval = knowledgeRetrievalService.retrieve(actor,
                    retrievalOverride == null ? MyHrKnowledgeGrounding.retrievalQuery(message, conversation.verified())
                            : retrievalOverride);
        } catch (UnauthorizedException | ForbiddenException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            log.warn("MyHR chat knowledge retrieval failed ({})", unavailable.getClass().getSimpleName());
            retrieval = new KnowledgeRetrievalService.RetrievalResult(
                    KnowledgeRetrievalService.RetrievalStatus.UNAVAILABLE, List.of(), 0);
        }
        if (retrieval == null || retrieval.status() == KnowledgeRetrievalService.RetrievalStatus.DISABLED) {
            return null;
        }
        retrieval = sameOrganizationOnly(retrieval, actor);
        Set<ChunkAccessLevel> allowedLevels = KnowledgeRetrievalService.allowedAccessLevels(actor);
        MyHrKnowledgeGrounding.Grounding general = MyHrKnowledgeGrounding.prepare(message, generalHistory, retrieval,
                allowedLevels);
        if (!general.prompt().containsPolicyContext()) {
            return PreparedGrounding.withoutPolicyText(general, ExternalPolicyDecision.NOT_APPLICABLE,
                    retrieval.timings(), generalPermitted);
        }
        // The retrieved excerpts decide where they may go, not only the configuration.
        ExternalPolicyDecision external = externalPolicyDecision(actor, retrieval.chunks());
        PolicyDestination destination = external == ExternalPolicyDecision.PERMITTED
                ? PolicyDestination.CLAUDE : localPolicyDestination();
        if (destination == PolicyDestination.NONE) {
            // These excerpts may not leave Waypoint and no local model may receive them: they are discarded and
            // the model is told it cannot check company policies, exactly as when nothing could be retrieved.
            log.warn("MyHR chat knowledge grounding has no permitted provider for the retrieved excerpts "
                    + "(external-policy={})", external);
            return PreparedGrounding.withoutPolicyText(unavailableGrounding(actor, message, generalHistory), external,
                    retrieval.timings(), generalPermitted);
        }
        if (destination == PolicyDestination.CLAUDE) {
            // Approved excerpts with external-safe history, for Claude and for its local fallback alike; the reply
            // is derived only from content permitted for external use.
            return new PreparedGrounding(MyHrKnowledgeGrounding.prepare(message, conversation.external(), retrieval,
                    allowedLevels), destination, external, retrieval.timings(), true);
        }
        // Local Ollama: the excerpts are not approved for external use, so neither is anything derived from them.
        return new PreparedGrounding(MyHrKnowledgeGrounding.prepare(message, conversation.local(), retrieval,
                allowedLevels), destination, external, retrieval.timings(), false);
    }

    private static MyHrKnowledgeGrounding.Grounding unavailableGrounding(
            VerifiedMyHrActor actor, String message, List<MyHrChatHistoryMessage> recentHistory) {
        return MyHrKnowledgeGrounding.prepare(message, recentHistory,
                new KnowledgeRetrievalService.RetrievalResult(
                        KnowledgeRetrievalService.RetrievalStatus.UNAVAILABLE, List.of(), 0),
                KnowledgeRetrievalService.allowedAccessLevels(actor));
    }

    /** Which provider may receive company policy text. */
    private enum PolicyDestination { CLAUDE, LOCAL_OLLAMA, NONE }

    /**
     * Why retrieved policy excerpts may or may not be sent to Claude; non-sensitive, for operational logs.
     * NOT_APPLICABLE: the request carries no policy text.
     */
    private enum ExternalPolicyDecision {
        PERMITTED,
        /** Claude is not the primary provider, or the deployment switch allow-external-provider is off. */
        DISABLED,
        /** An excerpt is not COMPANY_WIDE; internal read permission is not external disclosure permission. */
        RESTRICTED_CONTENT,
        /** The organization has not authorized external processing of these excerpts. */
        NOT_AUTHORIZED,
        NOT_APPLICABLE
    }

    /**
     * A grounding together with the one destination its policy text may go to, the retrieval timings, and whether
     * a reply written from it may later be sent to an external provider as history.
     */
    private record PreparedGrounding(MyHrKnowledgeGrounding.Grounding grounding, PolicyDestination destination,
                                     ExternalPolicyDecision externalDecision,
                                     KnowledgeRetrievalService.StageTimings retrievalTimings,
                                     boolean replyPermittedExternally) {

        static PreparedGrounding withoutPolicyText(MyHrKnowledgeGrounding.Grounding grounding,
                                                   ExternalPolicyDecision externalDecision,
                                                   KnowledgeRetrievalService.StageTimings retrievalTimings,
                                                   boolean replyPermittedExternally) {
            return new PreparedGrounding(grounding, PolicyDestination.NONE, externalDecision, retrievalTimings,
                    replyPermittedExternally);
        }
    }

    private boolean externalPolicyProcessingSwitchedOn() {
        return "claude".equalsIgnoreCase(primaryProvider) && externalPolicyContextApproved;
    }

    /**
     * Before retrieval: whether any provider could receive this organization's policy text - Claude only when it
     * is switched on and the organization could authorize anything, otherwise verified-local Ollama.
     */
    private PolicyDestination candidatePolicyDestination(String organizationId) {
        if (externalPolicyProcessingSwitchedOn() && mayAuthorizeExternalProcessing(organizationId)) {
            return PolicyDestination.CLAUDE;
        }
        return localPolicyDestination();
    }

    /** Ollama, but only when its endpoint is verifiably on this machine and Claude-only mode is off. */
    private PolicyDestination localPolicyDestination() {
        if (claudeOnly) {
            // Claude-only never hands policy text to Ollama.
            return PolicyDestination.NONE;
        }
        if (("claude".equalsIgnoreCase(primaryProvider) || "ollama".equalsIgnoreCase(primaryProvider))
                && ollamaClient.isLocalEndpoint()) {
            return PolicyDestination.LOCAL_OLLAMA;
        }
        return PolicyDestination.NONE;
    }

    /**
     * After retrieval: Claude may receive the excerpts only when external processing is switched on, every
     * excerpt is COMPANY_WIDE, and the actor's organization - from the verified actor, never the request - has
     * authorized exactly these excerpts. An authorization that fails is a denial.
     */
    private ExternalPolicyDecision externalPolicyDecision(VerifiedMyHrActor actor,
                                                          List<KnowledgeRetrievalService.RetrievedChunk> chunks) {
        if (!externalPolicyProcessingSwitchedOn()) {
            return ExternalPolicyDecision.DISABLED;
        }
        if (chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk.accessLevel() != ChunkAccessLevel.COMPANY_WIDE)) {
            return ExternalPolicyDecision.RESTRICTED_CONTENT;
        }
        try {
            return externalProcessingAuthorization.permits(actor.organizationId(), chunks)
                    ? ExternalPolicyDecision.PERMITTED : ExternalPolicyDecision.NOT_AUTHORIZED;
        } catch (RuntimeException failure) {
            log.warn("MyHR external policy processing authorization failed ({}); treated as not authorized",
                    failure.getClass().getSimpleName());
            return ExternalPolicyDecision.NOT_AUTHORIZED;
        }
    }

    private boolean mayAuthorizeExternalProcessing(String organizationId) {
        try {
            return externalProcessingAuthorization.mayPermit(organizationId);
        } catch (RuntimeException failure) {
            log.warn("MyHR external policy processing authorization failed ({}); treated as not authorized",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * A prompt carrying policy text goes only to its permitted destination: approved Claude, whose
     * fallback is Ollama only when local; or local Ollama alone. A prompt without policy text follows
     * the ordinary routing. Claude receives the authorized sources as documents and reports its own
     * citations, which are verified against those documents; Ollama receives the same sources, history
     * and question as the text prompt and cites by source id. Ollama answers only when Claude is
     * temporarily unavailable - never after a refusal, and never to replace an answer that failed the
     * citation rules.
     */
    private MyHrChatResponse groundedResponse(PreparedGrounding prepared, String message,
                                               Conversation conversation, Turn turn) {
        MyHrKnowledgeGrounding.Grounding grounding = prepared.grounding();
        RagStageClock clock = new RagStageClock(prepared);
        MyHrKnowledgeGrounding.GroundedAnswer unsupported = MyHrKnowledgeGrounding.unsupportedPolicyAnswer(grounding, message);
        if (unsupported != null) {
            log.info("{} provider=none grounded=true sources=0 cited=0 status={} policy-question=true",
                    PROVIDER_USED_MARKER, unsupported.status());
            clock.log("none", unsupported.status());
            turn.permitExternalUse();
            return new MyHrChatResponse(unsupported.message(), unsupported.status(), List.of());
        }
        MyHrGroundedPrompt prompt = grounding.prompt();
        String answer = null;
        List<MyHrCitedSegment> citedSegments = null;
        MyHrKnowledgeGrounding.GroundedAnswer finished;
        String provider;
        clock.startGeneration();
        try {
            if (!prompt.containsPolicyContext()) {
                if ("claude".equalsIgnoreCase(primaryProvider)) {
                    try {
                        answer = claudeChatClient.chat(prompt);
                        provider = "claude";
                    } catch (ClaudeChatClient.ClaudeUnavailableException exception) {
                        if (!canUseLocalFallback(exception)) {
                            throw exception;
                        }
                        answer = ollamaClient.chat(prompt);
                        provider = "ollama fallback=true";
                    }
                } else if ("ollama".equalsIgnoreCase(primaryProvider)) {
                    answer = ollamaClient.chat(prompt);
                    provider = "ollama";
                } else {
                    throw new IllegalStateException("Unsupported My HR AI provider configuration");
                }
            } else if (prepared.destination() == PolicyDestination.CLAUDE) {
                try {
                    citedSegments = claudeChatClient.chatWithDocuments(grounding.documentPrompt());
                    provider = "claude citations=native";
                } catch (ClaudeChatClient.ClaudeUnavailableException exception) {
                    if (localFallbackEnabled && !claudeOnly && !ollamaClient.isLocalEndpoint()) {
                        // Reported as an unavailable AI provider; policy text is not sent to a remote Ollama.
                        throw new OllamaClient.PolicyContextNotLocalException();
                    }
                    if (!canUseLocalFallback(exception)) {
                        throw exception;
                    }
                    answer = ollamaClient.chat(prompt);
                    provider = "ollama fallback=true";
                }
            } else if (prepared.destination() == PolicyDestination.LOCAL_OLLAMA) {
                answer = ollamaClient.chat(prompt);
                provider = "ollama local-policy=true";
            } else {
                throw new OllamaClient.PolicyContextNotLocalException();
            }
        } catch (ClaudeChatClient.ClaudeSafetyRefusalException refused) {
            // A refusal is Claude's answer, never a reason to ask another provider.
            turn.provider("claude;refusal;grounded");
            log.info("{} provider=claude refusal=true grounded=true sources={} cited=0", PROVIDER_USED_MARKER,
                    grounding.sources().size());
            clock.endGeneration();
            clock.log("claude", null);
            turn.permitExternalUse();
            return new MyHrChatResponse(REFUSAL_REPLY).withActions(List.of());
        }
        clock.endGeneration();

        clock.startCitationValidation();
        finished = citedSegments != null
                ? MyHrKnowledgeGrounding.finishCited(grounding, citedSegments)
                : MyHrKnowledgeGrounding.finish(grounding, answer);
        clock.endCitationValidation();
        // A local model can answer a policy correctly (company-wide or HR-only) but omit the required source marker.
        // Give it one bounded, local-only chance to produce a cited answer; the same citation and stale-fact checks
        // still decide whether anything is returned. Never retry a valid-but-stale citation, which preserves G3.
        if (needsLocalCitationRetry(prepared, answer, finished)) {
            clock.markCitationRetry();
            clock.startGeneration();
            answer = ollamaClient.chat(citationRetryPrompt(prompt));
            clock.endGeneration();
            provider = "ollama local-policy=true citation-retry=true";
            clock.startCitationValidation();
            finished = MyHrKnowledgeGrounding.finish(grounding, answer);
            clock.endCitationValidation();
        }
        // A valid source marker is not evidence that the model's wording follows from that source. Construct the
        // visible policy claims from current authorized sentences, independently of the generated paraphrase.
        finished = WaviPolicyEvidence.constrain(grounding, finished,
                MyHrKnowledgeGrounding.retrievalQuery(message,
                        conversation.session() == null ? conversation.local() : conversation.verified()), message);
        List<MyHrChatCitation> citations = chatCitations(finished.cited());
        if (!citations.isEmpty() && grounding.policyQuestion()) turn.answeredWithPolicy(finished.cited());
        String rejectionReason = finished.unverifiedReason() == null ? "none" : finished.unverifiedReason().name();
        turn.provider(provider.strip().replace(' ', ';'));
        log.info("{} provider={} grounded=true sources={} cited={} status={} reason={}", PROVIDER_USED_MARKER, provider,
                grounding.sources().size(), citations.size(), finished.status(), rejectionReason);
        clock.log(provider.split(" ", 2)[0], finished.status());
        turn.permitExternalUseIf(prepared.replyPermittedExternally());
        return new MyHrChatResponse(finished.message(), finished.status(), citations);
    }

    private static List<MyHrChatCitation> chatCitations(List<MyHrKnowledgeGrounding.Source> sources) {
        return sources.stream().map(source -> new MyHrChatCitation(source.number(), source.title(), source.documentName(),
                source.accessLevel() == ChunkAccessLevel.HR_ONLY)).toList();
    }

    /** A previous source number identifies only a document, never permission or a cached policy passage. */
    private MyHrChatResponse citationFollowUp(String message, Conversation conversation, VerifiedMyHrActor actor, Turn turn) {
        WaviDialogueStore.State previous = turn.previousState;
        List<WaviDialogueStore.CitationRef> references = previous != null
                && WaviTools.POLICY_QUESTION.equals(previous.tool()) ? previous.citations() : List.of();
        turn.keepPreviousState = previous != null;
        if (references.isEmpty()) return new MyHrChatResponse(
                "I don't have a verified source from the previous answer. Please ask the policy question again.").withActions(List.of());
        Integer number = WaviPolicyEvidence.sourceNumber(message);
        if (number == null && references.size() > 1) return new MyHrChatResponse("Which source number do you mean?").withActions(List.of());
        WaviDialogueStore.CitationRef reference = number == null ? references.getFirst()
                : number == -1 ? references.getLast()
                : references.stream().filter(ref -> ref.number() == number).findFirst().orElse(null);
        if (reference == null) return new MyHrChatResponse(
                "That source number wasn't in my previous answer. Please choose one of the displayed sources.").withActions(List.of());
        String evidenceQuestion = MyHrKnowledgeGrounding.retrievalQuery(message, conversation.verified());
        if (evidenceQuestion.equals(message)) {
            String anchor = MyHrKnowledgeGrounding.latestPolicyQuestion(conversation.verified());
            if (anchor != null && anchor.length() + message.length() + 1 <= KnowledgeRetrievalService.MAX_QUESTION_CHARS) {
                evidenceQuestion = anchor + "\n" + message;
            }
        }
        PreparedGrounding prepared = prepareGrounding(actor, message, conversation, evidenceQuestion);
        if (prepared == null || prepared.grounding().state() == MyHrKnowledgeGrounding.ContextState.UNAVAILABLE) {
            return new MyHrChatResponse(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER,
                    MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE, List.of()).withActions(List.of());
        }
        MyHrKnowledgeGrounding.Source current = prepared.grounding().sources().stream()
                .filter(source -> reference.documentId().equals(source.documentId())).findFirst().orElse(null);
        if (current == null) return new MyHrChatResponse(
                "I can't find that source among the policies currently available to you. It may have changed or been removed.",
                MyHrKnowledgeStatus.NO_MATCHING_POLICY, List.of()).withActions(List.of());
        if (reference.ingestionId() == null || !reference.ingestionId().equals(current.ingestionId())) {
            return new MyHrChatResponse("That source has changed since my earlier answer. Please ask the policy question "
                    + "again to check the current version.", MyHrKnowledgeStatus.NO_MATCHING_POLICY, List.of())
                    .withActions(List.of());
        }
        MyHrKnowledgeGrounding.GroundedAnswer answer = WaviPolicyEvidence.answer(List.of(current),
                evidenceQuestion, message);
        if (!answer.cited().isEmpty()) turn.answeredWithPolicy(answer.cited());
        turn.permitExternalUseIf(prepared.replyPermittedExternally());
        // Answer first: which approved document it came from (re-checked just now), then the passage it says.
        String sourceLine = isBlank(current.title()) ? "" : "Source: " + current.title() + " - an approved company "
                + "policy, current version" + (current.approvedOn() == null ? "" : ", approved " + formatDay(current.approvedOn()))
                + ". ";
        String text = answer.cited().isEmpty() ? answer.message() : sourceLine + "It says: " + answer.message();
        return new MyHrChatResponse(text, answer.status(), chatCitations(answer.cited())).withActions(List.of());
    }

    /**
     * Exactly one retry, and only when: the answer failed verification, it is a policy question, the policy text is
     * going to the verified-LOCAL model (never Claude, never a remote Ollama), and the model cited NOTHING. An answer
     * that cites a source but fails another check (a stale version, an unsupported claim) is never retried.
     */
    private boolean needsLocalCitationRetry(PreparedGrounding prepared, String answer,
                                            MyHrKnowledgeGrounding.GroundedAnswer finished) {
        MyHrKnowledgeGrounding.Grounding grounding = prepared.grounding();
        return finished.status() == MyHrKnowledgeStatus.UNVERIFIED
                && grounding.policyQuestion()
                && prepared.destination() == PolicyDestination.LOCAL_OLLAMA
                && answer != null
                && MyHrKnowledgeGrounding.cite(answer, grounding.sources()).cited().isEmpty();
    }

    /**
     * Elapsed time of each RAG stage for one grounded reply - query embedding, vector search, candidate
     * validation, generation (every provider call, fallback and retry included) and citation validation - logged
     * once as one line of durations, routing decision and counts. Never the question, history, policy text,
     * answer, organization, user or any credential.
     */
    private static final class RagStageClock {
        private final PreparedGrounding prepared;
        private long generationNanos;
        private long citationNanos;
        private long started;
        private boolean generated;
        private boolean validated;
        private boolean citationRetry;

        RagStageClock(PreparedGrounding prepared) {
            this.prepared = prepared;
        }

        void startGeneration() {
            started = System.nanoTime();
        }

        void endGeneration() {
            generationNanos += Math.max(0, System.nanoTime() - started);
            generated = true;
        }

        /** A second local generation was needed for a missing citation; it roughly doubles generation time. */
        void markCitationRetry() {
            citationRetry = true;
        }

        void startCitationValidation() {
            started = System.nanoTime();
        }

        void endCitationValidation() {
            citationNanos += Math.max(0, System.nanoTime() - started);
            validated = true;
        }

        void log(String provider, MyHrKnowledgeStatus status) {
            KnowledgeRetrievalService.StageTimings retrieval = prepared.retrievalTimings();
            MyHrChatService.log.info("{} embeddingMs={} vectorSearchMs={} retrievalValidationMs={} generationMs={} "
                            + "citationValidationMs={} destination={} external-policy={} provider={} sources={} status={} "
                            + "citation-retry={}",
                    RAG_LATENCY_MARKER, millis(retrieval.embeddingNanos()), millis(retrieval.vectorSearchNanos()),
                    millis(retrieval.validationNanos()), generated ? millis(generationNanos) : "-",
                    validated ? millis(citationNanos) : "-", prepared.destination(), prepared.externalDecision(),
                    provider, prepared.grounding().sources().size(), status == null ? "none" : status, citationRetry);
        }

        private static String millis(long nanos) {
            return nanos < 0 ? "-" : String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
        }
    }

    private MyHrGroundedPrompt citationRetryPrompt(MyHrGroundedPrompt prompt) {
        return new MyHrGroundedPrompt(prompt.systemPrompt() + "\n\nReturn the policy answer again using only the "
                + "current policy context. Include at least one valid source id citation, such as [S1], for every "
                + "policy statement; do not use [GENERAL].", prompt.history(), prompt.userMessage(), true);
    }

    /**
     * The constrained interpretation fallback ({@link MyHrQuestionInterpreter}). A personal field Wavi does not show
     * gets an honest fixed reply; natural or follow-up wording is rewritten into a canonical question that goes
     * through exactly the same deterministic routes and authorized lookups as a typed one; a status question that
     * names no record gets a short clarification. Nothing here calls a model, and the verified actor alone decides
     * what may be looked up. Null when none of this applies, so the scope check and the model path follow.
     */
    private MyHrChatResponse interpretedResponse(String message, List<MyHrChatHistoryMessage> history,
                                                 VerifiedMyHrActor actor, Turn turn) {
        if (WaviClarification.workIdentityQuestion(message)) {
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_ROLE,
                    Map.of("asked", "ANY", "detail", "WORK_CONTEXT"),
                    () -> new MyHrChatResponse(new ToolAnswers().role(actor, MyHrConversationIntents.AskedRole.ANY)
                            + WaviCapabilities.WORK_CONTEXT_NOTE));
        }
        String canonical = MyHrQuestionInterpreter.interpret(message, history);
        MyHrQuestionInterpreter.UnsupportedField field = unsupportedFieldOf(canonical == null ? message : canonical);
        if (field != null) return unsupportedFieldResponse(field, actor, turn);
        // "How many hours?" / "what's the total?" with no period, no owner and nothing earlier to continue: the
        // missing slot is asked for instead of handing an unanswerable question to a model.
        String bare = normalizeQuestion(message);
        if (turn.previousState == null && WaviContinuation.words(bare) <= 5 && !WaviContinuation.isHowTo(bare)
                // Only an amount question ("how many/much ...", "the total"): never a how-to or policy question that
                // merely mentions hours ("Can I edit overtime hours?").
                && ((WaviContinuation.asksForHours(bare) && !WaviContinuation.withoutQuantityInterrogative(bare).equals(bare.strip()))
                    || asksUnitlessTotal(bare, message))
                && MyHrConversationIntents.periodIn(WaviContinuation.withoutQuantityInterrogative(bare), LocalDate.now(clock)) == null
                && !WaviContinuation.hasNameShapedToken(message) && !WaviContinuation.refersToPerson(bare)) {
            log.info("{} provider=none clarification=period", PROVIDER_USED_MARKER);
            return new MyHrChatResponse(actor.role() == UserRole.EMPLOYEE
                    ? "Hours for which period? For example \"my hours last week\", \"my hours in October\" or \"my hours "
                    + "between Oct 5 and Oct 18\"."
                    : "Whose hours, and for which period? For example \"my hours last week\", \"hours for <a person in "
                    + "your scope> in October\" or \"my team's hours last week\".");
        }
        // A rewrite would drop the conditional clause and turn a rule question into a record lookup.
        if (isHypotheticalPolicyQuestion(message)) return null;
        if (canonical != null) {
            // Romanized Telugu/Hindi is read as English, greetings and "who are you" included; other rewrites never
            // produce small talk.
            MyHrChatResponse answer = deterministicResponse(canonical, actor.userId(), actor,
                    MyHrQuestionInterpreter.isRomanized(message), turn.restrictedChild());
            if (answer != null) {
                log.info("{} provider=none interpreted=true", PROVIDER_USED_MARKER);
                return answer;
            }
        }
        // The same own-record question in words no route knows ("manager name", "who my manager is"): asked by its
        // subject instead, through the same deterministic routes.
        String wording = canonical == null ? MyHrQuestionInterpreter.canonicalize(message) : canonical;
        String byRecord = MyHrQuestionInterpreter.recordQuestion(wording);
        if (byRecord != null && !byRecord.equals(wording)) {
            MyHrChatResponse answer = deterministicResponse(byRecord, actor.userId(), actor, false, turn.restrictedChild());
            if (answer != null) {
                log.info("{} provider=none interpreted=record-subject", PROVIDER_USED_MARKER);
                return answer;
            }
        }
        if (MyHrQuestionInterpreter.isAmbiguousStatusQuestion(message)) {
            log.info("{} provider=none clarification=true", PROVIDER_USED_MARKER);
            return viaTools(actor, turn.restrictedChild(), WaviRouterDecision.Intent.CLARIFY, WaviTools.CLARIFY, Map.of(),
                    () -> new MyHrChatResponse(clarificationReply(actor.role())));
        }
        // "What happens if I submit my timesheet late?" asks about a rule or consequence, not which record: it goes
        // on to the policy path rather than being asked "which record do you mean?".
        String asked = normalizeQuestion(message);
        if (WaviContinuation.isConditional(asked) && WaviContinuation.asksConsequence(asked)) return null;
        // A question about the user's own record that no lookup answers never reaches a model, which has no access to
        // the record and could only guess.
        return MyHrQuestionInterpreter.isUnansweredOwnRecordQuestion(wording) ? unansweredOwnRecordResponse() : null;
    }

    /**
     * After a confident router handoff to the policy or general path: only the interpreter's safety replies, never
     * its rewrites or clarification. Null when neither applies.
     */
    private MyHrChatResponse handoffGuardResponse(String message, List<MyHrChatHistoryMessage> history,
                                                  VerifiedMyHrActor actor, Turn turn) {
        String canonical = MyHrQuestionInterpreter.interpret(message, history);
        MyHrQuestionInterpreter.UnsupportedField field = unsupportedFieldOf(canonical == null ? message : canonical);
        if (field != null) return unsupportedFieldResponse(field, actor, turn);
        // A provider handoff is not permission to generate a personal record answer. A recognized own-record
        // intent must still use its authorized service, even when the model incorrectly picked policy/general.
        MyHrOwnRecordIntent.Intent record = ownRecordIntent(message);
        if (!isHypotheticalPolicyQuestion(message) && record != null && record.kind() == MyHrOwnRecordIntent.Kind.OWN) {
            String answer = ownRecordResponse(actor, record);
            if (answer != null) {
                rememberOwnRequest(turn, record, actor);
                return new MyHrChatResponse(answer);
            }
        }
        String wording = canonical == null ? MyHrQuestionInterpreter.canonicalize(message) : canonical;
        return MyHrQuestionInterpreter.isUnansweredOwnRecordQuestion(wording) ? unansweredOwnRecordResponse() : null;
    }

    /**
     * A detail Wavi never shows (pay, leave balance, a contact field), however it is time-qualified: "what's my
     * salary this month" is the pay question with "this month" added.
     */
    private static MyHrQuestionInterpreter.UnsupportedField unsupportedFieldOf(String text) {
        MyHrQuestionInterpreter.UnsupportedField field = MyHrQuestionInterpreter.unsupportedField(text);
        if (field != null) return field;
        String plain = WaviContinuation.withoutTimeQualifiers(text);
        return plain == null || plain.equals(text) ? null : MyHrQuestionInterpreter.unsupportedField(plain);
    }

    private MyHrChatResponse unsupportedFieldResponse(MyHrQuestionInterpreter.UnsupportedField field,
                                                      VerifiedMyHrActor actor, Turn turn) {
        log.info("{} provider=none unsupported-field={}", PROVIDER_USED_MARKER, field);
        boolean onProfile = field != MyHrQuestionInterpreter.UnsupportedField.PAY
                && field != MyHrQuestionInterpreter.UnsupportedField.LEAVE_BALANCE;
        return viaTools(actor, turn.restrictedChild(), WaviRouterDecision.Intent.UNSUPPORTED, WaviTools.UNSUPPORTED_FIELD,
                Map.of("field", field.name()), () -> new MyHrChatResponse(unsupportedFieldReply(field))
                        .withActions(onProfile ? List.of(MyHrConversationIntents.Page.MY_PROFILE.name()) : List.of()));
    }

    private MyHrChatResponse unansweredOwnRecordResponse() {
        log.info("{} provider=none own-record-unanswered=true", PROVIDER_USED_MARKER);
        return new MyHrChatResponse(UNANSWERED_OWN_RECORD_REPLY)
                .withActions(List.of(MyHrConversationIntents.Page.MY_PROFILE.name()));
    }

    static final String TIMESHEET_QUEUE_REVIEWERS_ONLY = "Timesheet review queue counts are available only to HR and Company Admin accounts.";
    static final String PROFILE_CHANGE_QUEUE_REVIEWERS_ONLY = "Profile-change approval queue counts are available only to HR and Company Admin accounts.";
    static final String DOCUMENT_QUEUE_REVIEWERS_ONLY = "Document-request queue counts are available only to HR and Company Admin accounts.";

    static final String UNANSWERED_OWN_RECORD_REPLY = "I'm not sure which record or detail you mean. I can tell you "
            + "your employee ID, job title, reporting manager and a profile summary, your "
            + "timesheet status for a week, month or date, and the status of your profile change and document requests. "
            + "Which of these would you like to check?";

    /**
     * As typed, or else as canonically worded; either way only a classification, answered for the actor alone. A
     * generic queue count comes only from the typed question: reworded, a queue question reaches its exact queue
     * route through the interpretation fallback instead.
     */
    /** A policy question framed as a hypothetical: a conditional clause plus a policy topic. */
    private boolean isHypotheticalPolicyQuestion(String message) {
        return WaviContinuation.isConditional(normalizeQuestion(message)) && MyHrKnowledgeGrounding.isPolicyQuestion(message);
    }

    private MyHrOwnRecordIntent.Intent ownRecordIntent(String message) {
        // A hypothetical company-policy question ("what happens to my pay if my timesheet is rejected?") asks about a
        // rule, not the user's actual record, so no own-record lookup answers it; it goes to the policy path.
        if (isHypotheticalPolicyQuestion(message)) return null;
        // "What happens if I submit my timesheet late?" asks about a consequence: no record's status answers it.
        String asked = normalizeQuestion(message);
        if (WaviContinuation.isConditional(asked) && WaviContinuation.asksConsequence(asked)) return null;
        MyHrOwnRecordIntent.Intent typed = MyHrOwnRecordIntent.classify(message);
        if (typed != null) return typed;
        MyHrOwnRecordIntent.Intent reworded = MyHrOwnRecordIntent.classify(MyHrQuestionInterpreter.canonicalize(message));
        return reworded == null || reworded.kind() == MyHrOwnRecordIntent.Kind.QUEUE_COUNT ? null : reworded;
    }

    static final String CLARIFY_EMPLOYEE =
            "Which one do you mean: your latest timesheet, profile change request or document request?";
    static final String CLARIFY_HR = "Which one do you mean: your own latest timesheet, profile change request or "
            + "document request, or the items waiting for your review?";
    static final String CLARIFY_ADMIN = "Which one do you mean: your profile change request, or the timesheets, "
            + "profile change requests or document requests waiting for approval in your organization?";

    private String clarificationReply(UserRole role) {
        return switch (role) {
            case HR -> CLARIFY_HR;
            case COMPANY_ADMIN -> CLARIFY_ADMIN;
            default -> CLARIFY_EMPLOYEE;
        };
    }

    /** Never a value: these fields are not read for chat, so nothing can be shown wrongly or out of date. */
    private String unsupportedFieldReply(MyHrQuestionInterpreter.UnsupportedField field) {
        return switch (field) {
            case PHONE_NUMBER -> contactFieldReply("phone number");
            case PERSONAL_EMAIL -> contactFieldReply("personal email");
            case HOME_ADDRESS -> contactFieldReply("home address");
            case START_DATE -> "I can't look up your start date in chat. Open My Profile to see your employment "
                    + "details, or ask HR.";
            case EMPLOYMENT_TYPE -> "I can't look up your employment type in chat. Open My Profile to see your "
                    + "employment details, or ask HR.";
            case PAY -> "I can't see pay or salary information. Please check your pay documents or ask HR.";
            case LEAVE_BALANCE -> "I can't see leave or PTO balances: Waypoint doesn't record them. Please check with "
                    + "HR for your balance. I can look up the leave policy in the approved company policies if you ask "
                    + "about it, for example \"What is the annual leave policy?\"";
        };
    }

    private String contactFieldReply(String label) {
        return "I don't show your current " + label + " in chat. Open My Profile to see it; to change it, submit a "
                + "profile change request.";
    }

    private List<MyHrChatHistoryMessage> filterSafeHistory(List<MyHrChatHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        if (history.size() > 10) {
            return List.of();
        }

        List<MyHrChatHistoryMessage> safeHistory = new ArrayList<>();
        for (int index = 0; index < history.size();) {
            MyHrChatHistoryMessage userMessage = history.get(index);
            if (!isValidHistoryMessage(userMessage) || !"user".equals(userMessage.role())) {
                return List.of();
            }

            boolean personalHrIntent = isPersonalHrQuestion(userMessage.content())
                    || containsPersonalHrHistoryTopic(userMessage.content());
            if (index + 1 < history.size()) {
                MyHrChatHistoryMessage assistantMessage = history.get(index + 1);
                if (!isValidHistoryMessage(assistantMessage) || !"assistant".equals(assistantMessage.role())) {
                    return List.of();
                }
                personalHrIntent = personalHrIntent
                        || containsPersonalHrHistoryTopic(assistantMessage.content());
                if (!personalHrIntent) {
                    safeHistory.add(userMessage);
                    safeHistory.add(assistantMessage);
                }
                index += 2;
            } else {
                if (!personalHrIntent) {
                    safeHistory.add(userMessage);
                }
                index++;
            }
        }
        return List.copyOf(safeHistory);
    }

    private boolean isValidHistoryMessage(MyHrChatHistoryMessage message) {
        return message != null && message.content() != null && !message.content().isBlank()
                && message.content().length() <= 4000
                && ("user".equals(message.role()) || "assistant".equals(message.role()));
    }

    private boolean isPersonalHrQuestion(String message) {
        return MyHrOwnRecordIntent.classify(message) != null
                // Natural or misspelled wording of the same questions is just as personal.
                || MyHrOwnRecordIntent.classify(MyHrQuestionInterpreter.canonicalize(message)) != null
                || MyHrQuestionInterpreter.unsupportedField(message) != null
                || isRecentTimesheetStatusQuestion(message)
                || isProfileChangeStatusQuestion(message)
                || classifyProfileChangeDateQuestion(message) != null
                || classifyPhoneNumberChangeQuestion(message) != null
                || classifyProfileQuestion(message) != null;
    }

    private boolean containsPersonalHrHistoryTopic(String message) {
        return PERSONAL_HR_HISTORY_TOPIC.matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    private boolean isRecentTimesheetStatusQuestion(String message) {
        return matchesLookup(RECENT_TIMESHEET_STATUS_QUESTION, message);
    }

    private boolean isProfileChangeStatusQuestion(String message) {
        return matchesLookup(PROFILE_CHANGE_STATUS_QUESTION, message);
    }

    private ProfileChangeDateQuestion classifyProfileChangeDateQuestion(String message) {
        String normalized = normalizeQuestion(message);
        if (PROFILE_CHANGE_APPROVED_DATE_QUESTION.matcher(normalized).matches()) return ProfileChangeDateQuestion.APPROVED_DATE;
        if (PROFILE_CHANGE_REQUESTED_DATE_QUESTION.matcher(normalized).matches()) return ProfileChangeDateQuestion.REQUESTED_DATE;
        return null;
    }

    private PhoneNumberChangeQuestion classifyPhoneNumberChangeQuestion(String message) {
        String normalized = normalizeQuestion(message);
        if (PHONE_CHANGE_APPROVAL_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.APPROVAL;
        if (PHONE_CHANGE_PENDING_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.PENDING;
        if (PHONE_CHANGE_REJECTION_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.REJECTION;
        if (PHONE_CHANGE_REQUESTED_DATE_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.REQUESTED_DATE;
        if (PHONE_CHANGE_APPROVED_DATE_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.APPROVED_DATE;
        if (PHONE_CHANGE_EFFECTIVE_DATE_QUESTION.matcher(normalized).matches()) return PhoneNumberChangeQuestion.EFFECTIVE_DATE;
        return null;
    }

    private DocumentRequestQuestion classifyDocumentRequestQuestion(String message) {
        String normalized = normalizeQuestion(message);
        if (matchesLookup(LATEST_DOCUMENT_REQUEST_STATUS_QUESTION, message)) return DocumentRequestQuestion.LATEST_STATUS;
        if (OFFER_LETTER_STATUS_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.OFFER_LETTER_STATUS;
        if (DOCUMENT_REQUEST_REJECTION_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.REJECTION_REASON;
        if (EMPLOYMENT_VERIFICATION_REQUEST_DATE_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.EMPLOYMENT_VERIFICATION_REQUEST_DATE;
        if (REQUESTED_DOCUMENTS_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.REQUESTED_DOCUMENTS;
        if (COMPLETED_OFFER_LETTER_DOWNLOAD_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.COMPLETED_OFFER_LETTER_DOWNLOAD;
        if (ONBOARDING_REQUEST_PENDING_QUESTION.matcher(normalized).matches()) return DocumentRequestQuestion.ONBOARDING_PENDING;
        return null;
    }

    private DocumentRequestQueueQuestion classifyDocumentRequestQueueQuestion(String message) {
        String normalized = normalizeQueueQuestion(message);
        if (HR_DOCUMENT_REVIEW_COUNT_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.HR_AWAITING_REVIEW;
        if (HR_PENDING_DOCUMENT_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.HR_PENDING;
        if (HR_RETURNED_DOCUMENT_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.HR_RETURNED;
        if (HR_ASSIGNED_DOCUMENT_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.HR_ASSIGNED;
        if (HR_DOCUMENT_REVIEW_LOCATION_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.HR_REVIEW_LOCATION;
        // Before the Company Admin approval queue: "my approval" is resolved for the asker's own role.
        if (MY_DOCUMENT_REQUEST_QUEUE_QUESTION.matcher(normalized).matches()) {
            return asksForApproval(normalized) ? DocumentRequestQueueQuestion.MY_APPROVAL : DocumentRequestQueueQuestion.MY_QUEUE;
        }
        if (ADMIN_PENDING_DOCUMENT_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.ADMIN_PENDING;
        if (ADMIN_APPROVAL_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.ADMIN_AWAITING_APPROVAL;
        if (ADMIN_COMPLETED_DOCUMENT_QUEUE_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.ADMIN_COMPLETED;
        if (ADMIN_DOCUMENT_APPROVAL_LOCATION_QUESTION.matcher(normalized).matches()) return DocumentRequestQueueQuestion.ADMIN_REVIEW_LOCATION;
        return null;
    }

    private String normalizeQueueQuestion(String message) {
        return normalizeQuestion(message).replaceFirst(
                "^(?:(?:can|could|would) you (?:please )?(?:tell me|show me|check)|please (?:tell me|show me|check))\\s+", "");
    }

    private ProfileChangeQueueQuestion classifyProfileChangeQueueQuestion(String message) {
        String normalized = normalizeQueueQuestion(message);
        if (HR_PROFILE_REVIEW_COUNT_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.HR_AWAITING_REVIEW;
        if (PROFILE_PENDING_QUEUE_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.PENDING;
        if (HR_PROFILE_REVIEW_LOCATION_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.HR_REVIEW_LOCATION;
        if (ADMIN_PROFILE_PENDING_ORGANIZATION_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.ADMIN_PENDING_ORGANIZATION;
        if (ADMIN_PROFILE_APPROVAL_QUEUE_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.ADMIN_AWAITING_APPROVAL;
        if (ADMIN_PROFILE_APPROVED_QUEUE_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.ADMIN_APPROVED;
        if (ADMIN_PROFILE_REJECTED_QUEUE_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.ADMIN_REJECTED;
        if (PROFILE_REVIEW_LOCATION_QUESTION.matcher(normalized).matches()) return ProfileChangeQueueQuestion.REVIEW_LOCATION;
        if (MY_PROFILE_CHANGE_QUEUE_QUESTION.matcher(normalized).matches()) {
            return asksForApproval(normalized) ? ProfileChangeQueueQuestion.MY_APPROVAL : ProfileChangeQueueQuestion.MY_QUEUE;
        }
        return null;
    }

    private boolean asksForApproval(String normalized) {
        return normalized.contains("approv") || normalized.contains("sign-off") || normalized.contains("signoff");
    }

    private String profileChangeQueueResponse(VerifiedMyHrActor actor, String authenticatedUserId,
                                              ProfileChangeQueueQuestion question) {
        if (actor.role() == UserRole.EMPLOYEE) {
            return PROFILE_CHANGE_QUEUE_REVIEWERS_ONLY;
        }
        if (question == ProfileChangeQueueQuestion.MY_APPROVAL || question == ProfileChangeQueueQuestion.MY_QUEUE) {
            question = actor.role() != UserRole.COMPANY_ADMIN ? ProfileChangeQueueQuestion.HR_AWAITING_REVIEW
                    : question == ProfileChangeQueueQuestion.MY_APPROVAL ? ProfileChangeQueueQuestion.ADMIN_AWAITING_APPROVAL
                    : ProfileChangeQueueQuestion.ADMIN_PENDING_ORGANIZATION;
        }
        boolean adminOnly = switch (question) {
            case ADMIN_PENDING_ORGANIZATION, ADMIN_AWAITING_APPROVAL, ADMIN_APPROVED, ADMIN_REJECTED -> true;
            default -> false;
        };
        if (actor.role() == UserRole.HR && adminOnly) {
            return "Organization-wide profile-change summaries are available only to Company Admin accounts.";
        }
        if (actor.role() == UserRole.COMPANY_ADMIN && question == ProfileChangeQueueQuestion.HR_AWAITING_REVIEW) {
            return "HR assigned-review summaries are available only to the HR user each profile change is assigned to.";
        }
        if (question == ProfileChangeQueueQuestion.HR_REVIEW_LOCATION) {
            return "Open Profile Change Requests to review profile changes assigned to you.";
        }
        if (question == ProfileChangeQueueQuestion.REVIEW_LOCATION) {
            return actor.role() == UserRole.COMPANY_ADMIN
                    ? "Open Profile Change Requests to review profile changes in your organization."
                    : "Open Profile Change Requests to review profile changes assigned to you.";
        }
        if (profileChangeRequestService == null || authenticatedUserId == null) return null;
        MyHrProfileChangeQueueSummary summary = profileChangeRequestService.queueSummary(authenticatedUserId);
        return switch (question) {
            case HR_AWAITING_REVIEW -> "You have " + summary.pending() + " profile change "
                    + plural(summary.pending(), "request") + " awaiting your review.";
            case PENDING -> actor.role() == UserRole.HR
                    ? "You have " + summary.pending() + " pending employee profile "
                    + plural(summary.pending(), "update") + " assigned to you."
                    : "There are " + summary.pending() + " pending profile change "
                    + plural(summary.pending(), "request") + " in your organization.";
            case ADMIN_PENDING_ORGANIZATION -> "There are " + summary.pending() + " pending profile change "
                    + plural(summary.pending(), "request") + " in your organization.";
            case ADMIN_AWAITING_APPROVAL -> "There are " + summary.pending() + " employee profile "
                    + plural(summary.pending(), "update") + " awaiting approval in your organization.";
            case ADMIN_APPROVED -> "There are " + summary.approved() + " approved profile change "
                    + plural(summary.approved(), "request") + " in your organization.";
            case ADMIN_REJECTED -> "There are " + summary.rejected() + " rejected profile change "
                    + plural(summary.rejected(), "request") + " in your organization.";
            default -> null;
        };
    }

    private TimesheetQueueQuestion classifyTimesheetQueueQuestion(String message) {
        String normalized = normalizeQueueQuestion(message);
        if (HR_TIMESHEET_REVIEW_COUNT_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.HR_AWAITING_REVIEW;
        if (HR_TIMESHEET_AWAITING_APPROVAL_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.HR_AWAITING_APPROVAL;
        if (HR_ASSIGNED_TIMESHEET_PENDING_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.HR_ASSIGNED_PENDING;
        if (HR_TIMESHEET_REVIEW_LOCATION_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.HR_REVIEW_LOCATION;
        if (ADMIN_TIMESHEET_SUBMITTED_ORGANIZATION_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_SUBMITTED;
        if (ADMIN_TIMESHEET_PENDING_APPROVAL_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_PENDING_APPROVAL;
        if (ADMIN_TIMESHEET_AWAITING_REVIEW_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_AWAITING_REVIEW;
        if (ADMIN_TIMESHEET_APPROVED_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_APPROVED;
        if (ADMIN_TIMESHEET_REJECTED_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_REJECTED;
        if (ADMIN_TIMESHEET_REVIEW_LOCATION_QUESTION.matcher(normalized).matches()) return TimesheetQueueQuestion.ADMIN_REVIEW_LOCATION;
        if (MY_TIMESHEET_QUEUE_QUESTION.matcher(normalized).matches()) {
            return asksForApproval(normalized) ? TimesheetQueueQuestion.MY_APPROVAL : TimesheetQueueQuestion.MY_QUEUE;
        }
        return null;
    }

    private String timesheetQueueResponse(VerifiedMyHrActor actor, String authenticatedUserId,
                                          TimesheetQueueQuestion question) {
        // Where to review is not a count: it is answered with the page the verified role actually uses, whichever
        // of the two wordings was asked. No queue is read.
        if (question == TimesheetQueueQuestion.HR_REVIEW_LOCATION || question == TimesheetQueueQuestion.ADMIN_REVIEW_LOCATION) {
            return switch (actor.role()) {
                case HR -> "Open Timesheets and select Team Timesheets to review submitted timesheets for your active direct reports.";
                case COMPANY_ADMIN -> "Open Timesheets to review submitted timesheets in your organization.";
                default -> workflowGuidance(WorkflowIntent.TIMESHEET_REVIEW, actor.role());
            };
        }
        if (actor.role() == UserRole.EMPLOYEE) {
            return TIMESHEET_QUEUE_REVIEWERS_ONLY;
        }
        if (question == TimesheetQueueQuestion.MY_APPROVAL || question == TimesheetQueueQuestion.MY_QUEUE) {
            boolean approval = question == TimesheetQueueQuestion.MY_APPROVAL;
            question = actor.role() == UserRole.COMPANY_ADMIN
                    ? (approval ? TimesheetQueueQuestion.ADMIN_PENDING_APPROVAL : TimesheetQueueQuestion.ADMIN_AWAITING_REVIEW)
                    : (approval ? TimesheetQueueQuestion.HR_AWAITING_APPROVAL : TimesheetQueueQuestion.HR_AWAITING_REVIEW);
        }
        boolean adminQuestion = question.name().startsWith("ADMIN_");
        if (actor.role() == UserRole.HR && adminQuestion) {
            return "Organization-wide timesheet summaries are available only to Company Admin accounts.";
        }
        if (actor.role() == UserRole.COMPANY_ADMIN && !adminQuestion) {
            return "HR assigned-review summaries are available only to HR users reviewing their active direct reports.";
        }
        if (authenticatedUserId == null) return null;
        MyHrTimesheetQueueSummary summary = timesheetService.queueSummary(authenticatedUserId);
        return switch (question) {
            case HR_AWAITING_REVIEW -> "You have " + summary.submitted() + " submitted "
                    + plural(summary.submitted(), "timesheet") + " awaiting your review.";
            case HR_AWAITING_APPROVAL -> "You have " + summary.submitted() + " employee "
                    + plural(summary.submitted(), "timesheet") + " awaiting your approval.";
            case HR_ASSIGNED_PENDING -> "You have " + summary.submitted() + " assigned "
                    + plural(summary.submitted(), "timesheet") + " pending review.";
            case ADMIN_SUBMITTED -> "There " + countVerb(summary.submitted()) + " " + summary.submitted() + " submitted "
                    + plural(summary.submitted(), "timesheet") + " in your organization.";
            case ADMIN_PENDING_APPROVAL -> "There " + countVerb(summary.submitted()) + " " + summary.submitted()
                    + " submitted " + plural(summary.submitted(), "timesheet")
                    + " awaiting approval in your organization.";
            case ADMIN_AWAITING_REVIEW -> "There " + countVerb(summary.submitted()) + " " + summary.submitted() + " "
                    + plural(summary.submitted(), "timesheet") + " awaiting review in your organization.";
            case ADMIN_APPROVED -> "There " + countVerb(summary.approved()) + " " + summary.approved() + " approved "
                    + plural(summary.approved(), "timesheet") + " in your organization.";
            case ADMIN_REJECTED -> "There " + countVerb(summary.rejected()) + " " + summary.rejected() + " rejected "
                    + plural(summary.rejected(), "timesheet") + " in your organization.";
            default -> null;
        };
    }

    private String documentRequestQueueResponse(VerifiedMyHrActor actor, String authenticatedUserId,
                                                DocumentRequestQueueQuestion question) {
        if (actor.role() == UserRole.EMPLOYEE) {
            return DOCUMENT_QUEUE_REVIEWERS_ONLY;
        }
        if (question == DocumentRequestQueueQuestion.MY_APPROVAL || question == DocumentRequestQueueQuestion.MY_QUEUE) {
            question = actor.role() == UserRole.COMPANY_ADMIN ? DocumentRequestQueueQuestion.ADMIN_AWAITING_APPROVAL
                    : question == DocumentRequestQueueQuestion.MY_APPROVAL ? DocumentRequestQueueQuestion.HR_APPROVAL
                    : DocumentRequestQueueQuestion.HR_PENDING;
        }
        boolean adminQuestion = question.name().startsWith("ADMIN_");
        if (actor.role() == UserRole.HR && adminQuestion) {
            return "Organization-wide document approval summaries are available only to Company Admin accounts.";
        }
        if (actor.role() == UserRole.COMPANY_ADMIN && !adminQuestion) {
            return "HR assigned-request summaries are available only to the HR user each request is assigned to.";
        }
        if (question == DocumentRequestQueueQuestion.HR_REVIEW_LOCATION) {
            return "Open Request Documents and choose Received requests to review documents assigned to you.";
        }
        if (question == DocumentRequestQueueQuestion.ADMIN_REVIEW_LOCATION) {
            return "Open Document Approvals to review documents awaiting Company Admin approval.";
        }
        if (documentRequestService == null || authenticatedUserId == null) return null;
        MyHrDocumentRequestQueueSummary summary = documentRequestService.queueSummary(authenticatedUserId);
        return switch (question) {
            case HR_AWAITING_REVIEW -> "You have " + summary.awaitingI983Review()
                    + " submitted I-983 " + plural(summary.awaitingI983Review(), "request") + " awaiting your review.";
            case HR_PENDING -> "You have " + summary.pendingFulfillment() + " pending "
                    + plural(summary.pendingFulfillment(), "request") + " assigned for fulfillment and "
                    + summary.awaitingI983Review() + " submitted I-983 "
                    + plural(summary.awaitingI983Review(), "request") + " awaiting review.";
            // HR approves only the submitted I-983s assigned to them; offer letters and other documents HR
            // delivers are approved by a Company Admin, so they are never counted as awaiting HR approval.
            case HR_APPROVAL -> "You have " + summary.awaitingI983Review() + " submitted I-983 "
                    + plural(summary.awaitingI983Review(), "request") + " awaiting your approval, and "
                    + summary.pendingFulfillment() + " pending " + plural(summary.pendingFulfillment(), "request")
                    + " assigned to you to fulfill. Offer letters and other documents you send are approved by a "
                    + "Company Admin, so they are not counted here.";
            case HR_RETURNED -> "You have " + summary.returnedForCorrection() + " assigned "
                    + plural(summary.returnedForCorrection(), "request") + " returned for correction.";
            case HR_ASSIGNED -> "You have " + summary.assignedToMe() + " document "
                    + plural(summary.assignedToMe(), "request") + " assigned to you: "
                    + summary.pendingFulfillment() + " pending for fulfillment, " + summary.awaitingI983Review()
                    + " submitted I-983 awaiting review, and " + summary.returnedForCorrection()
                    + " returned for correction.";
            case ADMIN_PENDING -> "There are " + summary.pendingFulfillment() + " pending document "
                    + plural(summary.pendingFulfillment(), "request") + " in your organization.";
            case ADMIN_AWAITING_APPROVAL -> "There are " + summary.pendingAdminApproval() + " submitted document "
                    + plural(summary.pendingAdminApproval(), "request") + " awaiting Company Admin approval.";
            case ADMIN_COMPLETED -> "There are " + summary.completed() + " completed document "
                    + plural(summary.completed(), "request") + " in your organization.";
            default -> null;
        };
    }

    /**
     * Records a deterministic personal request as the same schema-checked tool descriptor that semantic routing
     * would use. The descriptor contains no answer text or record value; it is retained only when {@link #stamped}
     * binds this turn to the verified actor and login session.
     */
    private void rememberOwnRequest(Turn turn, MyHrOwnRecordIntent.Intent intent, VerifiedMyHrActor actor) {
        if (turn == null || intent == null || actor == null) return;
        if (intent.kind() == MyHrOwnRecordIntent.Kind.PENDING_CHECK && actor.role() != UserRole.EMPLOYEE) {
            WaviTools.ReviewQueue queue = reviewQueue(intent.subject());
            if (queue != null) turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name()));
            return;
        }
        if (intent.kind() != MyHrOwnRecordIntent.Kind.OWN && intent.kind() != MyHrOwnRecordIntent.Kind.PENDING_CHECK) return;
        if (intent.subject() == MyHrOwnRecordIntent.Subject.TIMESHEET) {
            turn.answeredWith(WaviTools.GET_MY_TIMESHEETS,
                    Map.of("period", WaviTimesheetPeriod.Period.LATEST.name()));
            return;
        }
        WaviTools.RequestKind kind = requestKind(intent.subject());
        if (kind == null) return;
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("kind", kind.name());
        if (intent.aspect() != null && intent.aspect() != MyHrOwnRecordIntent.Aspect.STATUS) {
            arguments.put("aspect", intent.aspect().name());
        }
        if (intent.field() != null) arguments.put("field", intent.field().name());
        if (kind == WaviTools.RequestKind.DOCUMENT_REQUEST && intent.documentType() != null) {
            arguments.put("documentType", intent.documentType().name());
        }
        turn.answeredWith(WaviTools.GET_MY_REQUESTS, arguments);
    }

    private static WaviTools.RequestKind requestKind(MyHrOwnRecordIntent.Subject subject) {
        return switch (subject) {
            case PROFILE_CHANGE -> WaviTools.RequestKind.PROFILE_CHANGE;
            case DOCUMENT_REQUEST -> WaviTools.RequestKind.DOCUMENT_REQUEST;
            case OFFER_LETTER -> WaviTools.RequestKind.OFFER_LETTER;
            default -> null;
        };
    }

    private static WaviTools.ReviewQueue reviewQueue(MyHrOwnRecordIntent.Subject subject) {
        return switch (subject) {
            case TIMESHEET -> WaviTools.ReviewQueue.TIMESHEETS;
            case PROFILE_CHANGE -> WaviTools.ReviewQueue.PROFILE_CHANGES;
            case DOCUMENT_REQUEST, OFFER_LETTER -> WaviTools.ReviewQueue.DOCUMENT_REQUESTS;
            default -> null;
        };
    }

    private static MyHrOwnRecordIntent.Aspect phoneChangeAspect(PhoneNumberChangeQuestion question) {
        return switch (question) {
            case APPROVAL -> MyHrOwnRecordIntent.Aspect.APPROVED;
            case PENDING -> MyHrOwnRecordIntent.Aspect.PENDING;
            case REJECTION -> MyHrOwnRecordIntent.Aspect.REJECTED;
            case REQUESTED_DATE -> MyHrOwnRecordIntent.Aspect.REQUESTED_DATE;
            case APPROVED_DATE -> MyHrOwnRecordIntent.Aspect.APPROVED_DATE;
            case EFFECTIVE_DATE -> MyHrOwnRecordIntent.Aspect.UPDATED;
        };
    }

    private static MyHrOwnRecordIntent.Intent documentRequestIntent(DocumentRequestQuestion question) {
        return switch (question) {
            case OFFER_LETTER_STATUS, COMPLETED_OFFER_LETTER_DOWNLOAD -> new MyHrOwnRecordIntent.Intent(
                    MyHrOwnRecordIntent.Kind.OWN, MyHrOwnRecordIntent.Subject.OFFER_LETTER,
                    MyHrOwnRecordIntent.Aspect.STATUS, null, RequestedDocumentType.OFFER_LETTER);
            case REJECTION_REASON -> new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.DOCUMENT_REQUEST, MyHrOwnRecordIntent.Aspect.REJECTION_REASON, null, null);
            case EMPLOYMENT_VERIFICATION_REQUEST_DATE -> new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.DOCUMENT_REQUEST, MyHrOwnRecordIntent.Aspect.REQUESTED_DATE, null,
                    RequestedDocumentType.EMPLOYMENT_VERIFICATION);
            default -> new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.DOCUMENT_REQUEST, MyHrOwnRecordIntent.Aspect.STATUS, null, null);
        };
    }

    /** Current reviewer queues only; historical count and navigation answers do not become a queue continuation. */
    private void rememberReviewQueue(Turn turn, VerifiedMyHrActor actor, DocumentRequestQueueQuestion question) {
        if (actor.role() == UserRole.EMPLOYEE || question == DocumentRequestQueueQuestion.ADMIN_COMPLETED
                || question == DocumentRequestQueueQuestion.HR_REVIEW_LOCATION
                || question == DocumentRequestQueueQuestion.ADMIN_REVIEW_LOCATION) return;
        turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", WaviTools.ReviewQueue.DOCUMENT_REQUESTS.name()));
    }

    private void rememberReviewQueue(Turn turn, VerifiedMyHrActor actor, ProfileChangeQueueQuestion question) {
        if (actor.role() == UserRole.EMPLOYEE || question == ProfileChangeQueueQuestion.ADMIN_APPROVED
                || question == ProfileChangeQueueQuestion.ADMIN_REJECTED
                || question == ProfileChangeQueueQuestion.HR_REVIEW_LOCATION
                || question == ProfileChangeQueueQuestion.REVIEW_LOCATION) return;
        turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", WaviTools.ReviewQueue.PROFILE_CHANGES.name()));
    }

    private void rememberReviewQueue(Turn turn, VerifiedMyHrActor actor, TimesheetQueueQuestion question) {
        if (actor.role() == UserRole.EMPLOYEE || question == TimesheetQueueQuestion.ADMIN_APPROVED
                || question == TimesheetQueueQuestion.ADMIN_REJECTED
                || question == TimesheetQueueQuestion.HR_REVIEW_LOCATION
                || question == TimesheetQueueQuestion.ADMIN_REVIEW_LOCATION) return;
        turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", WaviTools.ReviewQueue.TIMESHEETS.name()));
    }

    private MyHrChatResponse ownRecordReply(VerifiedMyHrActor actor, Turn turn, MyHrOwnRecordIntent.Intent intent,
                                             String answer) {
        log.info("{} provider=none own-record={} subject={} aspect={}", PROVIDER_USED_MARKER, intent.kind(),
                intent.subject(), intent.aspect());
        rememberOwnRequest(turn, intent, actor);
        return new MyHrChatResponse(answer);
    }

    /**
     * Answers a classified personal-record question only through the existing authorized lookups, each scoped
     * by the service to the verified actor's own records, assignments or organization. Null when the lookup
     * this needs is not available, so the remaining routes still apply.
     */
    private String ownRecordResponse(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Intent intent) {
        return switch (intent.kind()) {
            case OWN -> switch (intent.subject()) {
                case PROFILE_CHANGE -> intent.field() == null
                        ? ownProfileChangeAnswer(actor.userId(), intent.aspect())
                        : ownFieldChangeAnswer(actor.userId(), intent.field(), intent.aspect());
                case TIMESHEET -> ownTimesheetAnswer(actor, intent.aspect());
                case DOCUMENT_REQUEST, OFFER_LETTER -> ownDocumentAnswer(actor, intent);
                case PERSONAL_RECORD -> null;
            };
            case PENDING_CHECK -> actor.role() == UserRole.EMPLOYEE
                    ? ownPendingAnswer(actor.userId(), intent.subject())
                    : switch (intent.subject()) {
                        case TIMESHEET -> timesheetQueueResponse(actor, actor.userId(), TimesheetQueueQuestion.MY_QUEUE);
                        case PROFILE_CHANGE -> profileChangeQueueResponse(actor, actor.userId(), ProfileChangeQueueQuestion.MY_QUEUE);
                        case DOCUMENT_REQUEST, OFFER_LETTER, PERSONAL_RECORD -> documentRequestQueueResponse(
                                actor, actor.userId(), DocumentRequestQueueQuestion.MY_QUEUE);
                    };
            case OTHER_PERSON -> otherPersonAnswer(actor.role(), intent.subject());
            case QUEUE_COUNT -> queueCountAnswer(actor.role(), intent.subject());
        };
    }

    private String ownProfileChangeAnswer(String userId, MyHrOwnRecordIntent.Aspect aspect) {
        return switch (aspect) {
            case APPROVED_DATE -> formatProfileChangeDate(ProfileChangeDateQuestion.APPROVED_DATE,
                    profileChangeRequestService.mostRecentOwnHistory(userId));
            case REQUESTED_DATE -> formatProfileChangeDate(ProfileChangeDateQuestion.REQUESTED_DATE,
                    profileChangeRequestService.mostRecentOwnHistory(userId));
            case REJECTION_REASON -> formatProfileChangeRejectionReason(profileChangeRequestService.mostRecentOwnHistory(userId));
            default -> formatProfileChangeStatus(profileChangeRequestService.mostRecentOwnStatus(userId));
        };
    }

    private String formatProfileChangeRejectionReason(Optional<MyHrOwnProfileChangeHistory> result) {
        if (result.isEmpty()) return "I couldn't find a profile change request of yours in Waypoint.";
        MyHrOwnProfileChangeHistory history = result.get();
        if (history.status() == ProfileChangeStatus.REJECTED) {
            return "Your latest profile change request was rejected"
                    + (history.processedAt() == null ? "" : " on " + formatDate(history.processedAt()))
                    + ". " + REVIEWER_COMMENT_LOCATION;
        }
        if (history.status() == ProfileChangeStatus.PENDING) {
            return "Your latest profile change request is still pending, so it hasn't been rejected.";
        }
        return history.status() == ProfileChangeStatus.APPROVED
                ? "Your latest profile change request was approved, so it wasn't rejected."
                : "Your latest profile change request has no recorded status.";
    }

    private static final String REVIEWER_COMMENT_LOCATION =
            "I can't see the reviewer's comment; open Profile Change Requests to read it.";

    private String ownFieldChangeAnswer(String userId, ProfileChangeField field, MyHrOwnRecordIntent.Aspect aspect) {
        PhoneNumberChangeQuestion question = switch (aspect) {
            case PENDING -> PhoneNumberChangeQuestion.PENDING;
            case REJECTED, REJECTION_REASON -> PhoneNumberChangeQuestion.REJECTION;
            case APPROVED_DATE -> PhoneNumberChangeQuestion.APPROVED_DATE;
            case REQUESTED_DATE -> PhoneNumberChangeQuestion.REQUESTED_DATE;
            case UPDATED -> PhoneNumberChangeQuestion.EFFECTIVE_DATE;
            default -> PhoneNumberChangeQuestion.APPROVAL;
        };
        Optional<MyHrOwnProfileChangeHistory> history =
                profileChangeRequestService.mostRecentOwnHistoryForField(userId, field);
        String answer = formatFieldChangeHistory(fieldLabel(field), question, history);
        boolean rejected = history.map(request -> request.status() == ProfileChangeStatus.REJECTED).orElse(false);
        return aspect == MyHrOwnRecordIntent.Aspect.REJECTION_REASON && rejected
                ? answer + " " + REVIEWER_COMMENT_LOCATION : answer;
    }

    private String fieldLabel(ProfileChangeField field) {
        return switch (field) {
            case PHONE_NUMBER -> "phone number";
            case ADDRESS -> "address";
            case PERSONAL_EMAIL -> "personal email";
            case NAME -> "name";
            case DESIGNATION -> "designation";
        };
    }

    private String ownTimesheetAnswer(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Aspect aspect) {
        boolean admin = actor.role() == UserRole.COMPANY_ADMIN;
        return switch (aspect) {
            // The same answer as "My timesheet was rejected": the looked-up status, then how to resubmit.
            case REJECTED, REJECTION_REASON -> admin
                    ? workflowGuidance(WorkflowIntent.TIMESHEET_REJECTED, actor.role())
                    : formatRejectionStatusCheck(timesheetService.getMostRecentStatusForUser(actor.userId())) + " "
                    + workflowGuidance(WorkflowIntent.TIMESHEET_REJECTED, actor.role());
            case REQUESTED_DATE -> admin ? ADMIN_NO_TIMESHEET_SUBMISSIONS
                    : formatLatestTimesheetSubmission(timesheetService.getLatestSubmissionForUser(actor.userId()));
            case APPROVED_DATE -> {
                if (admin) yield ADMIN_NO_OWN_TIMESHEET_STATUS;
                Optional<MyHrRecentTimesheetStatus> latest = timesheetService.getMostRecentStatusForUser(actor.userId());
                yield latest.isEmpty() ? formatRecentTimesheetStatus(latest) : formatRecentTimesheetStatus(latest)
                        + " I can't see when it was reviewed; Submitted Timesheets on Timesheets shows each period's details.";
            }
            // Company Admin accounts never submit timesheets: say so rather than "no timesheet found".
            default -> admin ? ADMIN_NO_OWN_TIMESHEET_STATUS
                    : formatRecentTimesheetStatus(timesheetService.getMostRecentStatusForUser(actor.userId()));
        };
    }

    /**
     * Employees: their own request history. HR: the requests they made for themselves, through the existing
     * "My requests" worklist. Company Admins don't request documents for themselves.
     */
    private String ownDocumentAnswer(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Intent intent) {
        if (documentRequestService == null) return null;
        if (actor.role() == UserRole.COMPANY_ADMIN) return ADMIN_NO_OWN_DOCUMENT_REQUESTS;
        if (actor.role() == UserRole.HR) return hrOwnDocumentAnswer(actor.userId(), intent.aspect());
        List<MyHrOwnDocumentRequestHistory> history = documentRequestService.listOwnHistory(actor.userId());
        if (intent.aspect() == MyHrOwnRecordIntent.Aspect.REJECTION_REASON) {
            return formatDocumentRequestHistory(DocumentRequestQuestion.REJECTION_REASON, history);
        }
        RequestedDocumentType type = intent.documentType();
        if (type == RequestedDocumentType.OFFER_LETTER) {
            return formatDocumentRequestHistory(DocumentRequestQuestion.OFFER_LETTER_STATUS, history);
        }
        // Mid-sentence: "Your latest employment verification letter request"; "I-983" keeps its capitals.
        String label = type == null ? "document" : type == RequestedDocumentType.I983 ? readableDocumentType(type)
                : readableDocumentType(type).toLowerCase(Locale.ROOT);
        Optional<MyHrOwnDocumentRequestHistory> latest = type == null ? history.stream().findFirst()
                : latestOfType(history, type, label.toLowerCase(Locale.ROOT).replace(" letter", ""));
        if (latest.isEmpty()) return "I couldn't find " + article(label) + " " + label + " request of yours in Waypoint.";
        if (intent.aspect() == MyHrOwnRecordIntent.Aspect.REQUESTED_DATE) {
            return latest.get().requestedAt() == null
                    ? "I found your latest " + label + " request, but no submission date was recorded."
                    : "You submitted your latest " + label + " request on " + formatDate(latest.get().requestedAt()) + ".";
        }
        return documentStatusAnswer("Your latest " + label + " request", latest.get());
    }

    private String hrOwnDocumentAnswer(String userId, MyHrOwnRecordIntent.Aspect aspect) {
        DocumentRequestPageResponse page = documentRequestService.list(userId, 0, 1, null, null, null, null, "mine");
        List<DocumentRequestResponse> items = page == null || page.items() == null ? List.of() : page.items();
        if (items.isEmpty()) {
            return "I couldn't find a document request you made for yourself in Waypoint. Requests you sent to "
                    + "employees are under Request Documents, My requests.";
        }
        DocumentRequestResponse latest = items.getFirst();
        String title = latest.requestTitle() != null && !latest.requestTitle().isBlank() ? latest.requestTitle()
                : latest.documentType() == null ? "Untitled document request" : readableDocumentType(latest.documentType());
        String answer = "Your latest document request for yourself (" + title + ") "
                + (latest.status() == null ? "has no recorded status" : "is " + readableStatus(latest.status()))
                + (latest.requestedAt() == null ? "." : ", requested on " + formatDate(latest.requestedAt()) + ".");
        if (aspect == MyHrOwnRecordIntent.Aspect.REJECTION_REASON && latest.status() == DocumentRequestStatus.REJECTED) {
            answer += latest.rejectionReason() == null || latest.rejectionReason().isBlank()
                    ? " Waypoint has no rejection reason recorded." : " Recorded reason: " + latest.rejectionReason().trim();
        }
        return answer;
    }

    private String article(String noun) {
        return noun.isEmpty() || "aeiouAEIOU".indexOf(noun.charAt(0)) < 0 ? "a" : "an";
    }

    /** An Employee's own pending requests; a reviewer's "pending" questions are answered from their queue. */
    private String ownPendingAnswer(String userId, MyHrOwnRecordIntent.Subject subject) {
        return switch (subject) {
            case PROFILE_CHANGE -> {
                // The own-status lookup reports a pending request ahead of any decided one.
                Optional<ProfileChangeStatus> status = profileChangeRequestService.mostRecentOwnStatus(userId);
                yield status.isEmpty() ? "You don't have any profile change requests in Waypoint."
                        : status.get() == ProfileChangeStatus.PENDING
                        ? "Yes, you have a pending profile change request. Open Profile Change Requests to see its details."
                        : "You don't have a pending profile change request. Your latest one was "
                        + status.get().name().toLowerCase(Locale.ROOT) + ".";
            }
            case TIMESHEET -> formatRecentTimesheetStatus(timesheetService.getMostRecentStatusForUser(userId));
            case OFFER_LETTER -> documentRequestService == null ? null : formatDocumentRequestHistory(
                    DocumentRequestQuestion.OFFER_LETTER_STATUS, documentRequestService.listOwnHistory(userId));
            case DOCUMENT_REQUEST, PERSONAL_RECORD -> {
                if (documentRequestService == null) yield null;
                List<MyHrOwnDocumentRequestHistory> open = documentRequestService.listOwnHistory(userId).stream()
                        .filter(request -> request.status() == DocumentRequestStatus.PENDING
                                || request.status() == DocumentRequestStatus.SUBMITTED
                                || request.status() == DocumentRequestStatus.RETURNED_FOR_CORRECTION)
                        .toList();
                yield open.isEmpty()
                        ? "You don't have any open document requests: none are pending, awaiting review or returned for correction."
                        : "You have " + open.size() + " open document " + plural(open.size(), "request") + ". "
                        + documentStatusAnswer("The most recent", open.getFirst());
            }
        };
    }

    private String otherPersonAnswer(UserRole role, MyHrOwnRecordIntent.Subject subject) {
        if (role == UserRole.EMPLOYEE) return OTHER_PERSON_REPLY;
        boolean admin = role == UserRole.COMPANY_ADMIN;
        return OTHER_PERSON_REPLY + " " + switch (subject) {
            case TIMESHEET -> admin ? "Open Timesheets to review employees' timesheets."
                    : "Open Timesheets and select Team Timesheets to review your active direct reports' timesheets.";
            case PROFILE_CHANGE -> "Open Profile Change Requests to review the requests " + (admin
                    ? "in your organization." : "assigned to you.");
            case DOCUMENT_REQUEST, OFFER_LETTER -> "Open Request Documents to work with employees' document requests.";
            case PERSONAL_RECORD -> "Open Employee Management to see employee details you're permitted to view.";
        };
    }

    private String queueCountAnswer(UserRole role, MyHrOwnRecordIntent.Subject subject) {
        String items = switch (subject) {
            case TIMESHEET -> "timesheets";
            case PROFILE_CHANGE -> "profile change requests";
            default -> "document requests";
        };
        if (role == UserRole.EMPLOYEE) {
            return switch (subject) {
                case TIMESHEET -> TIMESHEET_QUEUE_REVIEWERS_ONLY;
                case PROFILE_CHANGE -> PROFILE_CHANGE_QUEUE_REVIEWERS_ONLY;
                default -> DOCUMENT_QUEUE_REVIEWERS_ONLY;
            };
        }
        return "I can't count that exactly. I can tell you how many " + items + " are waiting for your approval"
                + (role == UserRole.COMPANY_ADMIN
                ? ", or how many are pending, approved or rejected in your organization."
                : " among those assigned to you.")
                + " Try \"How many " + items + " are waiting for my approval?\"";
    }

    private String plural(int count, String noun) {
        return count == 1 ? noun : noun + "s";
    }

    private String countVerb(int count) {
        return count == 1 ? "is" : "are";
    }

    /**
     * An own-record lookup asked as it is, or politely ("Can you tell me my employee ID?" is asked as
     * "what is my employee ID"). The lookup patterns themselves stay exact.
     */
    private boolean matchesLookup(Pattern lookup, String message) {
        String normalized = normalizeQuestion(message);
        if (lookup.matcher(normalized).matches()) return true;
        Matcher polite = POLITE_LOOKUP_PREFIX.matcher(normalized);
        return polite.lookingAt() && lookup.matcher("what is " + normalized.substring(polite.end())).matches();
    }

    private String normalizeQuestion(String message) {
        // Misspelled domain words ("tmesheet", "titel") are read as the words they are one edit from, so every route
        // sees the same wording a careful typist would have used.
        return message == null ? "" : WaviSpelling.correct(message.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[?.!]+$", "").replaceAll("\\s+", " "));
    }

    private String formatProfileChangeStatus(Optional<ProfileChangeStatus> status) {
        return status.map(value -> "Your profile change request status is " + value.name() + ".")
                .orElse("No profile change request found.");
    }

    /** Dates of the signed-in user's own latest profile change request, whichever field it changes. */
    private String formatProfileChangeDate(ProfileChangeDateQuestion question,
                                           Optional<MyHrOwnProfileChangeHistory> result) {
        if (result.isEmpty()) {
            return "I couldn't find a profile change request of yours in Waypoint.";
        }
        MyHrOwnProfileChangeHistory history = result.get();
        ProfileChangeStatus status = history.status();
        return switch (question) {
            case REQUESTED_DATE -> history.requestedAt() == null
                    ? "I found your latest profile change request, but no submission date was recorded."
                    : "You submitted your latest profile change request on " + formatDate(history.requestedAt()) + ".";
            case APPROVED_DATE -> status == ProfileChangeStatus.PENDING
                    ? "Your latest profile change request is still pending, so it has not been approved."
                    : status == ProfileChangeStatus.REJECTED
                    ? "Your latest profile change request was rejected"
                    + (history.processedAt() == null ? "" : " on " + formatDate(history.processedAt()))
                    + ", so it was not approved."
                    : status != ProfileChangeStatus.APPROVED
                    ? "Your latest profile change request has no recorded status."
                    : history.processedAt() == null
                    ? "Your latest profile change request is marked approved, but no approval date was recorded."
                    : "Your latest profile change request was approved on " + formatDate(history.processedAt()) + ".";
        };
    }

    /** Keeps request submission, review, and the actual applied profile update distinct. */
    private String formatPhoneNumberChangeHistory(PhoneNumberChangeQuestion question,
                                                   Optional<MyHrOwnProfileChangeHistory> result) {
        return formatFieldChangeHistory("phone number", question, result);
    }

    /** The same answers for any single profile field: "phone number", "address", "name" or "personal email". */
    private String formatFieldChangeHistory(String label, PhoneNumberChangeQuestion question,
                                            Optional<MyHrOwnProfileChangeHistory> result) {
        if (result.isEmpty()) {
            return "I couldn't find a " + label + " change request of yours in Waypoint.";
        }
        MyHrOwnProfileChangeHistory history = result.get();
        ProfileChangeStatus status = history.status();
        return switch (question) {
            case APPROVAL -> status == ProfileChangeStatus.APPROVED
                    ? "Your " + label + " change was approved" + onDate(history.processedAt()) + "."
                    : status == ProfileChangeStatus.PENDING
                    ? "Your " + label + " update is still pending" + requestedOnDate(history.requestedAt()) + "."
                    : status == ProfileChangeStatus.REJECTED
                    ? "Your " + label + " change was rejected" + onDate(history.processedAt()) + "."
                    : "Your latest " + label + " change has no recorded status.";
            case PENDING -> status == ProfileChangeStatus.PENDING
                    ? "Your " + label + " update is pending" + requestedOnDate(history.requestedAt()) + "."
                    : status == ProfileChangeStatus.APPROVED
                    ? "Your latest " + label + " change was approved" + onDate(history.processedAt()) + "; it is not pending."
                    : status == ProfileChangeStatus.REJECTED
                    ? "Your latest " + label + " change was rejected" + onDate(history.processedAt()) + "; it is not pending."
                    : "Your latest " + label + " change has no recorded status.";
            case REJECTION -> status == ProfileChangeStatus.REJECTED
                    ? "Your " + label + " change was rejected" + onDate(history.processedAt()) + "."
                    : status == ProfileChangeStatus.APPROVED
                    ? "Your latest " + label + " change was approved" + onDate(history.processedAt()) + "; it was not rejected."
                    : status == ProfileChangeStatus.PENDING
                    ? "Your " + label + " update is pending" + requestedOnDate(history.requestedAt()) + "; it has not been rejected."
                    : "Your latest " + label + " change has no recorded status.";
            case REQUESTED_DATE -> history.requestedAt() == null
                    ? "I found your " + label + " change request, but no submission date was recorded."
                    : "You requested a " + label + " change on " + formatDate(history.requestedAt()) + ".";
            case APPROVED_DATE -> status != ProfileChangeStatus.APPROVED
                    ? status == ProfileChangeStatus.PENDING
                    ? "Your " + label + " update is still pending, so it has not been approved."
                    : status == ProfileChangeStatus.REJECTED
                    ? "Your " + label + " change was rejected, so it was not approved."
                    : "Your latest " + label + " change has no recorded status."
                    : history.processedAt() == null
                    ? "Your " + label + " change is marked approved, but no approval date was recorded."
                    : "Your " + label + " change was approved on " + formatDate(history.processedAt()) + ".";
            case EFFECTIVE_DATE -> status != ProfileChangeStatus.APPROVED
                    ? "Your " + label + " has not been updated from this request because it is "
                    + (status == null ? "missing a recorded status." : status.name().toLowerCase(Locale.ROOT) + ".")
                    : history.effectiveAt() == null
                    ? "Your " + label + " change was approved, but no confirmed effective update date was recorded."
                    : "Your " + label + " was last updated on " + formatDate(history.effectiveAt()) + ".";
        };
    }

    /** Deterministic employee-only request history that never returns files, attachments, or descriptions. */
    private String formatDocumentRequestHistory(DocumentRequestQuestion question,
                                                List<MyHrOwnDocumentRequestHistory> history) {
        return switch (question) {
            case LATEST_STATUS -> history.isEmpty()
                    ? "I couldn't find a document request of yours in Waypoint."
                    : documentStatusAnswer("Your latest document request", history.getFirst());
            case OFFER_LETTER_STATUS -> latestOfType(history, RequestedDocumentType.OFFER_LETTER, "offer letter")
                    .map(request -> documentStatusAnswer("Your latest offer letter request", request))
                    .orElse("I couldn't find an offer letter request of yours in Waypoint.");
            case REJECTION_REASON -> history.stream()
                    .filter(request -> request.status() == DocumentRequestStatus.REJECTED)
                    .findFirst()
                    .map(this::rejectionAnswer)
                    .orElse("I couldn't find a rejected document request of yours in Waypoint.");
            case EMPLOYMENT_VERIFICATION_REQUEST_DATE -> latestOfType(
                    history.stream().filter(MyHrOwnDocumentRequestHistory::requestedByMe).toList(),
                    RequestedDocumentType.EMPLOYMENT_VERIFICATION, "employment verification")
                    .map(request -> request.requestedAt() == null
                            ? "I found your employment verification letter request, but no submission date was recorded."
                            : "You submitted your employment verification letter request on " + formatDate(request.requestedAt()) + ".")
                    .orElse("I couldn't find an employment verification letter request you submitted in Waypoint.");
            case REQUESTED_DOCUMENTS -> requestedDocumentsAnswer(history);
            case COMPLETED_OFFER_LETTER_DOWNLOAD -> latestOfType(history, RequestedDocumentType.OFFER_LETTER, "offer letter")
                    .map(this::completedOfferLetterDownloadAnswer)
                    .orElse("I couldn't find an offer letter request of yours in Waypoint.");
            case ONBOARDING_PENDING -> latestOnboardingRequest(history)
                    .map(request -> request.status() == DocumentRequestStatus.PENDING
                            ? "Your latest onboarding document request is pending" + requestedOnDate(request.requestedAt()) + "."
                            : documentStatusAnswer("Your latest onboarding document request", request))
                    .orElse("I couldn't find an onboarding document request of yours in Waypoint.");
        };
    }

    private Optional<MyHrOwnDocumentRequestHistory> latestOfType(List<MyHrOwnDocumentRequestHistory> history,
                                                                   RequestedDocumentType type, String legacyTitle) {
        return history.stream().filter(request -> request.documentType() == type
                        || (request.documentType() == null && contains(request.requestTitle(), legacyTitle)))
                .findFirst();
    }

    private Optional<MyHrOwnDocumentRequestHistory> latestOnboardingRequest(List<MyHrOwnDocumentRequestHistory> history) {
        return history.stream().filter(request -> "PRE_EMPLOYMENT_ONBOARDING".equals(request.category()))
                .findFirst();
    }

    private String documentStatusAnswer(String subject, MyHrOwnDocumentRequestHistory request) {
        if (request.status() == null) return subject + " has no recorded status.";
        return switch (request.status()) {
            case PENDING -> subject + " is pending" + requestedOnDate(request.requestedAt()) + ".";
            case SUBMITTED -> subject + " was submitted and is awaiting review" + requestedOnDate(request.requestedAt())
                    + "." + signedDocumentSentence(request);
            case RETURNED_FOR_CORRECTION -> subject + " was returned for correction"
                    + onRecordedDate(request.reviewedAt(), "review") + "." + submissionDateSentence(request.requestedAt());
            case APPROVED -> subject + " was approved" + onRecordedDate(request.approvedAt(), "approval")
                    + "." + submissionDateSentence(request.requestedAt());
            case FULFILLED -> subject + " was completed" + onRecordedDate(request.fulfilledAt(), "completion")
                    + "." + signedDocumentSentence(request) + submissionDateSentence(request.requestedAt());
            case REJECTED -> subject + " was rejected" + onRecordedDate(request.rejectedAt(), "rejection")
                    + "." + submissionDateSentence(request.requestedAt());
            case CANCELLED -> subject + " was cancelled" + requestedOnDate(request.requestedAt()) + ".";
        };
    }

    private String onRecordedDate(java.time.LocalDateTime date, String kind) {
        return date == null ? ", but no " + kind + " date was recorded" : " on " + formatDate(date);
    }

    private String submissionDateSentence(java.time.LocalDateTime date) {
        return date == null ? " No request submission date was recorded."
                : " It was requested on " + formatDate(date) + ".";
    }

    private String signedDocumentSentence(MyHrOwnDocumentRequestHistory request) {
        if (!request.signedDocumentRecorded()) return "";
        return request.signatureSubmittedAt() == null
                ? " A signed document is recorded."
                : " A signed document was recorded on " + formatDate(request.signatureSubmittedAt()) + ".";
    }

    private String rejectionAnswer(MyHrOwnDocumentRequestHistory request) {
        String prefix = "Your most recent rejected document request" + rejectedOnDate(request.rejectedAt()) + ".";
        if (request.rejectionReason() == null || request.rejectionReason().isBlank()) {
            return prefix + " Waypoint has no rejection reason recorded.";
        }
        return prefix + " Recorded reason: " + request.rejectionReason().trim();
    }

    private String requestedDocumentsAnswer(List<MyHrOwnDocumentRequestHistory> history) {
        List<MyHrOwnDocumentRequestHistory> requestedByMe = history.stream()
                .filter(MyHrOwnDocumentRequestHistory::requestedByMe).limit(10).toList();
        if (requestedByMe.isEmpty()) return "I couldn't find any document requests you submitted in Waypoint.";
        String items = requestedByMe.stream().map(request -> {
            String title = request.requestTitle();
            if (title == null || title.isBlank()) title = request.documentType() == null ? "Untitled document request"
                    : readableDocumentType(request.documentType());
            return title + " (" + readableStatus(request.status()) + ")";
        }).collect(java.util.stream.Collectors.joining("; "));
        return "You requested: " + items + (history.stream().filter(MyHrOwnDocumentRequestHistory::requestedByMe).count() > 10
                ? ". Showing your 10 most recent requests." : ".");
    }

    private String completedOfferLetterDownloadAnswer(MyHrOwnDocumentRequestHistory request) {
        if (request.status() != DocumentRequestStatus.FULFILLED) {
            return documentStatusAnswer("Your latest offer letter request", request)
                    + " It is not a completed offer letter available for download yet. Once it is completed, it appears "
                    + "on your Offer Letter request in Document Requests with Preview and Download buttons.";
        }
        if (!request.downloadableCompletedDocument()) {
            return "Your offer letter request is marked completed, but no downloadable document is recorded. Open Document Requests to check the request details.";
        }
        return "Your completed offer letter is available in Document Requests. Open your Offer Letter request and use Preview or Download.";
    }

    private boolean contains(String value, String fragment) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(fragment);
    }

    private String readableDocumentType(RequestedDocumentType type) {
        return switch (type) {
            case I983 -> "I-983";
            case EMPLOYMENT_VERIFICATION -> "Employment verification letter";
            case EXPERIENCE_LETTER -> "Experience letter";
            case EMPLOYMENT_LETTER -> "Employment letter";
            case OFFER_LETTER -> "Offer letter";
            case OTHER -> "Document request";
        };
    }

    private String readableStatus(DocumentRequestStatus status) {
        return status == null ? "no recorded status" : switch (status) {
            case PENDING -> "pending";
            case SUBMITTED -> "submitted";
            case RETURNED_FOR_CORRECTION -> "returned for correction";
            case APPROVED -> "approved";
            case FULFILLED -> "completed";
            case REJECTED -> "rejected";
            case CANCELLED -> "cancelled";
        };
    }

    private String rejectedOnDate(java.time.LocalDateTime date) {
        return date == null ? ", but no rejection date was recorded" : " was rejected on " + formatDate(date);
    }

    private String onDate(java.time.LocalDateTime date) {
        return date == null ? ", but no decision date was recorded" : " on " + formatDate(date);
    }

    private String requestedOnDate(java.time.LocalDateTime date) {
        return date == null ? ", but no submission date was recorded" : ", requested on " + formatDate(date);
    }

    private String formatDate(java.time.LocalDateTime date) {
        return date.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private ProfileQuestion classifyProfileQuestion(String message) {
        if (matchesLookup(PROFILE_DETAILS_QUESTION, message)) return ProfileQuestion.PROFILE;
        if (matchesLookup(NAME_QUESTION, message)) return nameField(message);
        if (matchesLookup(EMPLOYEE_ID_QUESTION, message)) return ProfileQuestion.EMPLOYEE_ID;
        if (matchesLookup(JOB_TITLE_QUESTION, message)) return ProfileQuestion.JOB_TITLE;
        if (matchesLookup(DEPARTMENT_QUESTION, message)) return ProfileQuestion.DEPARTMENT;
        if (matchesLookup(WORK_EMAIL_QUESTION, message)) return ProfileQuestion.WORK_EMAIL;
        if (matchesLookup(REPORTING_MANAGER_QUESTION, message)) return ProfileQuestion.REPORTING_MANAGER;
        return null;
    }

    /** Which part of the name a name question asks for: the qualifier is part of the asked field, not a new intent. */
    private ProfileQuestion nameField(String message) {
        for (String token : normalizeQuestion(message).split("[^a-z]+")) {
            if (token.equals("last") || token.equals("surname") || token.equals("family")) return ProfileQuestion.LAST_NAME;
            if (token.equals("first") || token.equals("given")) return ProfileQuestion.FIRST_NAME;
        }
        return ProfileQuestion.NAME;
    }

    /**
     * A fixed reply to a standalone greeting, thanks or farewell, or to a question about what Wavi or
     * Waypoint is; null for any other message.
     */
    private String smallTalkReply(String message, UserRole role, String organizationId) {
        String normalized = WAVI_ADDRESSED.matcher(normalizeQuestion(message)).replaceAll("");
        if (CAPABILITIES_QUESTION.matcher(normalized).matches()) return capabilitiesReply(role, organizationId);
        if (ABOUT_WAYPOINT_QUESTION.matcher(normalized).matches()) return ABOUT_WAYPOINT_REPLY;
        if (HELLO_GREETING.matcher(normalized).matches()) return "Hello! How can I help you today?";
        if (HOW_ARE_YOU.matcher(normalized).matches()) return "I'm doing well, thanks for asking! How can I help you today?";
        if (HI_GREETING.matcher(normalized).matches()) return "Hi there! How can I help you today?";
        Matcher timeOfDay = TIME_OF_DAY_GREETING.matcher(normalized);
        if (timeOfDay.matches()) return "Good " + timeOfDay.group(1) + "! How can I help you today?";
        if (THANKS.matcher(normalized).matches()) return "You're welcome!";
        if (FAREWELL.matcher(normalized).matches()) return "Goodbye! Have a great day.";
        return null;
    }

    /**
     * Only what this backend actually answers: the lookups in {@link #respond}, the workflow
     * guidance for the verified role, and company policy only when grounding can reach an approved
     * provider.
     */
    private String capabilitiesReply(UserRole role, String organizationId) {
        boolean policyAnswers = knowledgeGroundingEnabled && knowledgeRetrievalService != null
                && candidatePolicyDestination(organizationId) != PolicyDestination.NONE;
        boolean reviewer = role == UserRole.HR || role == UserRole.COMPANY_ADMIN;
        return "I'm Wavi, Waypoint's assistant. Here's what I can do:\n"
                + "- Look up your own Waypoint record: your employee ID, job title, reporting manager, a profile "
                + "summary"
                + (role == UserRole.COMPANY_ADMIN ? "" : ", your latest profile change request status, including "
                        + "phone number, address, name or personal email changes, and your document requests") + ". Try "
                + "\"What is my employee ID?\"\n"
                + WaviCapabilities.describe(WaviTools.catalog(new ToolAnswers()), role)
                + (reviewer ? "- Count the timesheets, profile change requests and document requests waiting for your "
                + "approval. Try \"How many timesheets are waiting for my approval?\"\n" : "")
                + "- Explain Waypoint workflows for your role and point you to the right page: timesheets, profile "
                + "change requests, document requests and onboarding documents.\n"
                + (policyAnswers
                ? "- Answer company-policy questions only from HR-approved policy documents, with sources. If no "
                        + "approved document covers your question, I'll say so.\n"
                : "- Company-policy answers aren't turned on for Wavi here, so please check exact policies, "
                        + "deadlines and amounts with HR.\n")
                + "- Help with work-related writing, such as a message to your manager or team.\n"
                + "I only help with Waypoint and company topics, not general questions such as programming, "
                + "news or entertainment.\n"
                + "I can't approve, reject, submit, sign or change anything for you, or show records outside your "
                + "authorized scope, leave balances or pay.";
    }

    /**
     * A workflow how-to intent only when exactly one workflow matches and the message has no policy,
     * timing or status terms; those questions keep going to company-policy retrieval.
     */
    private WorkflowIntent classifyWorkflowIntent(String message) {
        String normalized = normalizeQuestion(message);
        if (MyHrKnowledgeGrounding.isGroupEligibilityQuestion(normalized)) return null;
        // Where to find something in Waypoint is answered with its location, even when it mentions a
        // status; questions about a policy keep going to company-policy retrieval.
        WorkflowIntent location = POLICY_REFERENCE.matcher(normalized).find() ? null : classifyLocationIntent(normalized);
        if (location != null) return location;
        if (WORKFLOW_POLICY_TERMS.matcher(normalized).find()) return null;
        // The existing Wavy shortcut uses this name for the timesheet review workflow.
        if (TIMESHEET_EXPRESS_CLEARANCE.matcher(normalized).find()) {
            return WorkflowIntent.TIMESHEET_REVIEW;
        }
        if (DOCUMENT_APPROVAL_WORKFLOW.matcher(normalized).find()) return WorkflowIntent.DOCUMENT_APPROVAL;
        if (OWN_REJECTED_TIMESHEET.matcher(normalized).find()) return WorkflowIntent.TIMESHEET_REJECTED;
        boolean changeAction = WORKFLOW_CHANGE_ACTION.matcher(normalized).find();
        boolean correction = changeAction || TIMESHEET_RESUBMIT_ACTION.matcher(normalized).find();
        boolean review = TIMESHEET_REVIEW_ACTION.matcher(normalized).find();
        boolean timesheet = TIMESHEET_SUBJECT.matcher(normalized).find()
                && (correction || review || TIMESHEET_ENTRY_ACTION.matcher(normalized).find());
        boolean profile = changeAction && PROFILE_SUBJECT.matcher(normalized).find();
        boolean offerLetter = OFFER_LETTER.matcher(normalized).find();
        boolean document = offerLetter || DOCUMENT_REQUEST.matcher(normalized).find();
        if ((timesheet ? 1 : 0) + (profile ? 1 : 0) + (document ? 1 : 0) != 1) return null;
        // Fixing a rejected timesheet is a correction, even though "reject" is also a review verb.
        if (timesheet) return correction ? WorkflowIntent.TIMESHEET_CORRECTION
                : review ? WorkflowIntent.TIMESHEET_REVIEW : WorkflowIntent.TIMESHEET_ENTRY;
        if (profile) return WorkflowIntent.PROFILE_CHANGE;
        if (offerLetter) {
            // Downloading, viewing or signing an existing letter is not a request for a new one.
            return DOCUMENT_RETRIEVAL_ACTION.matcher(normalized).find()
                    ? WorkflowIntent.OFFER_LETTER_COPY : WorkflowIntent.OFFER_LETTER;
        }
        return WorkflowIntent.DOCUMENT_REQUEST;
    }

    /** Questions about where to check a status or upload documents; null for anything else. */
    private WorkflowIntent classifyLocationIntent(String normalized) {
        if (!LOCATION_QUESTION.matcher(normalized).find()) return null;
        boolean lookUp = LOOK_UP_ACTION.matcher(normalized).find();
        boolean status = STATUS_TERMS.matcher(normalized).find();
        // "Where can I see my team's submitted timesheets?" is the review page, not the asker's own history. Only where
        // or look-up questions: "How long do I have to approve timesheets?" stays a policy question.
        if ((lookUp || normalized.startsWith("where")) && TIMESHEET_SUBJECT.matcher(normalized).find()
                && asksAboutTeamTimesheets(normalized)) {
            return WorkflowIntent.TIMESHEET_REVIEW;
        }
        if (lookUp && status && TIMESHEET_SUBJECT.matcher(normalized).find()) return WorkflowIntent.TIMESHEET_STATUS;
        // "Where do I see my document requests?" asks where the list is, even without a status word.
        if (lookUp && (status || normalized.startsWith("where")) && DOCUMENT_REQUEST_SUBJECT.matcher(normalized).find()) {
            return WorkflowIntent.DOCUMENT_REQUEST_STATUS;
        }
        if (UPLOAD_ACTION.matcher(normalized).find() && ONBOARDING_SUBJECT.matcher(normalized).find()) {
            return WorkflowIntent.ONBOARDING_UPLOAD;
        }
        return null;
    }

    private WorkflowIntent classifyTimesheetHistoryNavigation(String normalized) {
        return TIMESHEET_HISTORY_NAVIGATION.matcher(normalized).find() && !asksAboutTeamTimesheets(normalized)
                ? WorkflowIntent.TIMESHEET_STATUS : null;
    }

    /**
     * Whose timesheets a question is about, then what it asks: the team's or reports' timesheets, or reviewing,
     * approving or rejecting timesheets that are not named as the asker's own, is reviewer work, answered for the
     * asker's verified role. Outcome words ("approved", "rejected") describe the asker's own status and are not
     * reviewer actions; "my submitted timesheets" and "the hours I sent" stay the asker's own history.
     */
    private static boolean asksAboutTeamTimesheets(String normalized) {
        return TEAM_TIMESHEET_SUBJECT.matcher(normalized).find()
                || (TIMESHEET_REVIEW_ACTION.matcher(normalized).find() && !OWN_TIMESHEETS.matcher(normalized).find());
    }

    /** Only what each role can do in the existing pages; null for a role without MyHR chat access. */
    private String workflowGuidance(WorkflowIntent intent, UserRole role) {
        if (role != UserRole.EMPLOYEE && role != UserRole.HR && role != UserRole.COMPANY_ADMIN) return null;
        boolean employee = role == UserRole.EMPLOYEE;
        boolean admin = role == UserRole.COMPANY_ADMIN;
        return switch (intent) {
            case TIMESHEET_ENTRY -> admin
                    ? "Company Admin accounts can review timesheets but can't submit one. Open Timesheets to review "
                    + "submissions across the organization."
                    : "Open Timesheets" + (employee ? "" : ", stay on the My Timesheets tab,")
                    + " and choose Enter Timesheet: pick the date range, enter your daily hours, review the period and "
                    + "submit it to your reporting manager. You can keep changing a draft until you submit it, and "
                    + "Submitted Timesheets shows the status of each period you've submitted.";
            case TIMESHEET_CORRECTION -> admin
                    ? "Company Admin accounts don't correct timesheets themselves. To have hours corrected, reject the "
                    + "submitted timesheet with a reason on Timesheets; the employee can then correct the hours and "
                    + "resubmit it."
                    : "On Timesheets" + (employee ? "" : " (My Timesheets tab)") + ": a draft can be changed from "
                    + "Enter Timesheet until you submit it. If a timesheet was rejected, review the feedback, correct "
                    + "the hours and resubmit it. To correct hours you already submitted, enter the same dates again; "
                    + "Waypoint asks you to confirm the re-edit and requires a reason for change and the approved "
                    + "changed timesheet.";
            case TIMESHEET_REVIEW -> employee
                    ? "Employees don't review timesheets. Your reporting manager reviews the timesheets you submit, and "
                    + "Submitted Timesheets on Timesheets shows the status of each one."
                    : (admin
                    ? "Open Timesheets to see submitted timesheets across the organization."
                    : "Open Timesheets and select the Team Timesheets tab to see your active direct reports' "
                    + "submissions; you can review only your active direct reports.")
                    + " Only SUBMITTED timesheets can be approved or rejected, and rejecting one requires a reason so "
                    + "the employee can correct and resubmit it.";
            case PROFILE_CHANGE -> admin
                    ? "Company Admin accounts don't have an employee profile to change through Profile Change Requests. "
                    + "Open Profile Change Requests to review employees' and HR's requests and approve or reject them."
                    : "Open Profile Change Requests and submit a request: choose the field (name, personal email, phone "
                    + "number or address), enter the new value and, optionally, a reason. Your profile is updated once "
                    + "the request is approved. You can have one pending request per field."
                    + (employee ? "" : " You also review employees' profile change requests on the same page.");
            case OFFER_LETTER -> admin
                    ? "Company Admin accounts don't request offer letters for themselves. Offer letters prepared by HR "
                    + "wait for your approval in Document Approvals; approving one allows it to be sent to the "
                    + "employee. To ask an employee for documents, use Request Documents and choose Request from "
                    + "employee."
                    : "Open Request Documents and choose " + (employee ? "Request a document" : "Request for myself")
                    + ", then select Offer Letter. Add a reason and attach the six required onboarding documents: "
                    + "Resume, Work Authorization Copy, Passport Copy (complete pages), I-94, Driver's License Copy and "
                    + "SSN Copy. The request goes to your reporting manager. Once the letter is prepared and approved by "
                    + "a Company Admin, you'll be asked to sign it and return it in Waypoint. You can track the request "
                    + "on Request Documents."
                    + (employee ? "" : " To prepare an offer letter an employee requested, open their request in "
                    + "Request Documents; a Company Admin must approve it before it can be sent.");
            case DOCUMENT_REQUEST -> admin
                    ? "Company Admin accounts don't request documents for themselves. Use Request Documents and choose "
                    + "Request from employee to request documents from an employee, and Document Approvals to approve "
                    + "documents prepared by HR before they're sent."
                    : employee
                    ? "Open Request Documents and choose Request a document. Select the document type, give a reason and "
                    + "follow the instructions shown for that type. The request goes to your reporting manager, and you "
                    + "can track it on Request Documents."
                    : "Open Request Documents and choose Request for myself to request a document for yourself, or "
                    + "Request from employee to request documents from an employee. Select the document type and follow "
                    + "the instructions shown for it.";
            case DOCUMENT_APPROVAL -> admin
                    ? "Open Document Approvals. Review the document HR submitted for the employee, then choose Approve "
                    + "Document (or Approve Offer Letter). Approval lets HR send it to the employee; rejecting it requires "
                    + "a reason and returns it to HR for correction."
                    : employee
                    ? "Employees cannot approve document requests. HR prepares the requested document, and a Company Admin "
                    + "reviews and approves it before HR can send it to you."
                    : "Open Request Documents and prepare the employee's document. Submit it for Company Admin approval; "
                    + "after it is approved, you can send it to the employee.";
            // The reviewer's reason and the hours are only on the timesheet; respond() adds the looked-up status.
            case TIMESHEET_REJECTED -> admin
                    ? "Company Admin accounts don't submit timesheets, so there's no timesheet of yours to resubmit. "
                    + "Open Timesheets to see submitted timesheets across the organization with their status; an "
                    + "employee whose timesheet was rejected corrects and resubmits it from their own Timesheets page."
                    : "I can't see the reviewer's reason or your hours; those are on the timesheet itself. Open "
                    + "Timesheets" + (employee ? "" : ", stay on the My Timesheets tab,") + " and choose Submitted "
                    + "Timesheets: a rejected period shows the reviewer's reason. Choose Revise & Resubmit (or Resubmit "
                    + "on that period) to load its hours, correct them, enter a reason for change and attach your "
                    + "approved changed timesheet as the supporting document, then submit it again to your reporting "
                    + "manager.";
            // Where things already are. Statuses come from the page itself, never from the assistant.
            case TIMESHEET_STATUS -> admin
                    ? "Company Admin accounts don't submit timesheets. Open Timesheets to see each submitted timesheet "
                    + "across the organization with its status."
                    : "Open Timesheets" + (employee ? "" : ", stay on the My Timesheets tab,") + " and choose Submitted "
                    + "Timesheets: each period you've submitted is listed with its status and the reporting manager it "
                    + "went to. If a period was rejected, open it to see the reason and resubmit it."
                    + (employee ? "" : " Your direct reports' submissions are on the Team Timesheets tab.")
                    + " You can also ask me \"What is my timesheet status?\" for your most recent timesheet.";
            case DOCUMENT_REQUEST_STATUS -> admin
                    ? "Open Request Documents: each request is listed with its status (Pending, Completed or Rejected), "
                    + "and you can filter by status. Open a request for its details. Documents prepared by HR that "
                    + "wait for your approval are in Document Approvals."
                    : employee
                    ? "Open Document Requests: each request is listed with its status (Pending, Completed or Rejected), "
                    + "and you can filter by status. Open a request to see its details and any document that's ready "
                    + "for you."
                    : "Open Request Documents and choose My requests to see the documents you requested for yourself or "
                    + "from employees, each with its status (Pending, Completed or Rejected). Requests sent to you are "
                    + "under Received requests. Open a request for its details.";
            case ONBOARDING_UPLOAD -> employee
                    ? "Onboarding documents are uploaded in Document Requests, not in your profile. If HR has asked you "
                    + "for documents, open that request in Document Requests and upload the file there. When you request "
                    + "your offer letter (Request a document, then Offer Letter), you attach the six required onboarding "
                    + "documents: Resume, Work Authorization Copy, Passport Copy (complete pages), I-94, Driver's License "
                    + "Copy and SSN Copy."
                    : "To collect onboarding documents from an employee, open Request Documents and choose Request from "
                    + "employee; the employee uploads them in their Document Requests."
                    + (admin ? "" : " If a document request has been sent to you, open it in Request Documents "
                    + "(Received requests) and upload the file there.");
            case OFFER_LETTER_COPY -> admin
                    ? "Company Admin accounts don't receive offer letters in Waypoint. An offer letter you approved is "
                    + "on the employee's request in Request Documents, where you can preview or download it."
                    : "Open " + (employee ? "Document Requests" : "Request Documents, choose My requests,")
                    + " and open your Offer Letter request. Once your letter has been prepared and approved, it appears "
                    + "there with Preview and Download buttons, and you sign it there if it's awaiting your signature; "
                    + "after both signatures it's shown as the complete offer letter. If no letter is shown yet, it "
                    + "hasn't been approved and sent to you.";
            case EMPLOYEE_CREATE -> employee
                    ? "Employees can't add people to Waypoint. HR creates employee accounts, and a Company Admin creates "
                    + "HR accounts; ask HR if someone new needs to be added."
                    : admin
                    ? "HR users create employee accounts in Waypoint: on the HR Dashboard or My Direct Reports they choose "
                    + "Create Employee and assign the new employee's reporting manager. As a Company Admin, you create "
                    + "HR users: open your admin dashboard and choose Create HR user under HR Management."
                    : "Open your Dashboard (or My Direct Reports) and choose Create Employee. Enter the new employee's "
                    + "details and assign their reporting manager, then create the account.";
            case PROFILE_CHANGE_REVIEW -> employee
                    ? "Employees don't review profile change requests. Your own requests are reviewed by HR, and Profile "
                    + "Change Requests shows the status of each one."
                    : "Open Profile Change Requests and select a pending request to review it. Choose Apply Change to "
                    + "update the profile, or Reject Request to decline it; you'll confirm the decision before it's "
                    + "saved." + (admin ? "" : " Your own requests on the same page are reviewed by someone else.");
            case ORGANIZATION_SETTINGS -> admin
                    ? "Open Organization Settings and choose Edit Settings. The payroll period is under Payroll & "
                    + "Timesheets; organization details are under Organization Information. Review your changes and "
                    + "choose Save."
                    : "Only a Company Admin can change organization settings, including how often payroll runs, in "
                    + "Organization Settings. Ask your Company Admin if a setting needs to change.";
            // The Knowledge Base page's own steps; each one only works while that step is switched on there.
            case KNOWLEDGE_BASE -> employee
                    ? "Company policies are managed by HR and Company Admin in the Knowledge Base, which isn't available "
                    + "to Employee accounts. You can ask me policy questions; I answer only from the approved policies "
                    + "you're allowed to see."
                    : "Open Knowledge Base. To add a policy, enter its title, choose the file (PDF, DOCX, XLSX, TXT or "
                    + "Markdown, up to 5 MB) and who may see it - Company-wide (all employees) or HR and administrators "
                    + "only - then choose Add policy. Next choose Ingest to index it, and Activate once it is indexed; "
                    + "only an active policy is used for answers. To update one, choose Replace file and Approve "
                    + "replacement (the current version keeps serving until the new one is activated); Revoke stops a "
                    + "policy being used. The status switches on that page show which of these steps are enabled.";
        };
    }

    private String formatProfileAnswer(MyHrOwnProfileResponse profile, ProfileQuestion question) {
        return switch (question) {
            case EMPLOYEE_ID -> answerForField("Your employee ID", profile.employeeId());
            case JOB_TITLE -> answerForField("Your job title", profile.designation());
            case DEPARTMENT -> "Department is not available in your self-profile.";
            case WORK_EMAIL -> "Work email is not available in your self-profile.";
            // Only the verified user's own active primary reporting manager, and only the name.
            case REPORTING_MANAGER -> profile.reportingManager() == null || isBlank(profile.reportingManager().name())
                    ? "No reporting manager is assigned to you in Waypoint. If that looks wrong, please contact HR."
                    : "Your reporting manager is " + profile.reportingManager().name() + ".";
            case NAME -> isBlank(profile.name()) ? "Your name is not available in your profile."
                    : "Your name in Waypoint is " + profile.name() + ".";
            case FIRST_NAME -> answerForField("Your first name in Waypoint", profile.firstName());
            case LAST_NAME -> answerForField("Your last name in Waypoint", profile.lastName());
            case REPORTING_MANAGER_FIRST_NAME, REPORTING_MANAGER_LAST_NAME -> managerNamePart(profile,
                    question == ProfileQuestion.REPORTING_MANAGER_FIRST_NAME);
            case REPORTING_RELATIONSHIP -> reportingRelationship(profile, null);
            case PROFILE -> "Your profile: "
                    + displayField("Name", profile.name()) + "; "
                    + displayField("Employee ID", profile.employeeId()) + "; "
                    + displayField("Job title", profile.designation()) + "; "
                    + "Department: not available in your self-profile; "
                    + "Work email: not available in your self-profile.";
        };
    }

    /**
     * One part of the reporting manager's recorded name. Waypoint stores the manager reference as one full name, so
     * the part is the first or last word of it, always shown with the full name it came from.
     */
    private String managerNamePart(MyHrOwnProfileResponse profile, boolean first) {
        String full = profile.reportingManager() == null ? null : profile.reportingManager().name();
        if (isBlank(full)) {
            return "No reporting manager is assigned to you in Waypoint. If that looks wrong, please contact HR.";
        }
        String[] parts = full.strip().split("\\s+");
        if (parts.length < 2) {
            return "Your reporting manager is recorded only as " + full.strip() + ", so I can't tell which part is the "
                    + (first ? "first" : "last") + " name.";
        }
        return "Your reporting manager's " + (first ? "first" : "last") + " name is "
                + (first ? parts[0] : parts[parts.length - 1]) + " (full name: " + full.strip() + ").";
    }

    /**
     * Why someone is the user's manager: the confirmed relationship from their own profile, and the honest limit that
     * Waypoint records the assignment, not its reason. {@code namedInQuestion}, when the user typed a name, is only
     * compared with the record; a different name is never confirmed.
     */
    private String reportingRelationship(MyHrOwnProfileResponse profile, String namedInQuestion) {
        String manager = profile.reportingManager() == null ? null : profile.reportingManager().name();
        if (isBlank(manager)) {
            return "No reporting manager is assigned to you in Waypoint, so there is no reporting relationship to explain. "
                    + "HR or your Company Admin sets reporting managers on the Employees page.";
        }
        String confirmed = namedInQuestion != null && !manager.toLowerCase(Locale.ROOT)
                .contains(namedInQuestion.toLowerCase(Locale.ROOT))
                ? "Waypoint doesn't show " + namedInQuestion + " as your reporting manager; your recorded reporting manager is "
                + manager + ". "
                : "Waypoint records " + manager + " as your reporting manager. ";
        return confirmed + "It records the assignment itself, not the reason it was made, so I can't tell you why. HR or "
                + "your Company Admin manages reporting assignments and can explain it.";
    }

    private String answerForField(String label, String value) {
        return isBlank(value) ? label + " is not available in your profile." : label + " is " + value + ".";
    }

    private String displayField(String label, String value) {
        return isBlank(value) ? label + " is not available in your profile." : label + ": " + value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String formatRecentTimesheetStatus(Optional<MyHrRecentTimesheetStatus> result) {
        if (result.isEmpty()) {
            return "No timesheet found.";
        }
        MyHrRecentTimesheetStatus timesheet = result.get();
        if (timesheet.periodStartDate() == null || timesheet.periodEndDate() == null || timesheet.status() == null) {
            return "A timesheet was found, but its period or status is unavailable.";
        }
        String period = timesheet.periodStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + timesheet.periodEndDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String status = switch (timesheet.status()) {
            case DRAFT -> "is a draft and has not been submitted.";
            case SUBMITTED -> "was submitted and is awaiting review.";
            case APPROVED -> "was approved.";
            case REJECTED -> "was rejected. Please review it in Waypoint for next steps.";
        };
        return "Your most recent timesheet for " + period + " " + status;
    }

    private String formatRejectionStatusCheck(Optional<MyHrRecentTimesheetStatus> result) {
        if (result.isEmpty()) {
            return "I couldn't find a timesheet of yours in Waypoint.";
        }
        MyHrRecentTimesheetStatus timesheet = result.get();
        if (timesheet.periodStartDate() == null || timesheet.periodEndDate() == null || timesheet.status() == null) {
            return "I found your most recent timesheet, but its period or status is unavailable.";
        }
        String period = timesheet.periodStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + timesheet.periodEndDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (timesheet.status() == TimesheetStatus.REJECTED) {
            return "I checked: your most recent timesheet, for " + period + ", was rejected.";
        }
        String status = switch (timesheet.status()) {
            case DRAFT -> "is a draft and hasn't been submitted";
            case SUBMITTED -> "was submitted and is awaiting review";
            default -> "was approved";
        };
        return "I checked: your most recent timesheet, for " + period + ", " + status + ". I can only check your most "
                + "recent timesheet, so an earlier rejected period would be listed in Submitted Timesheets.";
    }

    /** Only the recorded submission time; never the work period, creation, update or review date. */
    private String formatLatestTimesheetSubmission(Optional<MyHrLatestTimesheetSubmission> result) {
        if (result.isEmpty()) {
            return "I couldn't find a timesheet you've submitted in Waypoint. Drafts don't count until they're submitted.";
        }
        MyHrLatestTimesheetSubmission submission = result.get();
        if (submission.submittedAt() == null) {
            return "I found a submitted timesheet, but Waypoint has no submission date recorded for it, so I can't say "
                    + "when it was submitted. Open Submitted Timesheets on Timesheets to check its details.";
        }
        String answer = "You last submitted a timesheet on "
                + submission.submittedAt().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (submission.periodStartDate() != null && submission.periodEndDate() != null) {
            answer += ", for " + submission.periodStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE) + " to "
                    + submission.periodEndDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        String status = submission.status() == null ? "" : switch (submission.status()) {
            case SUBMITTED -> " It's awaiting review.";
            case APPROVED -> " It has since been approved.";
            case REJECTED -> " It has since been rejected.";
            case DRAFT -> "";
        };
        return answer + "." + status + " If you resubmitted that timesheet, this is the date of the latest resubmission.";
    }

    private enum WorkflowIntent {
        TIMESHEET_REJECTED,
        TIMESHEET_ENTRY,
        TIMESHEET_CORRECTION,
        TIMESHEET_REVIEW,
        PROFILE_CHANGE,
        OFFER_LETTER,
        DOCUMENT_REQUEST,
        DOCUMENT_APPROVAL,
        TIMESHEET_STATUS,
        DOCUMENT_REQUEST_STATUS,
        ONBOARDING_UPLOAD,
        OFFER_LETTER_COPY,
        // Capabilities the topic list did not cover, so a router had to pick the nearest wrong topic or refuse.
        // Exposed to semantic routing through workflowTopics(); no deterministic phrase rule selects them.
        EMPLOYEE_CREATE,
        PROFILE_CHANGE_REVIEW,
        ORGANIZATION_SETTINGS,
        /** Adding, replacing, activating or revoking a company policy in the Knowledge Base (HR, Company Admin). */
        KNOWLEDGE_BASE
    }

    private enum ProfileChangeDateQuestion {
        APPROVED_DATE,
        REQUESTED_DATE
    }

    private enum PhoneNumberChangeQuestion {
        APPROVAL,
        PENDING,
        REJECTION,
        REQUESTED_DATE,
        APPROVED_DATE,
        EFFECTIVE_DATE
    }

    private enum DocumentRequestQuestion {
        LATEST_STATUS,
        OFFER_LETTER_STATUS,
        REJECTION_REASON,
        EMPLOYMENT_VERIFICATION_REQUEST_DATE,
        REQUESTED_DOCUMENTS,
        COMPLETED_OFFER_LETTER_DOWNLOAD,
        ONBOARDING_PENDING
    }

    private enum DocumentRequestQueueQuestion {
        HR_AWAITING_REVIEW,
        HR_PENDING,
        HR_APPROVAL,
        HR_RETURNED,
        HR_ASSIGNED,
        HR_REVIEW_LOCATION,
        ADMIN_PENDING,
        ADMIN_AWAITING_APPROVAL,
        ADMIN_COMPLETED,
        ADMIN_REVIEW_LOCATION,
        MY_APPROVAL,
        MY_QUEUE
    }

    private enum ProfileChangeQueueQuestion {
        HR_AWAITING_REVIEW,
        PENDING,
        HR_REVIEW_LOCATION,
        ADMIN_PENDING_ORGANIZATION,
        ADMIN_AWAITING_APPROVAL,
        ADMIN_APPROVED,
        ADMIN_REJECTED,
        REVIEW_LOCATION,
        MY_APPROVAL,
        MY_QUEUE
    }

    private enum TimesheetQueueQuestion {
        HR_AWAITING_REVIEW,
        HR_AWAITING_APPROVAL,
        HR_ASSIGNED_PENDING,
        HR_REVIEW_LOCATION,
        ADMIN_SUBMITTED,
        ADMIN_PENDING_APPROVAL,
        ADMIN_AWAITING_REVIEW,
        ADMIN_APPROVED,
        ADMIN_REJECTED,
        ADMIN_REVIEW_LOCATION,
        MY_APPROVAL,
        MY_QUEUE
    }

    private enum ProfileQuestion {
        PROFILE,
        EMPLOYEE_ID,
        JOB_TITLE,
        DEPARTMENT,
        WORK_EMAIL,
        REPORTING_MANAGER,
        NAME,
        FIRST_NAME,
        LAST_NAME,
        REPORTING_MANAGER_FIRST_NAME,
        REPORTING_MANAGER_LAST_NAME,
        REPORTING_RELATIONSHIP
    }

    // --- Question frame: answer type + concept + scope + selection -> capability ----------------------------------

    /**
     * Answers from the deterministic {@link WaviQuestionFrame}: first a follow-up resolved against the VERIFIED
     * dialogue state (the previous tool and its validated arguments, never browser text), then a self-contained
     * question whose requested answer type (who / how many people / which latest / why ...) the keyword routes cannot
     * express. Every lookup is one of the existing actor-scoped answers; the frame only chooses among them. With
     * {@code fallback} false only the shapes that must outrank broader keyword routes are tried; with true (after
     * every other route and the semantic router declined) the remaining fresh-question shapes as well. Null when the
     * frame maps to no supported capability, so the established path continues unchanged.
     */
    /** Where in the routing order the frame is consulted; see {@link #frameResponse}. */
    enum FramePhase {
        /** Before every keyword route: follow-ups resolved against the verified dialogue state only. */
        FOLLOW_UP,
        /** After the precise routes (and the router, when on): self-contained questions with a definite answer type. */
        FRESH,
        /** After everything else: wording no route or router understood. */
        FALLBACK
    }

    private MyHrChatResponse frameResponse(String message, Conversation conversation, VerifiedMyHrActor actor, Turn turn,
                                           FramePhase phase) {
        boolean fallback = phase == FramePhase.FALLBACK;
        if (actor == null || message == null || message.isBlank() || MyHrScopeGuard.isExplicitlyOutOfScope(message)
                // Writing help ("rewrite this", "write a reminder to my team") is never a record lookup.
                || MyHrScopeGuard.isWritingTask(message)) {
            return null;
        }
        // A navigation command keeps its established page route and role checks.
        String asked = normalizeQuestion(message).replace('’', '\'');
        if (MyHrConversationIntents.navigationTarget(asked) != null || WaviContinuation.canonicalNavigation(asked) != null) {
            return null;
        }
        WaviQuestionFrame frame = WaviQuestionFrame.of(message);
        WaviDialogueStore.State previous = turn.previousState;
        if (phase == FramePhase.FOLLOW_UP && previous != null && frame.contextual() && previous.tool() != null
                && !WaviDialogueStore.NO_STATE.equals(previous.tool())) {
            MyHrChatResponse followUp = frameFollowUp(frame, message, conversation, actor, turn, previous);
            if (followUp != null) {
                log.info("{} provider=none frame=follow-up answer={} previous={}", PROVIDER_USED_MARKER, frame.answer(),
                        previous.tool());
                return followUp;
            }
        }
        // Early, only the precise shapes (an explicit answer type over an explicit capability) may answer before the
        // router and the broad routes; looser readings wait until those have declined.
        MyHrChatResponse fresh = frameQuestion(frame, message, actor, turn, fallback, phase == FramePhase.FOLLOW_UP);
        if (fresh != null) {
            log.info("{} provider=none frame=question answer={} scope={} fallback={}", PROVIDER_USED_MARKER,
                    frame.answer(), frame.scope(), fallback);
        }
        return fresh;
    }

    private MyHrChatResponse frameFollowUp(WaviQuestionFrame frame, String message, Conversation conversation,
                                           VerifiedMyHrActor actor, Turn turn, WaviDialogueStore.State previous) {
        String tool = previous.tool();
        Map<String, Object> arguments = previous.arguments() == null ? Map.of() : previous.arguments();
        boolean reviewer = isReviewer(actor);
        boolean ownTimesheet = WaviTools.GET_MY_TIMESHEETS.equals(tool) || WaviTools.LIST_MY_TIMESHEETS.equals(tool);
        String queue = WaviTools.GET_REVIEW_QUEUE.equals(tool) ? String.valueOf(arguments.get("queue")) : null;
        boolean pendingWork = WaviTools.GET_MY_PENDING_WORK.equals(tool) && reviewer;
        boolean teamTimesheets = reviewer && (WaviTools.GET_TEAM_TIMESHEETS.equals(tool) || "TIMESHEETS".equals(queue));
        boolean manager = WaviTools.GET_MY_PROFILE_FIELD.equals(tool)
                && String.valueOf(arguments.get("field")).startsWith("REPORTING_");
        boolean ownRequests = WaviTools.GET_MY_REQUESTS.equals(tool) || WaviTools.LIST_MY_REQUESTS.equals(tool);
        MyHrConversationIntents.TimesheetRange period = ownTimesheet || teamTimesheets ? periodOf(previous) : null;

        // "What's their first name?" after the manager: one part of the same recorded name.
        if (manager && (frame.namePart() == WaviQuestionFrame.NamePart.FIRST
                || frame.namePart() == WaviQuestionFrame.NamePart.LAST)) {
            return managerAnswer(actor, turn, frame.namePart() == WaviQuestionFrame.NamePart.FIRST
                    ? ProfileQuestion.REPORTING_MANAGER_FIRST_NAME : ProfileQuestion.REPORTING_MANAGER_LAST_NAME, null);
        }
        switch (frame.answer()) {
            case REASON -> {
                // A record's status, not a counting rule ("why were these hours counted once?").
                if (ownTimesheet && !frame.has(WaviQuestionFrame.Concept.HOURS)) {
                    turn.continuedWith(tool, arguments, previous);
                    return ownTimesheetReasonResponse(actor, period);
                }
                if (manager) return managerAnswer(actor, turn, ProfileQuestion.REPORTING_RELATIONSHIP, null);
                return null;
            }
            case SOURCE -> {
                if (WaviTools.POLICY_QUESTION.equals(tool)) return citationFollowUp(message, conversation, actor, turn);
                MyHrConversationIntents.Page page = pageOf(tool, arguments, actor);
                if (page == null) return null;
                turn.keepPreviousState = true;
                return new MyHrChatResponse("That came from your Waypoint records (" + page.label + "), read for your "
                        + "account when you asked - not from a policy document or general knowledge.")
                        .withActions(page.availableTo(actor.role()) ? List.of(page.name()) : List.of());
            }
            case LOCATION -> {
                MyHrConversationIntents.Page page = pageOf(tool, arguments, actor);
                if (page == null || !page.availableTo(actor.role())) return null;
                turn.keepPreviousState = true;
                return new MyHrChatResponse(MyHrConversationIntents.navigationReply(page)).withActions(List.of(page.name()));
            }
            default -> { }
        }
        // "How many hours is it?" right after one record was selected from the team's submissions: that record again
        // (its answer carries the hours), never a re-ask of which one.
        boolean selectedOne = WaviTools.GET_TEAM_TIMESHEETS.equals(tool)
                && TeamView.LATEST_BY_SUBMISSION.name().equals(String.valueOf(arguments.get(TEAM_VIEW)));
        if (selectedOne && frame.has(WaviQuestionFrame.Concept.HOURS)) {
            String status = arguments.get("status") == null ? WaviTools.TimesheetStatusFilter.ANY.name()
                    : arguments.get("status").toString();
            return teamTimesheetsResponse(actor, new WaviTeamTimesheetQuery(status, period, false, personOrdinal(previous)),
                    null, turn, false, TeamView.LATEST_BY_SUBMISSION);
        }
        // A selection of the previous result set: the latest submission, never a status filter on "submitted".
        if (frame.selection() == WaviQuestionFrame.Selection.LATEST_SUBMITTED
                || frame.selection() == WaviQuestionFrame.Selection.LATEST) {
            if (ownTimesheet) {
                turn.continuedWith(tool, arguments, previous);
                return ownLatestSubmittedResponse(actor, period);
            }
            if (teamTimesheets || pendingWork) {
                String status = WaviTools.GET_TEAM_TIMESHEETS.equals(tool) && arguments.get("status") != null
                        ? arguments.get("status").toString() : WaviTools.TimesheetStatusFilter.SUBMITTED.name();
                return teamTimesheetsResponse(actor, new WaviTeamTimesheetQuery(status, period, false,
                        personOrdinal(previous)), null, turn, false, TeamView.LATEST_BY_SUBMISSION);
            }
        }
        // "Which one is still open?" filters the user's requests; "has it been approved?" asks about the one referenced.
        boolean selects = frame.answer() == WaviQuestionFrame.AnswerType.WHICH || frame.answer() == WaviQuestionFrame.AnswerType.LIST;
        if (ownRequests && selects && frame.selection() != WaviQuestionFrame.Selection.NONE
                && frame.selection() != WaviQuestionFrame.Selection.LATEST
                && frame.selection() != WaviQuestionFrame.Selection.LATEST_SUBMITTED) {
            String kind = "PROFILE_CHANGE".equals(String.valueOf(arguments.get("kind"))) ? "PROFILE_CHANGE" : "DOCUMENT_REQUEST";
            String status = switch (frame.selection()) {
                case COMPLETED, APPROVED -> WaviTools.RequestStatusFilter.COMPLETED.name();
                case REJECTED -> WaviTools.RequestStatusFilter.REJECTED.name();
                default -> WaviTools.RequestStatusFilter.OPEN.name();
            };
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.LIST_MY_REQUESTS,
                    Map.of("kind", kind, "status", status), () -> myRequestListResponse(actor, kind, status));
        }
        if (frame.answer() == WaviQuestionFrame.AnswerType.PEOPLE_COUNT && ("TIMESHEETS".equals(queue) || pendingWork
                || queue != null)) {
            WaviTools.ReviewQueue counted = queue == null ? WaviTools.ReviewQueue.TIMESHEETS : WaviTools.ReviewQueue.valueOf(queue);
            turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", counted.name(), "detail", "LIST"));
            return reviewQueuePeopleCountResponse(actor, counted);
        }
        boolean enumerate = frame.answer() == WaviQuestionFrame.AnswerType.PERSON
                || frame.answer() == WaviQuestionFrame.AnswerType.LIST || frame.answer() == WaviQuestionFrame.AnswerType.EXPAND
                || (frame.answer() == WaviQuestionFrame.AnswerType.WHICH && frame.selection() == WaviQuestionFrame.Selection.NONE);
        // "Who submitted them?" after one queue's count: the established queue continuation lists that queue.
        if (!enumerate) return null;
        // "All" after pending work, or pending work for one referenced person: the established continuation keeps
        // the person and widens to every kind.
        if (pendingWork && frame.answer() != WaviQuestionFrame.AnswerType.EXPAND && arguments.get("ordinal") == null
                && personOrdinal(previous) == null) {
            // Pending work narrowed to one kind lists that queue; the overview lists everyone waiting.
            String area = arguments.get("area") == null ? null : arguments.get("area").toString();
            WaviTools.ReviewQueue narrowed = area == null ? null : switch (area) {
                case "TIMESHEETS" -> WaviTools.ReviewQueue.TIMESHEETS;
                case "PROFILE_CHANGES" -> WaviTools.ReviewQueue.PROFILE_CHANGES;
                case "DOCUMENT_REQUESTS" -> WaviTools.ReviewQueue.DOCUMENT_REQUESTS;
                default -> null;
            };
            if (narrowed != null) {
                return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_REVIEW_QUEUE,
                        Map.of("queue", narrowed.name(), "detail", "LIST"), () -> reviewQueueItemsResponse(actor, narrowed));
            }
            turn.answeredWith(WaviTools.GET_MY_PENDING_WORK, Map.of());
            return reviewWaitingPeopleResponse(actor);
        }
        if (WaviTools.GET_PEOPLE_SUMMARY.equals(tool) && reviewer) {
            turn.answeredWith(WaviTools.GET_PEOPLE_SUMMARY, Map.of());
            return peopleListResponse(actor);
        }
        if (ownRequests && frame.answer() == WaviQuestionFrame.AnswerType.EXPAND) {
            String kind = "PROFILE_CHANGE".equals(String.valueOf(arguments.get("kind"))) ? "PROFILE_CHANGE" : "DOCUMENT_REQUEST";
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.LIST_MY_REQUESTS,
                    Map.of("kind", kind, "status", "ANY"), () -> myRequestListResponse(actor, kind, "ANY"));
        }
        return null;
    }

    /** The Waypoint page that holds what a previous tool answered, for "where can I see it?" and provenance. */
    private static MyHrConversationIntents.Page pageOf(String tool, Map<String, Object> arguments, VerifiedMyHrActor actor) {
        return switch (tool) {
            case WaviTools.GET_MY_TIMESHEETS, WaviTools.LIST_MY_TIMESHEETS, WaviTools.GET_TEAM_TIMESHEETS ->
                    MyHrConversationIntents.Page.TIMESHEETS;
            case WaviTools.GET_MY_PROFILE_FIELD, WaviTools.GET_MY_ROLE -> MyHrConversationIntents.Page.MY_PROFILE;
            case WaviTools.GET_MY_REQUESTS, WaviTools.LIST_MY_REQUESTS ->
                    "PROFILE_CHANGE".equals(String.valueOf(arguments.get("kind")))
                            ? MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS
                            : MyHrConversationIntents.Page.DOCUMENT_REQUESTS;
            case WaviTools.GET_REVIEW_QUEUE -> switch (String.valueOf(arguments.get("queue"))) {
                case "PROFILE_CHANGES" -> MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS;
                case "DOCUMENT_REQUESTS" -> actor.role() == UserRole.COMPANY_ADMIN
                        ? MyHrConversationIntents.Page.DOCUMENT_APPROVALS : MyHrConversationIntents.Page.DOCUMENT_REQUESTS;
                default -> MyHrConversationIntents.Page.TIMESHEETS;
            };
            case WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_TEAM_MEMBER, WaviTools.GET_PEOPLE_SUMMARY ->
                    MyHrConversationIntents.Page.EMPLOYEES;
            default -> null;
        };
    }

    /**
     * A detail of the user's own reporting-manager relationship (a name part, or why: the confirmed assignment), from
     * the user's own profile only. The turn is remembered as the reporting-manager field, so later follow-ups
     * ("what's their first name?", "why?") continue it.
     */
    private MyHrChatResponse managerAnswer(VerifiedMyHrActor actor, Turn turn, ProfileQuestion question, String typedName) {
        MyHrOwnProfileResponse profile = employeeManagementService.getOwnDetail(actor.userId());
        turn.answeredWith(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", WaviTools.ProfileField.REPORTING_MANAGER.name()));
        return new MyHrChatResponse(question == ProfileQuestion.REPORTING_RELATIONSHIP
                ? reportingRelationship(profile, typedName) : formatProfileAnswer(profile, question)).withActions(List.of());
    }

    private MyHrChatResponse profileFieldViaTools(VerifiedMyHrActor actor, Turn turn, WaviTools.ProfileField field) {
        return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_PROFILE_FIELD,
                Map.of("field", field.name()), () -> new MyHrChatResponse(formatProfileAnswer(
                        employeeManagementService.getOwnDetail(actor.userId()), ProfileQuestion.valueOf(field.name()))));
    }

    private MyHrChatResponse frameQuestion(WaviQuestionFrame frame, String message, VerifiedMyHrActor actor, Turn turn,
                                           boolean fallback, boolean preciseOnly) {
        String normalized = normalizeQuestion(message);
        java.util.Set<String> tokens = new java.util.HashSet<>(List.of(normalized.split("[^a-z]+")));
        boolean reviewer = isReviewer(actor);
        WaviQuestionFrame.Scope scope = frame.scope();
        boolean ownScope = scope == WaviQuestionFrame.Scope.SELF || scope == WaviQuestionFrame.Scope.UNSPECIFIED;
        boolean otherPerson = WaviContinuation.namesThirdParty(WaviQuestionFrame.withoutConceptPossessives(message))
                || WaviContinuation.refersToPerson(normalized) || WaviContinuation.namesOtherOrganization(normalized);

        // The user's own reporting manager: one part of the name, or why that person is the manager.
        if (frame.has(WaviQuestionFrame.Concept.MANAGER) && ownScope && !frame.has(WaviQuestionFrame.Concept.PEOPLE)
                && WaviContinuation.hasFirstPerson(normalized)) {
            if (frame.namePart() == WaviQuestionFrame.NamePart.FIRST || frame.namePart() == WaviQuestionFrame.NamePart.LAST) {
                return managerAnswer(actor, turn, frame.namePart() == WaviQuestionFrame.NamePart.FIRST
                        ? ProfileQuestion.REPORTING_MANAGER_FIRST_NAME : ProfileQuestion.REPORTING_MANAGER_LAST_NAME, null);
            }
            if (frame.answer() == WaviQuestionFrame.AnswerType.REASON) {
                return managerAnswer(actor, turn, ProfileQuestion.REPORTING_RELATIONSHIP, typedManagerName(message));
            }
        }
        // "Why do I report to X?": the relationship, never a policy search.
        if (frame.answer() == WaviQuestionFrame.AnswerType.REASON && tokens.contains("report") && tokens.contains("i")) {
            return managerAnswer(actor, turn, ProfileQuestion.REPORTING_RELATIONSHIP, typedManagerName(message));
        }
        // "Why is my timesheet still pending?": what Waypoint records about it, never an invented reason.
        // Only a wait (pending, still, not yet ...): a rejection's reason keeps its established answer with the comment.
        boolean waitingOnReview = frame.selection() == WaviQuestionFrame.Selection.PENDING
                || tokens.contains("still") || tokens.contains("yet") || tokens.contains("stuck");
        if (frame.answer() == WaviQuestionFrame.AnswerType.REASON && frame.has(WaviQuestionFrame.Concept.TIMESHEET)
                && waitingOnReview && frame.selection() != WaviQuestionFrame.Selection.REJECTED
                && ownScope && WaviContinuation.hasFirstPerson(normalized) && !otherPerson
                && !frame.has(WaviQuestionFrame.Concept.POLICY) && !WaviContinuation.isConditional(normalized)) {
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock));
            Map<String, Object> state = range == null ? Map.of("period", WaviTimesheetPeriod.Period.LATEST.name())
                    : WaviTimesheetPeriod.arguments(range, LocalDate.now(clock));
            if (state != null) turn.answeredWith(WaviTools.GET_MY_TIMESHEETS, state);
            return ownTimesheetReasonResponse(actor, range);
        }
        // Knowledge Base management: the real page steps for the role, never an invented workflow.
        // A management verb applied to a policy document ("upload a policy"), not a noun ("profile update policy").
        boolean manages = tokens.stream().anyMatch(java.util.Set.of("upload", "publish", "replace", "revoke",
                "activate", "ingest")::contains) || (tokens.contains("add") && tokens.contains("new"));
        boolean knowledgeBase = frame.has(WaviQuestionFrame.Concept.KNOWLEDGE_BASE);
        if (frame.answer() == WaviQuestionFrame.AnswerType.INSTRUCTION
                && (knowledgeBase || (frame.has(WaviQuestionFrame.Concept.POLICY) && manages))) {
            turn.permitExternalUse();
            turn.answeredWith(WaviTools.WORKFLOW_HELP, Map.of());
            return new MyHrChatResponse(workflowGuidance(WorkflowIntent.KNOWLEDGE_BASE, actor.role()))
                    .withActions(reviewer ? List.of(MyHrConversationIntents.Page.KNOWLEDGE_BASE.name()) : List.of());
        }
        // Which policies exist / who may see them: the catalog, not a search inside one policy.
        if (frame.has(WaviQuestionFrame.Concept.POLICY) && tokens.contains("policies")
                && (frame.has(WaviQuestionFrame.Concept.ACCESS) || tokens.contains("available") || tokens.contains("exist"))
                && (frame.answer() == WaviQuestionFrame.AnswerType.WHICH || frame.answer() == WaviQuestionFrame.AnswerType.LIST
                    || frame.answer() == WaviQuestionFrame.AnswerType.COUNT || frame.answer() == WaviQuestionFrame.AnswerType.DETAIL
                    || frame.answer() == WaviQuestionFrame.AnswerType.YES_NO)) {
            ChunkAccessLevel level = tokens.contains("hr") && tokens.contains("only") ? ChunkAccessLevel.HR_ONLY
                    : tokens.contains("employees") || tokens.contains("everyone") ? ChunkAccessLevel.COMPANY_WIDE : null;
            turn.answeredWith(WaviTools.POLICY_QUESTION, Map.of());
            return policyCatalogResponse(actor, level);
        }
        if (reviewer && !otherPerson) {
            WaviTools.ReviewQueue queue = frame.has(WaviQuestionFrame.Concept.TIMESHEET) ? WaviTools.ReviewQueue.TIMESHEETS
                    : frame.has(WaviQuestionFrame.Concept.PROFILE_CHANGE) ? WaviTools.ReviewQueue.PROFILE_CHANGES
                    : frame.has(WaviQuestionFrame.Concept.DOCUMENT_REQUEST) ? WaviTools.ReviewQueue.DOCUMENT_REQUESTS : null;
            boolean waiting = frame.has(WaviQuestionFrame.Concept.APPROVAL)
                    || frame.selection() == WaviQuestionFrame.Selection.PENDING
                    || frame.selection() == WaviQuestionFrame.Selection.OPEN;
            boolean reviewScope = scope == WaviQuestionFrame.Scope.TEAM || scope == WaviQuestionFrame.Scope.ORGANIZATION
                    || (scope == WaviQuestionFrame.Scope.UNSPECIFIED && waiting);
            // "How many employees have timesheets awaiting review?": distinct people, not items.
            if (frame.answer() == WaviQuestionFrame.AnswerType.PEOPLE_COUNT && queue != null && waiting) {
                turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name(), "detail", "LIST"));
                return reviewQueuePeopleCountResponse(actor, queue);
            }
            // "Who is waiting for my approvals?": people, grouped, from the reviewer's own queues.
            if (frame.answer() == WaviQuestionFrame.AnswerType.PERSON && waiting && reviewScope
                    && !WaviContinuation.asksWhoReviewsOwnTimesheets(normalized)) {
                if (queue != null) {
                    return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_REVIEW_QUEUE,
                            Map.of("queue", queue.name(), "detail", "LIST"), () -> reviewQueueItemsResponse(actor, queue));
                }
                turn.answeredWith(WaviTools.GET_MY_PENDING_WORK, Map.of());
                return reviewWaitingPeopleResponse(actor);
            }
            // Early, only an explicit list/which form; a bare value or status wording waits for the router.
            boolean records = frame.answer() == WaviQuestionFrame.AnswerType.LIST
                    || frame.answer() == WaviQuestionFrame.AnswerType.WHICH
                    || (!preciseOnly && (frame.answer() == WaviQuestionFrame.AnswerType.DETAIL
                        || frame.answer() == WaviQuestionFrame.AnswerType.STATUS));
            // "Show my team's pending timesheets": the team's submissions, never the reviewer's own.
            // Early, only with an explicit filter: "which ... need something done" asks for a filter the frame can't
            // name, so the router or the broad routes decide it.
            boolean explicitFilter = frame.selection() != WaviQuestionFrame.Selection.NONE
                    || frame.answer() == WaviQuestionFrame.AnswerType.LIST;
            if (records && queue == WaviTools.ReviewQueue.TIMESHEETS && (tokens.contains("team") || tokens.contains("reports"))
                    && !frame.has(WaviQuestionFrame.Concept.HOURS) && (!preciseOnly || explicitFilter)) {
                String status = switch (frame.selection()) {
                    case PENDING, OPEN -> WaviTools.TimesheetStatusFilter.SUBMITTED.name();
                    case APPROVED -> WaviTools.TimesheetStatusFilter.APPROVED.name();
                    case REJECTED -> WaviTools.TimesheetStatusFilter.REJECTED.name();
                    default -> WaviTools.TimesheetStatusFilter.ANY.name();
                };
                return teamTimesheetsResponse(actor, teamQueryFrom(message, status, null), message, turn, false);
            }
            if (preciseOnly) return null;
            // "Which document requests are open?" (no "my"): the reviewer's queue of them.
            if (records && queue != null && waiting && scope != WaviQuestionFrame.Scope.SELF) {
                return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_REVIEW_QUEUE,
                        Map.of("queue", queue.name(), "detail", "LIST"), () -> reviewQueueItemsResponse(actor, queue));
            }
            // "What's pending across the org?": every review queue at once.
            boolean waitingWords = frame.selection() == WaviQuestionFrame.Selection.PENDING
                    || frame.selection() == WaviQuestionFrame.Selection.OPEN || tokens.contains("approval")
                    || tokens.contains("approvals") || tokens.contains("queue");
            if (queue == null && waitingWords && reviewScope && !frame.has(WaviQuestionFrame.Concept.POLICY)
                    && (records || frame.answer() == WaviQuestionFrame.AnswerType.YES_NO)) {
                return viaTools(actor, turn, WaviRouterDecision.Intent.QUEUE, WaviTools.GET_MY_PENDING_WORK, Map.of(),
                        () -> pendingOverviewResponse(actor));
            }
        }
        if (!fallback || otherPerson || preciseOnly) return null;
        // --- Fallback only: fresh wording no other route or the router understood ---
        // "What does Waypoint know about me?": the supported profile summary.
        if (frame.has(WaviQuestionFrame.Concept.PROFILE) && frame.namesNoRecord() && !frame.has(WaviQuestionFrame.Concept.MANAGER)
                && WaviContinuation.hasFirstPerson(normalized) && frame.answer() != WaviQuestionFrame.AnswerType.INSTRUCTION
                && frame.answer() != WaviQuestionFrame.AnswerType.REASON) {
            MyHrChatResponse profile = profileFieldViaTools(actor, turn, WaviTools.ProfileField.PROFILE);
            return new MyHrChatResponse(profile.message() + " These are the profile details I can show in chat; you can "
                    + "ask separately about supported timesheets and requests.")
                    .withActions(List.of(MyHrConversationIntents.Page.MY_PROFILE.name()));
        }
        // "How many hours count for Oct 13?": the user's own effective hours for that period.
        if (frame.has(WaviQuestionFrame.Concept.HOURS) && WaviContinuation.hasFirstPerson(normalized) || frame.has(
                WaviQuestionFrame.Concept.HOURS) && scope == WaviQuestionFrame.Scope.UNSPECIFIED) {
            String stripped = WaviContinuation.withoutQuantityInterrogative(normalized);
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn(stripped, LocalDate.now(clock));
            if (range == null) return null;
            Map<String, Object> period = WaviTimesheetPeriod.arguments(range, LocalDate.now(clock));
            if (period == null) return null;
            Map<String, Object> arguments = new java.util.LinkedHashMap<>(period);
            boolean daily = range.singleDay() || WaviContinuation.asksDaily(normalized);
            arguments.put("detail", daily ? WaviTimesheetPeriod.Detail.DAILY_HOURS.name() : WaviTimesheetPeriod.Detail.HOURS.name());
            return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS, arguments,
                    () -> daily ? new ToolAnswers().dailyTimesheetHours(actor, range) : timesheetHoursResponse(actor, range));
        }
        return null;
    }

    /** The name a "why do I report to X?" question typed after "to", compared with the record only, never trusted. */
    private static String typedManagerName(String message) {
        String text = message.replace('’', '\'').strip();
        int at = text.toLowerCase(Locale.ROOT).lastIndexOf(" to ");
        if (at < 0) return null;
        String name = text.substring(at + 4).replaceAll("[?.!]+$", "").strip();
        if (name.isEmpty() || name.split("\\s+").length > 4) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("her") || lower.equals("him") || lower.equals("them") || lower.startsWith("my ")
                || lower.equals("this person") ? null : name;
    }

    // --- Policy catalog -------------------------------------------------------------------------------------------

    /** The Knowledge Base's own listing (HR and Company Admin, organization-scoped); null where it is not deployed. */
    private KnowledgeManagementService knowledgeManagement;

    @Autowired(required = false)
    void useKnowledgeManagement(KnowledgeManagementService knowledgeManagement) {
        this.knowledgeManagement = knowledgeManagement;
    }

    static final String POLICY_CATALOG_EMPLOYEE_REPLY = "I can't list the policy documents for Employee accounts. I answer "
            + "policy questions only from the approved company-wide policies you're allowed to see, with the source "
            + "cited; HR-only policies aren't available to Employees. Ask me about a topic, for example \"How many "
            + "vacation days do I get?\"";

    /**
     * Which policies are active, by who may see them, from the Knowledge Base listing the reviewer can already open.
     * Titles and access levels only, never content. {@code onlyLevel} narrows to one access level (null = all).
     */
    MyHrChatResponse policyCatalogResponse(VerifiedMyHrActor actor, ChunkAccessLevel onlyLevel) {
        if (actor.role() == UserRole.EMPLOYEE) return new MyHrChatResponse(POLICY_CATALOG_EMPLOYEE_REPLY).withActions(List.of());
        List<String> knowledgePage = List.of(MyHrConversationIntents.Page.KNOWLEDGE_BASE.name());
        if (knowledgeManagement == null) {
            return new MyHrChatResponse("I can't list the policy catalog in chat here. Open Knowledge Base to see each "
                    + "policy and who may see it.").withActions(knowledgePage);
        }
        List<com.waypoint.dto.myhr.KnowledgeDocumentResponse> entries;
        try {
            entries = knowledgeManagement.list(actor);
        } catch (UnauthorizedException | com.waypoint.exception.BusinessException refused) {
            // The listing's own authorization decides; a refusal is reported, never worked around.
            return new MyHrChatResponse("I can't list the policy catalog for your account. Open Knowledge Base if you "
                    + "manage policies, or ask me about a policy topic.").withActions(List.of());
        }
        java.util.Map<ChunkAccessLevel, List<String>> byLevel = new java.util.EnumMap<>(ChunkAccessLevel.class);
        int pending = 0;
        for (com.waypoint.dto.myhr.KnowledgeDocumentResponse entry : entries == null
                ? List.<com.waypoint.dto.myhr.KnowledgeDocumentResponse>of() : entries) {
            if (entry == null || entry.status() != com.waypoint.enums.KnowledgeDocumentStatus.LIVE) continue;
            if (entry.activeVersion() == null || entry.activeVersion().accessLevel() == null) {
                pending++;
                continue;
            }
            ChunkAccessLevel level = entry.activeVersion().accessLevel();
            if (onlyLevel != null && level != onlyLevel) continue;
            byLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(isBlank(entry.title()) ? "Untitled policy"
                    : entry.title());
        }
        log.info("{} provider=none policy-catalog levels={} pending={}", PROVIDER_USED_MARKER, byLevel.size(), pending);
        String scope = onlyLevel == ChunkAccessLevel.HR_ONLY ? "HR-only " : onlyLevel == ChunkAccessLevel.COMPANY_WIDE
                ? "company-wide " : "";
        if (byLevel.isEmpty()) {
            return new MyHrChatResponse("There are no active " + scope + "policies in your organization's Knowledge Base"
                    + (pending > 0 ? " (" + pending + " not yet activated)" : "") + ".").withActions(knowledgePage);
        }
        StringBuilder reply = new StringBuilder("Active ").append(scope).append("policies in your organization's "
                + "Knowledge Base, by who may see them:");
        for (var level : byLevel.entrySet()) {
            String audience = switch (level.getKey()) {
                case COMPANY_WIDE -> "Company-wide (all employees)";
                case HR_ONLY -> "HR and administrators only";
                case EMPLOYEE_PRIVATE -> "Restricted";
            };
            List<String> titles = level.getValue();
            reply.append("\n- ").append(audience).append(" (").append(titles.size()).append("): ")
                    .append(String.join(", ", titles.subList(0, Math.min(titles.size(), MAX_LISTED_REVIEW_ITEMS))))
                    .append(titles.size() > MAX_LISTED_REVIEW_ITEMS ? ", and " + (titles.size() - MAX_LISTED_REVIEW_ITEMS)
                            + " more" : "");
        }
        if (pending > 0) reply.append("\n").append(pending).append(pending == 1 ? " more policy is" : " more policies are")
                .append(" not yet activated, so I don't use ").append(pending == 1 ? "it" : "them").append(" for answers.");
        return new MyHrChatResponse(reply.toString()).withActions(knowledgePage);
    }

    // --- Wavi tool layer (Phase P1) -------------------------------------------------------------------------------

    /**
     * Null unless waypoint.myhr.router.tool-layer.enabled is true (default false). When set, the deterministic routes
     * wired below answer through the tool layer (gate, tool, output bounds) instead of directly. The replies, page
     * buttons, lookups and history classification are the same as without it; the existing code is the fallback.
     */
    private WaviToolLayer toolLayer;
    private boolean toolLayerFlag;
    private boolean agentToolsIncluded;

    @Autowired
    void configureToolLayer(@Value("${waypoint.myhr.router.tool-layer.enabled:false}") boolean enabled) {
        useToolLayer(enabled);
    }

    /** Also used by tests to switch the tool layer on for one service instance. */
    void useToolLayer(boolean enabled) {
        toolLayerFlag = enabled;
        refreshToolLayer();
        if (enabled) {
            log.info("MyHR chat Wavi tool layer is enabled: deterministic routes answer through the tool layer");
        }
    }

    /** The tool layer exists while its own flag or semantic routing (which runs through it) is on. */
    private void refreshToolLayer() {
        boolean agent = agentActive();
        if (!toolLayerFlag && semanticRouting == null && shadowRouting == null && !agent) {
            toolLayer = null;
            agentToolsIncluded = false;
        } else if (toolLayer == null || agent != agentToolsIncluded) {
            ToolAnswers answers = new ToolAnswers();
            List<WaviTool> catalog = new ArrayList<>(WaviTools.catalog(answers));
            // Tools only the agent sees (the router's prompt, and so its approved spend ceiling, never include them).
            if (agent) catalog.addAll(WaviAgentTools.catalog(answers));
            toolLayer = new WaviToolLayer(new WaviToolRegistry(catalog));
            agentToolsIncluded = agent;
        }
    }

    // --- Claude tool-use agent (WaviAgentLoop) ----------------------------------------------------------------------

    /** Null, or disabled, unless waypoint.myhr.agent.enabled is true; it also needs an authorized organization. */
    private WaviAgentLoop agentLoop;

    @Autowired(required = false)
    void configureAgentLoop(WaviAgentLoop loop) {
        useAgentLoop(loop);
    }

    /** Also used by tests. The agent answers through the tool layer, which it therefore switches on. */
    void useAgentLoop(WaviAgentLoop loop) {
        agentLoop = loop;
        refreshToolLayer();
        if (agentActive()) {
            log.info("MyHR chat Wavi agent is enabled: Claude selects tools and words answers for authorized organizations");
        }
    }

    private boolean agentActive() {
        return agentLoop != null && agentLoop.enabled();
    }

    /**
     * The agent: Claude understands the message, chooses and chains the registered tools (each gated and executed for
     * the verified actor), and writes the answer from their results. Null means it did not answer (disabled, not
     * authorized, over a limit, a provider failure, or an answer the grounding check rejected), and the existing
     * pipeline answers exactly as before. A policy or general question is handed to that pipeline too, so approved
     * policy text still comes only from the grounded, cited path.
     */
    private MyHrChatResponse agentResponse(String message, Conversation conversation, VerifiedMyHrActor actor, Turn turn) {
        if (!agentActive() || actor == null || toolLayer == null) return null;
        // Names are exchanged for opaque handles for this actor and login session; the people come from records the
        // actor may already see, read fresh every time they are needed.
        WaviAgentLoop.Scope scope = conversation.session() == null ? null
                : new WaviAgentLoop.Scope(conversation.session().fingerprint(), () -> agentPeople(actor));
        WaviAgentLoop.Outcome outcome = agentLoop.run(actor, message, conversation.agentHistory(), turn.previousState,
                LocalDate.now(clock), toolLayer, scope);
        // The verified dialogue state survives a grounding problem: tools that ran still tell the next follow-up what
        // "those" means, whether or not the model's wording could be verified.
        if (outcome.lastTool() != null && outcome.answered()) turn.answeredWith(outcome.lastTool(), outcome.lastArguments());
        if (!outcome.answered()) return null;
        // An exchange the model saw stays usable as external history, but not one that holds data the model never saw
        // (a withheld result, or the tool results themselves) and not one the server filled with real names: those
        // are restricted. Follow-ups still work: the model is given the handle version of the last exchange.
        if (!outcome.localOnly() && !outcome.namesRevealed()) turn.permitExternalUse();
        turn.servedBy(outcome.kind() == WaviAgentLoop.Kind.ANSWERED
                ? (outcome.localOnly() || outcome.namesRevealed() ? "agent-restricted" : "agent") : "agent-fallback");
        turn.provider("claude-agent;" + turn.servedBy);
        MyHrChatResponse response = new MyHrChatResponse(outcome.text());
        return outcome.pageKeys().isEmpty() ? response : response.withActions(outcome.pageKeys());
    }

    /**
     * The people this actor may already see, for exchanging names with opaque handles: themselves and their manager,
     * and for a reviewer everyone in their scope with each one's manager. Read fresh on every call; nothing else is
     * ever hidden or revealed.
     */
    private List<WaviPersonHandles.Person> agentPeople(VerifiedMyHrActor actor) {
        List<WaviPersonHandles.Person> people = new ArrayList<>();
        try {
            var own = employeeManagementService.getOwnDetail(actor.userId());
            if (own != null) {
                people.add(new WaviPersonHandles.Person(own.userId(), own.name()));
                if (own.reportingManager() != null) {
                    people.add(new WaviPersonHandles.Person(own.reportingManager().userId(), own.reportingManager().name()));
                    // Also by name alone: the manager's name appears in text whether or not they are in the review scope.
                    people.add(new WaviPersonHandles.Person(null, own.reportingManager().name()));
                }
            }
        } catch (UnauthorizedException | com.waypoint.exception.BusinessException unavailable) {
            // Nothing to hide from a record the actor cannot read.
        }
        if (isReviewer(actor)) {
            for (var person : teamScope(actor)) {
                people.add(new WaviPersonHandles.Person(person.userId(), person.name()));
                people.add(new WaviPersonHandles.Person(null, person.reportingManagerName()));
            }
        }
        people.removeIf(person -> person.name() == null || person.name().isBlank());
        return people;
    }

    // --- Semantic routing (Phase P2) ----------------------------------------------------------------------------------

    /** Null unless waypoint.myhr.router.semantic.enabled is true (default false) and a provider is approved. */
    private WaviSemanticRouting semanticRouting;
    /**
     * What each conversation last answered (tool + validated arguments), for follow-ups. Server-side only, in memory:
     * bounded (LRU) and expiring. Present while continuation (default on), semantic routing or shadow mode is on.
     */
    private WaviDialogueStore dialogueStore = new WaviDialogueStore(Duration.ofHours(3));
    private WaviMongoDialogueStore sharedDialogueStore;

    /** Shared state is opt-in and only constructed when its shared signing key is configured. */
    @Autowired(required = false)
    void configureSharedDialogueStore(WaviMongoDialogueStore shared) {
        sharedDialogueStore = shared;
        dialogueStore = shared;
        refreshDialogueStore();
    }

    @Autowired
    void configureSemanticRouting(WaviSemanticRouting routing) {
        useSemanticRouting(routing != null && routing.enabled() ? routing : null);
        if (routing != null && routing.shadowEnabled()) useShadowRouting(routing, null);
    }

    /** Null unless waypoint.myhr.router.shadow.enabled is true (default false) and serving is off. */
    private WaviShadowRouting shadowRouting;

    /**
     * Also used by tests (with a direct executor). Shadow mode proposes decisions for a sample of messages and logs
     * how they compare with what answered; it never changes an answer. It needs the tool layer (for the catalog and
     * the gate's dry run) and the dialogue store (so follow-ups are proposed with the same state serving would use).
     */
    void useShadowRouting(WaviSemanticRouting routing, java.util.concurrent.Executor executor) {
        if (routing == null || !routing.shadowEnabled()) {
            shadowRouting = null;
        } else {
            shadowRouting = new WaviShadowRouting(routing, executor,
                    () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
            refreshDialogueStore();
            log.info("MyHR chat semantic routing shadow mode is enabled: proposals are logged, never served");
        }
        refreshToolLayer();
    }

    /**
     * Development diagnostics, OFF by default: when {@code waypoint.myhr.chat.diagnostics.answer-source-header} is true the
     * response carries {@code X-Wavi-Answer-Source: provider=<code>; served=<category>; stage=<stage>} so a developer can
     * tell a Claude answer from a deterministic one or a local fallback (browser dev tools, Network tab). Codes only:
     * never the question, the answer, a record or a credential. A failure here never affects the reply.
     */
    @Value("${waypoint.myhr.chat.diagnostics.answer-source-header:false}")
    private boolean answerSourceHeader;

    void answerSourceHeader(boolean enabled) {
        this.answerSourceHeader = enabled;
    }

    private void publishAnswerSource(Turn turn) {
        if (!answerSourceHeader) return;
        try {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes attributes
                    && attributes.getResponse() != null) {
                attributes.getResponse().setHeader("X-Wavi-Answer-Source", "provider=" + turn.providerUsed
                        + "; served=" + turn.servedBy + "; stage=" + turn.stage);
            }
        } catch (RuntimeException ignored) {
            // diagnostics only
        }
    }

    WaviShadowRouting shadowRouting() {
        return shadowRouting;
    }

    /** Hands the message to shadow mode with what answered it; any problem there never reaches the user. */
    private void shadowObserve(String message, Conversation conversation, VerifiedMyHrActor actor, Turn turn,
                               MyHrChatResponse response) {
        if (shadowRouting == null || actor == null || response == null || toolLayer == null) return;
        try {
            WaviDialogueStore.State answered = turn.dialogue.get();
            WaviShadowRouting.Served served = answered != null && !WaviTools.POLICY_QUESTION.equals(answered.tool())
                    ? new WaviShadowRouting.Served("tool", answered.tool(), answered.arguments())
                    : new WaviShadowRouting.Served(turn.servedBy, null, null);
            WaviDialogueStore.State previous = turn.previousState;
            shadowRouting.observe(actor, message, previous, LocalDate.now(clock), toolLayer, served);
        } catch (RuntimeException failure) {
            log.warn("{} not observed ({})", WaviShadowRouting.SHADOW_MARKER, failure.getClass().getSimpleName());
        }
    }

    /** Also used by tests. Semantic routing answers through the tool layer, so it brings the layer with it. */
    void useSemanticRouting(WaviSemanticRouting routing) {
        semanticRouting = routing;
        refreshDialogueStore();
        refreshToolLayer();
        if (routing != null) {
            log.info("MyHR chat semantic routing is enabled: unrecognised questions may be routed to a Wavi tool");
        }
    }

    WaviDialogueStore dialogueStore() {
        return dialogueStore;
    }

    // --- Follow-up continuation (deterministic) ---------------------------------------------------------------------

    /**
     * Whether a short follow-up may continue the previous answered tool (default true). Deterministic: no provider
     * call; the server-side dialogue store holds only tool names and enum arguments, bounded and expiring.
     */
    private boolean continuationEnabled = true;

    @Autowired
    void configureContinuation(@Value("${waypoint.myhr.chat.continuation.enabled:true}") boolean enabled) {
        useContinuation(enabled);
    }

    /** Also used by tests. */
    void useContinuation(boolean enabled) {
        continuationEnabled = enabled;
        refreshDialogueStore();
    }

    /** The store exists while anything reads it; an existing store (and its conversations) is kept. */
    private void refreshDialogueStore() {
        boolean needed = continuationEnabled || semanticRouting != null || shadowRouting != null;
        if (!needed) {
            dialogueStore = null;
        } else if (dialogueStore == null) {
            dialogueStore = sharedDialogueStore != null ? sharedDialogueStore : new WaviDialogueStore(Duration.ofHours(3));
        }
    }

    /**
     * "When was it submitted?", "and profile changes?": a short follow-up to the previous answer, rewritten from the
     * stamp-verified dialogue state (tool + enum arguments, never browser text) into full questions that the existing
     * routes answer. A candidate counts only when the same tool answered it, so a follow-up never drifts to another
     * capability; every lookup is still scoped by its service to the verified actor. Null when nothing continues.
     */
    private MyHrChatResponse continuationResponse(String message, Conversation conversation, VerifiedMyHrActor actor,
                                                  Turn turn) {
        if (!continuationEnabled || dialogueStore == null || actor == null || conversation == null) return null;
        WaviDialogueStore.State previous = turn.previousState;
        if (previous == null) return null;
        String normalized = normalizeQuestion(message);
        if (actor.role() == UserRole.EMPLOYEE && WaviTools.GET_MY_REQUESTS.equals(previous.tool())
                && WaviTools.RequestKind.PROFILE_CHANGE.name().equals(previous.arguments().get("kind"))
                && ProfileChangeField.PHONE_NUMBER.name().equals(previous.arguments().get("field"))
                && normalized.replaceAll("[?.!]+$", "").matches("is (?:it|that) (?:still )?pending")) {
            var history = profileChangeRequestService.mostRecentOwnHistoryForField(actor.userId(), ProfileChangeField.PHONE_NUMBER);
            rememberOwnRequest(turn, new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN,
                    MyHrOwnRecordIntent.Subject.PROFILE_CHANGE, MyHrOwnRecordIntent.Aspect.STATUS,
                    ProfileChangeField.PHONE_NUMBER, null), actor);
            return new MyHrChatResponse(formatPhoneNumberChangeHistory(PhoneNumberChangeQuestion.PENDING, history));
        }
        boolean afterPeople = WaviTools.GET_DIRECT_REPORTS.equals(previous.tool())
                || WaviTools.GET_TEAM_MEMBER.equals(previous.tool());
        if (afterPeople && isReviewer(actor) && WaviContinuation.words(normalized) <= 12) {
            Integer ordinal = WaviContinuation.ordinal(normalized);
            if (ordinal == null && WaviTools.GET_TEAM_MEMBER.equals(previous.tool())
                    && WaviContinuation.dependsOnContext(normalized) && previous.arguments().get("ordinal") instanceof Number n) {
                ordinal = n.intValue();
            }
            if (ordinal != null) {
                return teamMemberResponse(actor, teamFieldIn(normalized), ordinal, null, turn, true);
            }
        }
        if (WaviTools.GET_MY_PENDING_WORK.equals(previous.tool()) && actor.role() == UserRole.EMPLOYEE
                && (WaviContinuation.refersBack(normalized) || WaviContinuation.asksWhichOnes(normalized))) {
            turn.answeredWith(WaviTools.LIST_MY_REQUESTS, Map.of("kind", WaviTools.RequestListKind.DOCUMENT_REQUEST.name(),
                    "status", WaviTools.RequestStatusFilter.OPEN.name()));
            return myOpenItemsResponse(actor);
        }
        if (WaviTools.GET_MY_REQUESTS.equals(previous.tool()) && actor.role() == UserRole.EMPLOYEE
                && MyHrOwnRecordIntent.Aspect.PENDING.name().equals(previous.arguments().get("aspect"))
                && (WaviContinuation.refersBack(normalized) || WaviContinuation.asksWhichOnes(normalized))) {
            String kind = WaviTools.RequestKind.PROFILE_CHANGE.name().equals(previous.arguments().get("kind"))
                    ? WaviTools.RequestListKind.PROFILE_CHANGE.name() : WaviTools.RequestListKind.DOCUMENT_REQUEST.name();
            turn.answeredWith(WaviTools.LIST_MY_REQUESTS, Map.of("kind", kind,
                    "status", WaviTools.RequestStatusFilter.OPEN.name()));
            return myRequestListResponse(actor, kind, WaviTools.RequestStatusFilter.OPEN.name());
        }
        if (WaviTools.GET_REVIEW_QUEUE.equals(previous.tool()) && WaviContinuation.asksWhichOnes(normalized)
                && (actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN)) {
            WaviTools.ReviewQueue queue = reviewQueueArgument(previous);
            if (queue != null) {
                log.info("{} provider=none continuation=true tool={} detail=LIST", PROVIDER_USED_MARKER,
                        WaviTools.GET_REVIEW_QUEUE);
                turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name(), "detail",
                        WaviTools.ReviewDetail.LIST.name()));
                return reviewQueueItemsResponse(actor, queue);
            }
        }
        if (WaviTools.GET_MY_TIMESHEETS.equals(previous.tool()) && WaviContinuation.asksForHours(normalized)
                && WaviContinuation.words(normalized) <= WaviContinuation.MAX_WORDS) {
            MyHrChatResponse hours = timesheetHoursContinuation(actor, previous, turn);
            if (hours != null) return hours;
        }
        if (WaviTools.GET_MY_TIMESHEETS.equals(previous.tool()) && WaviContinuation.refersBack(normalized)) {
            MyHrChatResponse samePeriod = timesheetPeriodContinuation(actor, previous, normalized, turn);
            if (samePeriod != null) return samePeriod;
        }
        for (String candidate : WaviContinuation.candidates(normalized, previous)) {
            // A separate turn, so a candidate that answers with another tool leaves nothing behind.
            Turn attempt = new Turn();
            MyHrChatResponse answer = deterministicResponse(candidate, actor.userId(), actor, false, attempt,
                    RouteTier.ALL);
            WaviDialogueStore.State answered = attempt.dialogue.get();
            if (answer == null || answered == null || !previous.tool().equals(answered.tool())) continue;
            log.info("{} provider=none continuation=true tool={}", PROVIDER_USED_MARKER, answered.tool());
            // The answer may be a personal record: this turn stays restricted from external use.
            turn.answeredWith(answered.tool(), answered.arguments());
            if (WaviContinuation.asksWhy(normalized) && WaviTools.GET_MY_REQUESTS.equals(answered.tool())
                    && !MyHrOwnRecordIntent.Aspect.REJECTION_REASON.name().equals(answered.arguments().get("aspect"))) {
                // Waypoint records a status, not why something is still open: never let that gap be filled by a guess.
                MyHrChatResponse limited = new MyHrChatResponse(answer.message() + " " + WaviContinuation.WHY_LIMITATION,
                        answer.knowledgeStatus(), answer.citations());
                return answer.actions() == null ? limited : limited.withActions(answer.actions());
            }
            return answer;
        }
        return null;
    }

    /**
     * Resolves a context-dependent field request after Wavi listed direct reports or selected one of them. This is
     * intentionally narrower than general continuation precedence: only a state authenticated for this actor and
     * login session can select an ordinal, and {@link #teamMemberResponse} obtains the current permitted scope again.
     */
    private MyHrChatResponse directReportContinuationResponse(String message, Conversation conversation,
                                                              VerifiedMyHrActor actor, Turn turn) {
        if (dialogueStore == null || conversation == null || actor == null) return null;
        WaviDialogueStore.State previous = turn.previousState;
        if (previous == null || (!WaviTools.GET_DIRECT_REPORTS.equals(previous.tool())
                && !WaviTools.GET_TEAM_MEMBER.equals(previous.tool()))) {
            return null;
        }
        return WaviContinuation.dependsOnContext(normalizeQuestion(message))
                ? continuationResponse(message, conversation, actor, turn) : null;
    }

    /**
     * A message that only makes sense as a continuation ("when was it submitted?", "and profile changes?") when no
     * usable earlier turn exists: the role's clarifying question, instead of a guess by a model or a heuristic that
     * would supply its own question and owner. Null otherwise. A policy question or an explicitly out-of-scope
     * message keeps its own path. Message checks run first, so an ordinary message never touches a provider client.
     */
    private MyHrChatResponse unresolvedContextResponse(String message, Conversation conversation,
                                                       VerifiedMyHrActor actor, Turn turn) {
        if (actor == null || conversation == null || !WaviContinuation.dependsOnContext(normalizeQuestion(message))
                || MyHrScopeGuard.isExplicitlyOutOfScope(message) || MyHrKnowledgeGrounding.isPolicyQuestion(message)
                || hasUsableContext(conversation)) {
            return null;
        }
        log.info("{} provider=none clarification=unresolved-context", PROVIDER_USED_MARKER);
        turn.permitExternalUse();
        return viaTools(actor, turn, WaviRouterDecision.Intent.CLARIFY, WaviTools.CLARIFY, Map.of(),
                () -> new MyHrChatResponse(clarificationReply(actor.role())));
    }

    /**
     * Whether any earlier turn could give a context-dependent message its meaning: stamp-verified history; history the
     * provider path would itself receive ("make it longer" after a draft, with a local model); or, for a caller without
     * a login session (where no stamp can exist), the history as given. With a session, unverified history (edited,
     * forged, expired, another login) is not context.
     */
    private boolean hasUsableContext(Conversation conversation) {
        if (!conversation.verified().isEmpty()) return true;
        if (conversation.raw().isEmpty()) return false;
        if (conversation.session() == null) return true;
        return !generalHistory(conversation).isEmpty();
    }

    private static WaviTools.ReviewQueue reviewQueueArgument(WaviDialogueStore.State state) {
        Object queue = state.arguments().get("queue");
        for (WaviTools.ReviewQueue value : WaviTools.ReviewQueue.values()) {
            if (value.name().equals(queue)) return value;
        }
        return null;
    }

    /**
     * A review-queue question that asks WHO is waiting ("for timesheets, who requires my approval?") gets the list,
     * not the count. Reviewers only; others keep the queue route's own role answer.
     */
    private MyHrChatResponse reviewListIfAsked(String message, VerifiedMyHrActor actor, Turn turn,
                                               WaviTools.ReviewQueue queue) {
        if (queue == null || actor == null || (actor.role() != UserRole.HR && actor.role() != UserRole.COMPANY_ADMIN)
                || !WaviContinuation.asksForPeople(normalizeQuestion(message))) {
            return null;
        }
        turn.answeredWith(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name(), "detail",
                WaviTools.ReviewDetail.LIST.name()));
        return reviewQueueItemsResponse(actor, queue);
    }

    /** Whether a classified queue question asks about what is waiting now (not approved, rejected, completed, where). */
    private static boolean waitingQueue(Enum<?> question) {
        if (question == null) return false;
        String name = question.name();
        return !(name.endsWith("_APPROVED") || name.endsWith("_REJECTED") || name.endsWith("_COMPLETED")
                || name.endsWith("LOCATION") || name.endsWith("_RETURNED"));
    }

    /**
     * People in the reviewer's own authorized scope, resolved from DATA rather than wording: a message that names
     * someone in that scope (full name, or a first or last name that is unambiguous), or a list position / pronoun
     * after a people answer, is answered from that scope before any classifier can treat it as "another person's
     * records". The scope is re-read from the services on every lookup; nothing earlier authorizes anything.
     * Employees and messages naming no one in scope are untouched. Bounded: short messages only.
     */
    private MyHrChatResponse peopleReferenceResponse(String message, Conversation conversation, VerifiedMyHrActor actor,
                                                     Turn turn) {
        if (!isReviewer(actor) || message == null) return null;
        String normalized = normalizeQuestion(message);
        if (WaviContinuation.words(normalized) > 15) return null;
        List<String> tokens = List.of(normalized.split("[^a-z0-9]+"));
        // The user as the subject of a review action ("what did I approve last?", "the last timesheet I reviewed"):
        // their own review history, never their own timesheet's status.
        if (tokens.contains("i")) {
            String decision = null;
            // Past tense only: "I approved", "did I approve". "Do I need to review" is current work.
            boolean did = tokens.contains("did");
            for (String token : tokens) {
                if (token.equals("approved") || (did && token.equals("approve"))) decision = "APPROVED";
                else if (token.equals("rejected") || (did && token.equals("reject"))) decision = "REJECTED";
                else if (decision == null && (token.equals("reviewed") || token.equals("processed")
                        || (did && token.equals("review")))) decision = "ANY";
            }
            if (decision != null && !tokens.contains("how") && !tokens.contains("can") && !tokens.contains("should")) {
                String queue = tokens.contains("profile") ? "PROFILE_CHANGES" : "TIMESHEETS";
                turn.answeredWith(WaviTools.GET_MY_REVIEW_HISTORY, Map.of("queue", queue, "decision", decision));
                return reviewHistoryResponse(actor, queue, decision);
            }
        }
        if (tokens.contains("my") && !tokens.contains("report") && !tokens.contains("reports")) {
            // "my manager", "my timesheet": the user's own record, handled by the own-record routes.
            if (WaviContinuation.ordinal(normalized) == null) return null;
        }
        WaviDialogueStore.State previous = turn.previousState;
        boolean afterPeople = previous != null && (WaviTools.GET_DIRECT_REPORTS.equals(previous.tool())
                || WaviTools.GET_TEAM_MEMBER.equals(previous.tool()));
        Integer ordinal = WaviContinuation.ordinal(normalized);
        if (ordinal != null && (afterPeople || tokens.stream().anyMatch(WaviContinuation.PERSON_NOUNS::contains))) {
            return teamMemberResponse(actor, teamFieldIn(message), ordinal, null, turn, false);
        }
        if (afterPeople && WaviTools.GET_TEAM_MEMBER.equals(previous.tool()) && WaviContinuation.refersBack(normalized)
                && previous.arguments().get("ordinal") instanceof Number selected) {
            return teamMemberResponse(actor, teamFieldIn(message), selected.intValue(), null, turn, false);
        }
        return null;
    }

    /**
     * A message naming someone in the reviewer's authorized scope that no route above answered: answered from that
     * scope. Runs late, so exact routes, navigation and how-to questions never cost a lookup.
     */
    private MyHrChatResponse namedPersonResponse(String message, VerifiedMyHrActor actor, Turn turn) {
        if (!isReviewer(actor) || message == null || !WaviContinuation.hasNameShapedToken(message)
                || MyHrScopeGuard.isExplicitlyOutOfScope(message)
                // "approve Asha's timesheet": a request to act, answered by the action route ("I can't change
                // anything"), never by showing that person's records.
                || ASSISTANT_ACTION_REQUEST.matcher(normalizeQuestion(message)).find()) return null;
        String normalized = normalizeQuestion(message);
        if (WaviContinuation.words(normalized) > 15) return null;
        List<String> tokens = List.of(normalized.split("[^a-z0-9]+"));
        if (tokens.contains("my") && !tokens.contains("report") && !tokens.contains("reports")) return null;
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> scope = teamScope(actor);
        if (namedIn(message, scope, com.waypoint.dto.myhr.EmployeeSummaryResponse::name).isEmpty()
                && byEmployeeId(message, scope) == null) return null;
        if (tokens.contains("timesheet") || tokens.contains("timesheets")) {
            return teamTimesheetsResponse(actor, WaviTools.TimesheetStatusFilter.ANY.name(), message, turn, true);
        }
        // The asked field when the existing profile forms name it; otherwise the work-detail summary, which shows
        // every field chat may show. A name in the authorized set is data, so this never waits for a model.
        return teamMemberResponse(actor, teamFieldIn(message), null, message, turn, true);
    }

    /**
     * What the reviewer has already decided: timesheets they reviewed (from the records' own reviewer and review time)
     * or profile changes they processed, newest first. Never inferred from a status alone; only records the services
     * already let the reviewer see.
     */
    MyHrChatResponse reviewHistoryResponse(VerifiedMyHrActor actor, String queue, String decision) {
        if (!isReviewer(actor)) return new MyHrChatResponse(TIMESHEET_QUEUE_REVIEWERS_ONLY);
        log.info("{} provider=none review-history queue={} decision={}", PROVIDER_USED_MARKER, queue, decision);
        List<Object[]> rows = new ArrayList<>();
        String noun;
        String page;
        if ("PROFILE_CHANGES".equals(queue)) {
            noun = "profile change request";
            page = MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name();
            List<com.waypoint.dto.myhr.ProfileChangeRequestResponse> visible = profileChangeRequestService.listForAdmin(actor.userId());
            for (com.waypoint.dto.myhr.ProfileChangeRequestResponse request : visible == null
                    ? List.<com.waypoint.dto.myhr.ProfileChangeRequestResponse>of() : visible) {
                if (request == null || !actor.userId().equals(request.processedByUserId()) || request.processedAt() == null
                        || request.status() == null || !decisionMatches(request.status().name(), decision)) continue;
                rows.add(new Object[]{request.processedAt(), displayName(request.employeeName()) + " - "
                        + fieldLabel(request.fieldName()) + " change, " + request.status().name().toLowerCase(Locale.ROOT)
                        + " on " + formatDay(request.processedAt().toLocalDate())});
            }
        } else {
            noun = "timesheet";
            page = MyHrConversationIntents.Page.TIMESHEETS.name();
            List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
            for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                    ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
                if (timesheet == null || !actor.userId().equals(timesheet.reviewedByUserId()) || timesheet.reviewedAt() == null
                        || timesheet.status() == null || !decisionMatches(timesheet.status().name(), decision)) continue;
                rows.add(new Object[]{timesheet.reviewedAt(), displayName(timesheet.employeeName()) + " - "
                        + periodText(timesheet.periodStartDate(), timesheet.periodEndDate()) + ", "
                        + timesheet.status().name().toLowerCase(Locale.ROOT) + " on "
                        + formatDay(timesheet.reviewedAt().toLocalDate())});
            }
        }
        rows.sort((a, b) -> ((LocalDateTime) b[0]).compareTo((LocalDateTime) a[0]));
        String label = "APPROVED".equals(decision) ? "approved " : "REJECTED".equals(decision) ? "rejected " : "reviewed ";
        if (rows.isEmpty()) {
            return new MyHrChatResponse("I don't see any " + noun + "s you " + label.strip() + " in Waypoint.")
                    .withActions(List.of(page));
        }
        int shown = Math.min(rows.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder("The ").append(plural(rows.size(), noun)).append(" you ")
                .append(label.strip()).append(", most recent first (").append(rows.size()).append("):");
        for (Object[] row : rows.subList(0, shown)) reply.append("\n- ").append(row[1]);
        if (rows.size() > shown) reply.append("\n...and ").append(rows.size() - shown).append(" more.");
        return new MyHrChatResponse(reply.toString()).withActions(List.of(page));
    }

    private static boolean decisionMatches(String status, String decision) {
        if (decision == null || decision.equals("ANY")) return status.equals("APPROVED") || status.equals("REJECTED");
        return status.equals(decision);
    }

    /**
     * Counts over exactly the people the reviewer may see - the same list Employees / direct reports use - never
     * beyond it: total, per role, the most common job titles and the number of different reporting managers.
     */
    MyHrChatResponse peopleSummaryResponse(VerifiedMyHrActor actor) {
        if (!isReviewer(actor)) return new MyHrChatResponse(WaviTools.PEOPLE_AGGREGATE_REPLY);
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        log.info("{} provider=none people-summary size={}", PROVIDER_USED_MARKER, people.size());
        boolean hr = actor.role() == UserRole.HR;
        String scope = hr ? "active direct " + plural(people.size(), "report") : (people.size() == 1 ? "person" : "people")
                + " in your organization (HR and Employee accounts)";
        List<String> actions = List.of(MyHrConversationIntents.Page.EMPLOYEES.name());
        if (people.isEmpty()) {
            return new MyHrChatResponse(hr ? "No one currently reports to you in Waypoint."
                    : "I don't see any HR or Employee accounts in your organization yet.").withActions(actions);
        }
        java.util.Map<String, Long> roles = new java.util.TreeMap<>();
        java.util.Map<String, Long> titles = new java.util.HashMap<>();
        java.util.Set<String> managers = new java.util.HashSet<>();
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people) {
            roles.merge(roleLabel(person.personType()).replaceFirst("^an? ", ""), 1L, Long::sum);
            if (person.designation() != null && !person.designation().isBlank()) titles.merge(person.designation(), 1L, Long::sum);
            if (person.reportingManagerName() != null && !person.reportingManagerName().isBlank()) {
                managers.add(person.reportingManagerName());
            }
        }
        StringBuilder reply = new StringBuilder(hr ? "You have " : "There are ").append(people.size()).append(' ')
                .append(scope).append('.');
        if (!hr) {
            List<String> parts = new ArrayList<>();
            roles.forEach((role, count) -> parts.add(count + " " + role + (count == 1 ? "" : "s")));
            reply.append(" By role: ").append(String.join(", ", parts)).append('.');
        }
        List<String> topTitles = titles.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(java.util.Map.Entry.comparingByKey()))
                .limit(MAX_LISTED_REVIEW_ITEMS).map(entry -> entry.getKey() + " (" + entry.getValue() + ")").toList();
        if (!topTitles.isEmpty()) reply.append(" Job titles: ").append(String.join(", ", topTitles)).append('.');
        if (!hr) {
            reply.append(' ').append(managers.size()).append(" different ").append(managers.size() == 1 ? "person" : "people")
                    .append(managers.size() == 1 ? " is" : " are").append(" assigned as a reporting manager.");
        }
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    private static boolean isReviewer(VerifiedMyHrActor actor) {
        return actor != null && (actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN);
    }

    /** The people the reviewer can already see: HR - active direct reports; Company Admin - its organization. */
    private List<com.waypoint.dto.myhr.EmployeeSummaryResponse> teamScope(VerifiedMyHrActor actor) {
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = employeeManagementService.list(actor.userId());
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> scoped = new ArrayList<>();
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people == null
                ? List.<com.waypoint.dto.myhr.EmployeeSummaryResponse>of() : people) {
            if (person != null && !actor.userId().equals(person.userId())) scoped.add(person);
        }
        return scoped;
    }

    /**
     * The people of {@code candidates} the message names: a full name first, otherwise a first or last name token. Only
     * names from records the actor may already see are compared, so a message can never reach anyone else.
     */
    private static <T> List<T> namedIn(String message, List<T> candidates, java.util.function.Function<T, String> name) {
        if (message == null) return List.of();
        String text = " " + message.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replaceAll("'s\\b", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        List<T> full = new ArrayList<>();
        List<T> partial = new ArrayList<>();
        for (T candidate : candidates) {
            String value = name.apply(candidate);
            if (value == null || value.isBlank()) continue;
            String normalizedName = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
            if (text.contains(" " + normalizedName + " ")) {
                full.add(candidate);
                continue;
            }
            for (String part : normalizedName.split(" ")) {
                if (part.length() >= 3 && text.contains(" " + part + " ")) {
                    partial.add(candidate);
                    break;
                }
            }
        }
        return full.isEmpty() ? partial : full;
    }

    /** The person in scope whose employee ID appears in the message (exact, case-insensitive), or null. */
    private static com.waypoint.dto.myhr.EmployeeSummaryResponse byEmployeeId(String message,
            List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people) {
        if (message == null) return null;
        String upper = " " + message.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", " ") + " ";
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people) {
            if (person.employeeId() != null && !person.employeeId().isBlank()
                    && upper.contains(" " + person.employeeId().toUpperCase(Locale.ROOT) + " ")) return person;
        }
        return null;
    }

    /** The detail a team-member question asks for, read with the existing own-profile classifier. */
    private String teamFieldIn(String message) {
        if (message == null) return WaviTools.TeamField.SUMMARY.name();
        List<String> tokens = List.of(normalizeQuestion(message).replace('\u2019', '\'').replaceAll("'s\\b", "")
                .split("[^a-z0-9]+"));
        if (tokens.contains("role")) return WaviTools.TeamField.ROLE.name();
        // Each short phrase of the message, longest first, asked in the existing anchored own-profile forms ("what is
        // my <phrase>", "who is my <phrase>"): the field vocabulary stays the profile route's, nothing new is added.
        for (int size = 3; size >= 1; size--) {
            for (int from = 0; from + size <= tokens.size(); from++) {
                String phrase = String.join(" ", tokens.subList(from, from + size)).strip();
                if (phrase.isEmpty()) continue;
                for (String probe : List.of("what is my " + phrase, "who is my " + phrase)) {
                    ProfileQuestion question = classifyProfileQuestion(probe);
                    if (question == null) continue;
                    return switch (question) {
                        case JOB_TITLE -> WaviTools.TeamField.JOB_TITLE.name();
                        case EMPLOYEE_ID -> WaviTools.TeamField.EMPLOYEE_ID.name();
                        case REPORTING_MANAGER -> WaviTools.TeamField.REPORTING_MANAGER.name();
                        case WORK_EMAIL -> WaviTools.TeamField.WORK_EMAIL.name();
                        default -> WaviTools.TeamField.SUMMARY.name();
                    };
                }
            }
        }
        return WaviTools.TeamField.SUMMARY.name();
    }

    /**
     * One person in the reviewer's own scope, by a name in the message or by position in the list just shown. Shows
     * only work details (job title, employee ID, role, reporting manager, work email): never personal contact, pay,
     * dates or documents. Several matches are offered for the user to choose; none found is said plainly.
     */
    MyHrChatResponse teamMemberResponse(VerifiedMyHrActor actor, String field, Integer ordinal, String message, Turn turn,
                                        boolean onlyIfNamed) {
        if (!isReviewer(actor)) return new MyHrChatResponse(OTHER_PERSON_REPLY);
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        com.waypoint.dto.myhr.EmployeeSummaryResponse person = null;
        int index = -1;
        if (ordinal != null) {
            index = ordinal == -1 ? people.size() - 1 : ordinal - 1;
            if (index < 0 || index >= people.size()) {
                return new MyHrChatResponse("I can't tell who that is: the list has " + people.size() + " "
                        + plural(people.size(), "person") + ". Ask \"Who reports to me?\" to see it again.");
            }
            person = people.get(index);
        } else {
            com.waypoint.dto.myhr.EmployeeSummaryResponse byId = byEmployeeId(message, people);
            List<com.waypoint.dto.myhr.EmployeeSummaryResponse> matches = byId != null ? List.of(byId)
                    : namedIn(message, people, com.waypoint.dto.myhr.EmployeeSummaryResponse::name);
            if (matches.isEmpty()) {
                if (onlyIfNamed) return null;
                return new MyHrChatResponse("I couldn't find that person among the people you can see in Waypoint ("
                        + (actor.role() == UserRole.HR ? "your active direct reports" : "your organization")
                        + "). Check the name, or open Employees.").withActions(List.of(MyHrConversationIntents.Page.EMPLOYEES.name()));
            }
            if (matches.size() > 1) {
                List<String> names = matches.stream().limit(MAX_LISTED_REVIEW_ITEMS)
                        .map(match -> displayName(match.name()) + (match.designation() == null ? ""
                                : " (" + match.designation() + ")")).toList();
                return new MyHrChatResponse("More than one person matches: " + String.join("; ", names)
                        + ". Which one do you mean? You can use their full name.");
            }
            person = matches.getFirst();
            index = people.indexOf(person);
        }
        log.info("{} provider=none team-member field={}", PROVIDER_USED_MARKER, field);
        if (turn != null) {
            Map<String, Object> state = new java.util.LinkedHashMap<>(Map.of("field", field));
            state.putAll(personState(new ScopedPerson(person, index + 1)));
            turn.answeredWith(WaviTools.GET_TEAM_MEMBER, state);
        }
        String name = displayName(person.name());
        String reply = switch (field) {
            case "JOB_TITLE" -> blankOr(person.designation(), name + " has no job title recorded.",
                    name + "'s job title is " + person.designation() + ".");
            case "EMPLOYEE_ID" -> blankOr(person.employeeId(), name + " has no employee ID recorded.",
                    name + "'s employee ID is " + person.employeeId() + ".");
            case "ROLE" -> name + " is " + roleLabel(person.personType())
                    + (person.designation() == null || person.designation().isBlank() ? "."
                    : ", with the job title " + person.designation() + ".");
            case "REPORTING_MANAGER" -> blankOr(person.reportingManagerName(), name
                    + " has no active reporting manager recorded.", name + " reports to " + person.reportingManagerName() + ".");
            case "WORK_EMAIL" -> blankOr(person.workEmail(), name + " has no work email recorded.",
                    name + "'s work email is " + person.workEmail() + ".");
            default -> name + ": " + displayField("Employee ID", person.employeeId()) + "; "
                    + displayField("Job title", person.designation()) + "; Role: " + roleLabel(person.personType())
                    + "; " + displayField("Reporting manager", person.reportingManagerName()) + ".";
        };
        return new MyHrChatResponse(reply).withActions(List.of(MyHrConversationIntents.Page.EMPLOYEES.name()));
    }

    private static String blankOr(String value, String whenBlank, String whenPresent) {
        return value == null || value.isBlank() ? whenBlank : whenPresent;
    }

    private static String roleLabel(String personType) {
        if (personType == null || personType.isBlank()) return "a Waypoint user";
        return switch (personType.toUpperCase(Locale.ROOT)) {
            case "HR" -> "an HR user";
            case "COMPANY_ADMIN" -> "a Company Admin";
            default -> "an Employee";
        };
    }

    /** A person resolved inside the reviewer's CURRENT authorized scope, with their 1-based position in it. */
    private record ScopedPerson(com.waypoint.dto.myhr.EmployeeSummaryResponse person, int ordinal) { }

    /** The outcome of resolving "which person": a person, nobody named ({@code person} null), or a reply to give. */
    private record PersonResolution(ScopedPerson person, MyHrChatResponse problem) {
        static final PersonResolution NONE = new PersonResolution(null, null);
    }

    /**
     * Which person in the reviewer's scope a question is about. A name or Waypoint employee ID in the message wins;
     * otherwise {@code ordinal}, a position in the CURRENT scope list (so a stale or forged position can never reach
     * anyone outside it). Several matching names, or a position outside the list, give a reply instead.
     */
    private PersonResolution resolvePerson(List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people, String message,
                                           Integer ordinal) {
        return resolvePerson(people, message, ordinal, null);
    }

    /** The stored-state key of the person reference; never shown to a router (see {@link WaviRouterPrompt}). */
    static final String PERSON_REFERENCE = "personRef";

    /** An opaque, keyed digest of a person's user id, so a stored position can be checked against the same person. */
    private String personReference(String userId) {
        return historyProvenance.personReference(userId);
    }

    /** The follow-up state for one selected person: their position now, and a reference that pins who it was. */
    private Map<String, Object> personState(ScopedPerson person) {
        return Map.of("ordinal", person.ordinal(), PERSON_REFERENCE, personReference(person.person().userId()));
    }

    /**
     * As above; {@code expectedReference}, from a stored state, pins WHO the position meant. If the scope list changed
     * and the person moved, they are found by reference; if they are no longer in scope, the user is told so, rather
     * than answering about whoever now holds that position.
     */
    private PersonResolution resolvePerson(List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people, String message,
                                           Integer ordinal, String expectedReference) {
        if (expectedReference != null && message == null) {
            for (int index = 0; index < people.size(); index++) {
                if (expectedReference.equals(personReference(people.get(index).userId()))) {
                    return new PersonResolution(new ScopedPerson(people.get(index), index + 1), null);
                }
            }
            return new PersonResolution(null, new MyHrChatResponse("The person from the earlier answer is no longer "
                    + "among the people you can see in Waypoint. Please name the person you mean."));
        }
        com.waypoint.dto.myhr.EmployeeSummaryResponse byId = byEmployeeId(message, people);
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> named = byId != null ? List.of(byId)
                : namedIn(message, people, com.waypoint.dto.myhr.EmployeeSummaryResponse::name);
        if (named.size() > 1) {
            return new PersonResolution(null, new MyHrChatResponse("More than one person matches: " + String.join("; ",
                    named.stream().limit(MAX_LISTED_REVIEW_ITEMS).map(person -> displayName(person.name())).toList())
                    + ". Which one do you mean? You can use their full name."));
        }
        if (named.size() == 1) {
            return new PersonResolution(new ScopedPerson(named.getFirst(), people.indexOf(named.getFirst()) + 1), null);
        }
        if (ordinal == null) return PersonResolution.NONE;
        int index = ordinal == -1 ? people.size() - 1 : ordinal - 1;
        if (index < 0 || index >= people.size()) {
            return new PersonResolution(null, new MyHrChatResponse("I can't tell who that is any more: your list has "
                    + people.size() + " " + plural(people.size(), "person") + ". Please name the person."));
        }
        return new PersonResolution(new ScopedPerson(people.get(index), index + 1), null);
    }

    /**
     * Timesheets in the reviewer's own review scope (the scope of the review queue: HR - timesheets naming them as
     * reviewer from their ACTIVE direct reports; Company Admin - the organization), narrowed by the typed query:
     * one person (named, or by verified position), one status, and one day/week/month (records whose work period
     * overlaps it). Each row is one SUBMISSION: Waypoint stores every submission or resubmission as its own record,
     * so two rows can share a period; they are told apart by submission time and hours. With {@code hours}, the hours
     * worked within the period are given per submission and never added together where submissions cover the same
     * days. {@code onlyIfNamed}: null unless the message (or the query) selects someone in scope.
     */
    MyHrChatResponse teamTimesheetsResponse(VerifiedMyHrActor actor, WaviTeamTimesheetQuery query, String message,
                                            Turn turn, boolean onlyIfNamed) {
        return teamTimesheetsResponse(actor, query, message, turn, onlyIfNamed,
                teamViewOf(message == null ? null : normalizeQuestion(message), query));
    }

    MyHrChatResponse teamTimesheetsResponse(VerifiedMyHrActor actor, WaviTeamTimesheetQuery query, String message,
                                            Turn turn, boolean onlyIfNamed, TeamView view) {
        if (!isReviewer(actor)) return new MyHrChatResponse(TIMESHEET_QUEUE_REVIEWERS_ONLY);
        boolean hr = actor.role() == UserRole.HR;
        // The person first, from the people the reviewer may see NOW; only then their timesheets.
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        // Several people named in full: exactly those people, each still from the current scope.
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> several = query.ordinal() == null
                ? fullyNamed(message, people) : List.of();
        java.util.Set<String> severalIds = new java.util.HashSet<>();
        if (several.size() > 1) several.forEach(person -> severalIds.add(person.userId()));
        PersonResolution resolution = severalIds.isEmpty() ? resolvePerson(people, message, query.ordinal())
                : PersonResolution.NONE;
        if (resolution.problem() != null) return resolution.problem();
        ScopedPerson chosen = resolution.person();
        if (chosen == null && severalIds.isEmpty() && onlyIfNamed) return null;
        java.util.Set<String> reports = hr ? activeDirectReportIds(actor) : null;
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> scoped = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
            if (timesheet == null || actor.userId().equals(timesheet.employeeUserId())
                    || timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT) continue;
            if (hr && (!actor.userId().equals(timesheet.reportingManagerUserId())
                    || !reports.contains(timesheet.employeeUserId()))) continue;
            scoped.add(timesheet);
        }
        String status = "NEEDS_ACTION".equals(query.status())
                // Reviewers never see drafts, so "needs action" in their view is a rejection the EMPLOYEE must fix.
                ? WaviTools.TimesheetStatusFilter.REJECTED.name() : query.status();
        MyHrConversationIntents.TimesheetRange range = query.range();
        List<com.waypoint.dto.myhr.TimesheetResponse> filtered = scoped.stream()
                .filter(timesheet -> chosen == null || chosen.person().userId().equals(timesheet.employeeUserId()))
                .filter(timesheet -> severalIds.isEmpty() || severalIds.contains(timesheet.employeeUserId()))
                .filter(timesheet -> statusMatches(timesheet.status(), status))
                .filter(timesheet -> range == null || overlaps(timesheet, range.from(), range.to()))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>reverseOrder()))
                        .thenComparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        log.info("{} provider=none team-timesheets status={} named={} period={} hours={}", PROVIDER_USED_MARKER, status,
                chosen != null, range != null, query.hours());
        if (turn != null) turn.answeredWith(WaviTools.GET_TEAM_TIMESHEETS, teamTimesheetState(status, range, query.hours(),
                chosen));
        String who = chosen != null ? displayName(chosen.person().name())
                : !severalIds.isEmpty() ? String.join(" and ", several.stream().limit(MAX_LISTED_REVIEW_ITEMS)
                        .map(person -> displayName(person.name())).toList())
                : (hr ? "your review scope" : "your organization");
        String when = range == null ? "" : " covering " + range.label()
                + (range.singleDay() || range.unit() == MyHrConversationIntents.TimesheetRange.Unit.MONTH ? ""
                : " (" + formatDay(range.from()) + " to " + formatDay(range.to()) + ")");
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (filtered.isEmpty()) {
            StringBuilder none = new StringBuilder("I don't see any " + teamStatusWord(status) + "timesheets for " + who
                    + when + ".");
            if (!"ANY".equals(status)) {
                // "Was Ravi's timesheet for last week approved?" - say what IS there in other statuses, so "none
                // approved" is not mistaken for "nothing submitted".
                List<com.waypoint.dto.myhr.TimesheetResponse> others = scoped.stream()
                        .filter(timesheet -> chosen == null || chosen.person().userId().equals(timesheet.employeeUserId()))
                        .filter(timesheet -> severalIds.isEmpty() || severalIds.contains(timesheet.employeeUserId()))
                        .filter(timesheet -> range == null || overlaps(timesheet, range.from(), range.to()))
                        .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                        .limit(MAX_LISTED_REVIEW_ITEMS).toList();
                if (!others.isEmpty() && (chosen != null || range != null)) {
                    none.append(" In other statuses I see:");
                    for (com.waypoint.dto.myhr.TimesheetResponse timesheet : others) {
                        none.append("\n- ").append(chosen == null ? displayName(timesheet.employeeName()) + " - " : "")
                                .append(periodText(timesheet.periodStartDate(), timesheet.periodEndDate())).append(": ")
                                .append(reviewerStatusPhrase(timesheet.status())).append(", ")
                                .append(submittedText(timesheet.submittedAt()));
                    }
                }
            }
            return new MyHrChatResponse(none.toString()).withActions(actions);
        }
        if (chosen == null && severalIds.isEmpty() && view == TeamView.LATEST_BY_SUBMISSION) {
            // One record from the whole result set: the latest by its recorded submission time, with whose it is.
            com.waypoint.dto.myhr.TimesheetResponse latest = filtered.stream()
                    .filter(timesheet -> timesheet.submittedAt() != null)
                    .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt))
                    .orElse(filtered.getFirst());
            String kind = WaviTools.TimesheetStatusFilter.SUBMITTED.name().equals(status) ? "timesheet awaiting review"
                    : teamStatusWord(status) + "timesheet";
            if (turn != null) {
                // Remember that one record was selected, so "how many hours is it?" continues it.
                Map<String, Object> state = new java.util.LinkedHashMap<>(teamTimesheetState(status, range, false, null));
                state.put(TEAM_VIEW, TeamView.LATEST_BY_SUBMISSION.name());
                turn.answeredWith(WaviTools.GET_TEAM_TIMESHEETS, state);
            }
            return new MyHrChatResponse("The most recently submitted " + kind + when
                    + " in " + who + " is " + displayName(latest.employeeName()) + "'s, for "
                    + periodText(latest.periodStartDate(), latest.periodEndDate()) + " (" + submittedText(latest.submittedAt())
                    + "; " + reviewerStatusPhrase(latest.status())
                    + (latest.totalHours() == null ? "" : "; " + hoursText(latest.totalHours())) + ")."
                    + (filtered.size() > 1 ? " It is one of " + filtered.size() + " matching submissions." : ""))
                    .withActions(actions);
        }
        if (chosen != null && (view == TeamView.LATEST_BY_PERIOD || view == TeamView.LATEST_BY_SUBMISSION)) {
            // One record: the newest by work period (the list order), or the most recently submitted.
            com.waypoint.dto.myhr.TimesheetResponse latest = view == TeamView.LATEST_BY_PERIOD ? filtered.getFirst()
                    : filtered.stream().filter(timesheet -> timesheet.submittedAt() != null)
                    .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt))
                    .orElse(filtered.getFirst());
            return new MyHrChatResponse(who + "'s " + (view == TeamView.LATEST_BY_PERIOD
                    ? "latest " + teamStatusWord(status) + "timesheet (by work period)"
                    : "most recently submitted " + teamStatusWord(status) + "timesheet") + ": "
                    + periodText(latest.periodStartDate(), latest.periodEndDate()) + ", "
                    + reviewerStatusPhrase(latest.status()) + ", " + submittedText(latest.submittedAt())
                    + (latest.totalHours() == null ? "" : ", " + hoursText(latest.totalHours()))
                    + (filtered.size() > 1 ? ". They have " + filtered.size() + " " + teamStatusWord(status)
                    + "timesheets in total." : ".")).withActions(actions);
        }
        if (query.hours() && view == TeamView.DAILY && chosen != null && range != null) {
            return new MyHrChatResponse(teamDailyHoursReply(who + when, filtered, range)).withActions(actions);
        }
        // Someone named in full who has no matching record is said so, not silently left out.
        List<String> absent = several.size() < 2 ? List.of() : several.stream()
                .filter(person -> filtered.stream().noneMatch(timesheet -> person.userId().equals(timesheet.employeeUserId())))
                .map(person -> displayName(person.name())).toList();
        String absentNote = absent.isEmpty() ? "" : "\nNo matching timesheets for " + String.join(" or ", absent) + ".";
        if (query.hours()) return new MyHrChatResponse(teamHoursReply(who + when, filtered, range, chosen == null)
                + absentNote).withActions(actions);
        int shown = Math.min(filtered.size(), MAX_LISTED_REVIEW_ITEMS);
        List<String> lines = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : filtered.subList(0, shown)) {
            lines.add((chosen == null ? displayName(timesheet.employeeName()) + " - " : "")
                    + periodText(timesheet.periodStartDate(), timesheet.periodEndDate()) + ": "
                    + reviewerStatusPhrase(timesheet.status()) + ", " + submittedText(timesheet.submittedAt())
                    + (timesheet.totalHours() == null ? "" : ", " + hoursText(timesheet.totalHours())));
        }
        StringBuilder reply = new StringBuilder().append(filtered.size()).append(' ').append(teamStatusWord(status))
                .append(plural(filtered.size(), "timesheet")).append(" for ").append(who).append(when).append(':');
        appendDistinctSubmissions(reply, lines, filtered.subList(0, shown));
        if (filtered.size() > shown) reply.append("\n...and ").append(filtered.size() - shown).append(" more.");
        reply.append(absentNote);
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** The message's own team-timesheet question: its period, hours wording and status words, read with the same
     * parsers the user's own timesheet routes use. */
    MyHrChatResponse teamTimesheetsResponse(VerifiedMyHrActor actor, String status, String message, Turn turn,
                                            boolean onlyIfNamed) {
        return teamTimesheetsResponse(actor, teamQueryFrom(message, status, null), message, turn, onlyIfNamed);
    }

    /** Status, period and hours from the message; {@code status} wins unless it is ANY. */
    private WaviTeamTimesheetQuery teamQueryFrom(String message, String status, Integer ordinal) {
        String normalized = message == null ? "" : normalizeQuestion(message);
        LocalDate today = LocalDate.now(clock);
        // The subject (a person in the reviewer's scope) is resolved separately; only the period is read here.
        MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn(
                WaviContinuation.withoutQuantityInterrogative(normalized), today);
        // "What did Ravi work between Oct 5 and Oct 18?" asks for hours without naming a unit.
        boolean hours = WaviContinuation.asksForHours(normalized)
                || (range != null && WaviContinuation.asksWorkDone(normalized))
                || asksUnitlessTotal(normalized, message);
        String filter = status;
        if (filter == null || WaviTools.TimesheetStatusFilter.ANY.name().equals(filter)) {
            String named = WaviContinuation.timesheetStatusFilter(normalized);
            filter = named == null ? WaviTools.TimesheetStatusFilter.ANY.name() : named;
        }
        return new WaviTeamTimesheetQuery(filter, range, hours, ordinal);
    }

    /** The state a team-timesheet answer leaves for follow-ups: status, period, detail and person position only. */
    private Map<String, Object> teamTimesheetState(String status, MyHrConversationIntents.TimesheetRange range,
                                                   boolean hours, ScopedPerson person) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("status", status);
        if (range != null) {
            Map<String, Object> period = WaviTimesheetPeriod.arguments(range, LocalDate.now(clock));
            if (period != null) {
                period.forEach((name, value) -> { if (!"detail".equals(name)) state.put(name, value); });
            }
        }
        state.put("detail", hours ? WaviTools.TeamTimesheetDetail.HOURS.name() : WaviTools.TeamTimesheetDetail.LIST.name());
        if (person != null) state.putAll(personState(person));
        return state;
    }

    private static boolean overlaps(com.waypoint.dto.myhr.TimesheetResponse timesheet, LocalDate from, LocalDate to) {
        return timesheet.periodStartDate() != null && timesheet.periodEndDate() != null
                && !timesheet.periodEndDate().isBefore(from) && !timesheet.periodStartDate().isAfter(to);
    }

    /**
     * Hours worked within the period, per submission, from each submission's own daily entries. Days that appear on
     * more than one submission are never added together (a resubmission or correction covers the same days), so a
     * total is given only when no day is shared. These records are not an official payable total.
     */
    /**
     * One person's hours day by day within the period, from each submission's own entries. A day that appears on
     * more than one submission shows every recorded value with its submission's status, never their sum.
     */
    private String teamDailyHoursReply(String subject, List<com.waypoint.dto.myhr.TimesheetResponse> records,
                                       MyHrConversationIntents.TimesheetRange range) {
        java.util.Map<LocalDate, List<String>> byDay = new java.util.TreeMap<>();
        java.util.Map<LocalDate, java.math.BigDecimal> single = new java.util.HashMap<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : records) {
            for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                    ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                if (entry == null || entry.workDate() == null || entry.hoursWorked() == null
                        || entry.workDate().isBefore(range.from()) || entry.workDate().isAfter(range.to())) continue;
                byDay.computeIfAbsent(entry.workDate(), day -> new ArrayList<>()).add(hoursText(entry.hoursWorked())
                        + " (" + reviewerStatusPhrase(timesheet.status()) + ", " + submittedText(timesheet.submittedAt()) + ")");
                single.put(entry.workDate(), entry.hoursWorked());
            }
        }
        if (byDay.isEmpty()) {
            return "I don't see daily hours entered for " + subject + " on the submissions I can see.";
        }
        StringBuilder reply = new StringBuilder("Hours per day for ").append(subject).append(':');
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        boolean shared = false;
        for (java.util.Map.Entry<LocalDate, List<String>> day : byDay.entrySet()) {
            reply.append("\n- ").append(formatDay(day.getKey())).append(": ");
            if (day.getValue().size() == 1) {
                reply.append(hoursText(single.get(day.getKey())));
                total = total.add(single.get(day.getKey()));
            } else {
                shared = true;
                reply.append(String.join("; ", day.getValue()));
            }
        }
        reply.append(shared ? "\nSome days appear on more than one submission (separate submissions for the same dates), so "
                + "each value is shown and they are not added together. These records do not establish an official payable total."
                : "\nTotal: " + hoursText(total) + ".");
        return reply.toString();
    }

    private String teamHoursReply(String subject, List<com.waypoint.dto.myhr.TimesheetResponse> records,
                                  MyHrConversationIntents.TimesheetRange range, boolean showNames) {
        int shown = Math.min(records.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder("Hours recorded for ").append(subject).append(':');
        java.util.Map<String, java.math.BigDecimal> perPerson = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> personNames = new java.util.HashMap<>();
        java.util.Map<String, Integer> recordsPerPersonDay = new java.util.HashMap<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        boolean complete = true;
        List<String> lines = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : records.subList(0, shown)) {
            java.math.BigDecimal hours = java.math.BigDecimal.ZERO;
            int days = 0;
            List<com.waypoint.dto.myhr.TimesheetEntryResponse> entries = timesheet.entries() == null
                    ? List.of() : timesheet.entries();
            for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : entries) {
                if (entry == null || entry.workDate() == null || entry.hoursWorked() == null) continue;
                if (range != null && (entry.workDate().isBefore(range.from()) || entry.workDate().isAfter(range.to()))) continue;
                hours = hours.add(entry.hoursWorked());
                days++;
                recordsPerPersonDay.merge(timesheet.employeeUserId() + "/" + entry.workDate(), 1, Integer::sum);
            }
            String amount;
            if (entries.isEmpty() && range == null && timesheet.totalHours() != null) {
                hours = timesheet.totalHours();
                amount = hoursText(hours) + " (total recorded on the submission)";
            } else if (entries.isEmpty()) {
                complete = false;
                amount = "no daily entries recorded, so the hours for these dates are unavailable";
            } else {
                amount = hoursText(hours) + (days == 0 ? " (no hours entered on these days)"
                        : " on " + days + " " + plural(days, "day"));
            }
            total = total.add(hours);
            perPerson.merge(String.valueOf(timesheet.employeeUserId()), hours, java.math.BigDecimal::add);
            personNames.putIfAbsent(String.valueOf(timesheet.employeeUserId()), displayName(timesheet.employeeName()));
            lines.add((showNames ? displayName(timesheet.employeeName()) + " - " : "")
                    + periodText(timesheet.periodStartDate(), timesheet.periodEndDate()) + " ("
                    + reviewerStatusPhrase(timesheet.status()) + ", " + submittedText(timesheet.submittedAt()) + "): " + amount);
        }
        appendDistinctSubmissions(reply, lines, records.subList(0, shown));
        long shared = recordsPerPersonDay.values().stream().filter(count -> count > 1).count();
        if (records.size() > shown) {
            reply.append("\n...and ").append(records.size() - shown).append(" more submissions; open Timesheets to see them.");
        } else if (shared > 0) {
            reply.append("\n").append(shared).append(' ').append(plural((int) shared, "day")).append(shared == 1 ? " appears"
                    : " appear").append(" on more than one submission (separate submissions "
                    + "for the same dates), so I haven't added them together. These records do not establish an official "
                    + "payable total.");
        } else if (shown > 1 && complete && !showNames) {
            reply.append("\nTotal across these submissions: ").append(hoursText(total)).append('.');
        }
        if (showNames && complete && records.size() <= shown && perPerson.size() > 1) {
            // Per person: a total only for someone none of whose days is on two submissions.
            List<String> totals = new ArrayList<>();
            for (java.util.Map.Entry<String, java.math.BigDecimal> person : perPerson.entrySet()) {
                boolean overlapping = recordsPerPersonDay.entrySet().stream()
                        .anyMatch(day -> day.getValue() > 1 && day.getKey().startsWith(person.getKey() + "/"));
                totals.add(personNames.get(person.getKey()) + ": " + (overlapping
                        ? "not totalled (overlapping submissions)" : hoursText(person.getValue())));
            }
            reply.append("\nPer person: ").append(String.join("; ", totals)).append('.');
        }
        return reply.toString();
    }

    /**
     * Appends one line per submission. Where several submissions share a person and period (a resubmission is a
     * separate record), each is marked "submission k of n for this period" so identical-looking rows stay distinct.
     */
    private static void appendDistinctSubmissions(StringBuilder reply, List<String> lines,
                                                  List<com.waypoint.dto.myhr.TimesheetResponse> records) {
        java.util.Map<String, Integer> totals = new java.util.HashMap<>();
        for (com.waypoint.dto.myhr.TimesheetResponse record : records) totals.merge(submissionGroup(record), 1, Integer::sum);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String group = submissionGroup(records.get(index));
            int count = totals.get(group);
            reply.append("\n- ").append(lines.get(index));
            if (count > 1) {
                // Newest first, so the first one listed is the most recent submission for that period.
                int position = seen.merge(group, 1, Integer::sum);
                reply.append(" [submission ").append(position).append(" of ").append(count).append(" for this period")
                        .append(position == 1 ? ", most recent" : "").append(']');
            }
        }
    }

    private static String submissionGroup(com.waypoint.dto.myhr.TimesheetResponse record) {
        return record.employeeUserId() + "/" + record.periodStartDate() + "/" + record.periodEndDate();
    }

    private static final DateTimeFormatter SUBMITTED_AT = DateTimeFormatter.ofPattern("EEE MMM d, yyyy 'at' HH:mm", Locale.US);

    private static String submittedText(LocalDateTime submittedAt) {
        return submittedAt == null ? "no submission time recorded" : "submitted " + submittedAt.format(SUBMITTED_AT);
    }

    private static String hoursText(java.math.BigDecimal hours) {
        String value = hours.stripTrailingZeros().toPlainString();
        return value + (hours.compareTo(java.math.BigDecimal.ONE) == 0 ? " hour" : " hours");
    }

    /** A timesheet's status as the REVIEWER sees it: who has to act next. */
    private static String reviewerStatusPhrase(com.waypoint.enums.TimesheetStatus status) {
        if (status == null) return "no status recorded";
        return switch (status) {
            case SUBMITTED -> "submitted, awaiting review";
            case APPROVED -> "approved";
            case REJECTED -> "rejected, returned to the employee to correct and resubmit";
            case DRAFT -> "draft, not submitted";
        };
    }

    /** Status filter words in the reviewer's view (REJECTED waits for the employee, SUBMITTED for the reviewer). */
    private static String teamStatusWord(String filter) {
        return switch (filter == null ? "ANY" : filter) {
            case "SUBMITTED" -> "submitted (awaiting review) ";
            case "APPROVED" -> "approved ";
            case "REJECTED", "NEEDS_ACTION" -> "rejected (waiting for the employee to correct and resubmit) ";
            case "DRAFT" -> "draft ";
            default -> "";
        };
    }

    private static boolean statusMatches(com.waypoint.enums.TimesheetStatus status, String filter) {
        if (filter == null || filter.equals("ANY")) return true;
        if (status == null) return false;
        if (filter.equals("NEEDS_ACTION")) {
            return status == com.waypoint.enums.TimesheetStatus.DRAFT || status == com.waypoint.enums.TimesheetStatus.REJECTED;
        }
        return status.name().equals(filter);
    }

    private static String statusWord(String filter) {
        return switch (filter == null ? "ANY" : filter) {
            case "NEEDS_ACTION" -> "action-needed ";
            case "DRAFT" -> "draft ";
            case "SUBMITTED" -> "submitted (awaiting approval) ";
            case "APPROVED" -> "approved ";
            case "REJECTED" -> "rejected ";
            default -> "";
        };
    }

    /**
     * The user's own timesheets, newest first, with one status or those needing the user's action (drafts not yet
     * submitted and rejected ones to correct), including any reviewer comment on a rejection.
     */
    MyHrChatResponse myTimesheetListResponse(VerifiedMyHrActor actor, String status) {
        return myTimesheetListResponse(actor, status, null);
    }

    /**
     * The user's own review decisions: when each timesheet was approved or rejected and, where the record shows it,
     * by whom. Only what the records hold: the reviewer is named when it is the reporting manager on the record;
     * otherwise no name is given. {@code latestOnly}: the newest timesheet (by work period) only.
     */
    private MyHrChatResponse ownReviewDecisionsResponse(VerifiedMyHrActor actor, String decision,
                                                        MyHrConversationIntents.TimesheetRange range, boolean latestOnly) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                        && timesheet.periodStartDate() != null && timesheet.periodEndDate() != null)
                .filter(timesheet -> range == null || overlaps(timesheet, range.from(), range.to()))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate).reversed())
                .toList();
        if (latestOnly && !own.isEmpty()) own = List.of(own.getFirst());
        log.info("{} provider=none own-review-decisions decision={} period={} latest={}", PROVIDER_USED_MARKER, decision,
                range != null, latestOnly);
        String when = range == null ? "" : " covering " + range.label();
        if (own.isEmpty()) {
            return new MyHrChatResponse("I don't see a timesheet of yours" + when + ".").withActions(actions);
        }
        List<String> lines = new ArrayList<>();
        int matching = 0;
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : own.subList(0, Math.min(own.size(), MAX_LISTED_REVIEW_ITEMS))) {
            boolean decided = timesheet.status() == com.waypoint.enums.TimesheetStatus.APPROVED
                    || timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED;
            String line = periodText(timesheet.periodStartDate(), timesheet.periodEndDate()) + ": ";
            if (!decided) {
                line += "not reviewed yet (" + (timesheet.status() == null ? "no status recorded"
                        : timesheet.status().name().toLowerCase(Locale.ROOT)) + ")";
            } else {
                if (decisionMatches(timesheet.status().name(), decision)) matching++;
                String reviewer = timesheet.reviewedByUserId() != null
                        && timesheet.reviewedByUserId().equals(timesheet.reportingManagerUserId())
                        && !isBlank(timesheet.reportingManagerName()) ? " by " + displayName(timesheet.reportingManagerName()) : "";
                line += timesheet.status().name().toLowerCase(Locale.ROOT) + reviewer + (timesheet.reviewedAt() == null
                        ? "; the review date isn't recorded" : " on " + formatDay(timesheet.reviewedAt().toLocalDate()));
                if (reviewer.isEmpty()) line += "; the reviewer's name isn't shown on the record";
            }
            lines.add(line);
        }
        String asked = "APPROVED".equals(decision) ? "approved" : "REJECTED".equals(decision) ? "rejected" : "reviewed";
        StringBuilder reply = new StringBuilder(matching == 0
                ? "None of your timesheets" + when + (latestOnly ? " (your latest)" : "") + " has been " + asked + ". What I see:"
                : "Review decisions on your timesheet" + (own.size() == 1 ? "" : "s") + when + ":");
        lines.forEach(line -> reply.append("\n- ").append(line));
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** Whether a total is asked without a unit, where hours are the only thing Wavi can total. */
    private boolean asksUnitlessTotal(String normalized, String message) {
        if (!WaviContinuation.asksBareTotal(normalized)) return false;
        WaviTools.WorkArea area = WaviContinuation.namedKind(normalized);
        if (area != null && area != WaviTools.WorkArea.TIMESHEETS) return false;
        // A count ("total number of timesheets"), a detail Wavi never shows (pay, leave balance) or a policy
        // question is not a total of hours.
        if (WaviContinuation.asksCount(normalized) && !WaviContinuation.asksForHours(normalized)) return false;
        return MyHrQuestionInterpreter.unsupportedField(message) == null && !MyHrKnowledgeGrounding.isPolicyQuestion(message)
                && !MyHrScopeGuard.isExplicitlyOutOfScope(message);
    }

    /**
     * A status or existence question about the user's own request of one kind, whatever the request is called: the
     * kind comes from the request's subject (a profile field or change - profile change; a letter, certificate or
     * document - document request). Null when the message is not about the user's own request.
     */
    private MyHrChatResponse ownRequestStatusResponse(VerifiedMyHrActor actor, String normalized, List<String> tokens,
                                                      Turn turn) {
        if (!WaviContinuation.hasFirstPerson(normalized) || WaviContinuation.isHowTo(normalized)
                || WaviContinuation.isConditional(normalized) || WaviContinuation.namesThirdParty(normalized)
                || ASSISTANT_ACTION_REQUEST.matcher(normalized).find()) return null;
        if (tokens.stream().noneMatch(token -> token.startsWith("request"))) return null;
        // A fallback only: wording an established own-record or queue route already recognises keeps that route,
        // which answers more precisely (the latest request's dates, a field's history, rejection reasons, counts).
        if (ownRecordIntent(normalized) != null || hasQueueCountRoute(normalized)
                || classifyDocumentRequestQueueQuestion(normalized) != null
                || classifyProfileChangeQueueQuestion(normalized) != null) return null;
        // "Requests waiting for me / for my approval": a reviewer's queue, not their own requests. Own requests are
        // "my ... request" or "I requested".
        boolean possessive = tokens.contains("my") || tokens.contains("mine")
                || (tokens.contains("i") && tokens.stream().anyMatch(token -> token.startsWith("request")));
        if (!possessive) return null;
        // Review or approval wording ("pending my review") is a queue question for every role: reviewers get their
        // queue routes, and an Employee gets the reviewers-only refusal, never their own list.
        if (tokens.stream().anyMatch(List.of("approval", "approvals", "approve", "review", "reviews", "reviewer")::contains)
                || (isReviewer(actor) && tokens.contains("waiting") && !tokens.contains("my"))) return null;
        String kind = WaviContinuation.requestKindOf(normalized);
        if (kind == null) return null;
        // "My document request" wording has the established document-history routes (latest request, onboarding,
        // rejection reasons); this fallback covers a request named only by its document type ("salary certificate").
        if (WaviTools.RequestListKind.DOCUMENT_REQUEST.name().equals(kind)
                && tokens.stream().anyMatch(token -> token.startsWith("document"))) return null;
        boolean statusAsked = WaviContinuation.asksRequestStatus(normalized) || WaviContinuation.asksExistence(normalized);
        if (!statusAsked) return null;
        String status = tokens.contains("open") || WaviContinuation.asksPending(normalized)
                ? WaviTools.RequestStatusFilter.OPEN.name()
                : tokens.contains("rejected") ? WaviTools.RequestStatusFilter.REJECTED.name()
                : WaviTools.RequestStatusFilter.ANY.name();
        log.info("{} provider=none own-request-status kind={} status={}", PROVIDER_USED_MARKER, kind, status);
        turn.answeredWith(WaviTools.LIST_MY_REQUESTS, Map.of("kind", kind, "status", status));
        return myRequestListResponse(actor, kind, status);
    }

    /** The follow-up state of an own-timesheet list: its status filter and, when it had one, its period. */
    private Map<String, Object> ownListState(String status, MyHrConversationIntents.TimesheetRange period) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("status", status);
        if (period != null) {
            Map<String, Object> arguments = WaviTimesheetPeriod.arguments(period, LocalDate.now(clock));
            if (arguments != null) {
                state.putAll(arguments);
            }
        }
        return state;
    }

    /**
     * A follow-up to a list of the user's own timesheets that changes only the period ("and September?", "the month
     * before") or only the status ("just the rejected ones"): the same list with the rest of the stored filters.
     * Null when the message is anything else.
     */
    private MyHrChatResponse ownListFollowUp(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                             String message, String normalized, Turn turn) {
        if (previous == null || WaviContinuation.words(normalized) > 6 || WaviContinuation.isHowTo(normalized)
                || WaviContinuation.hasNameShapedToken(message) || WaviContinuation.namesGroup(normalized)) return null;
        if (WaviTools.LIST_MY_REQUESTS.equals(previous.tool())) return ownRequestListFollowUp(actor, previous, normalized, turn);
        if (!WaviTools.LIST_MY_TIMESHEETS.equals(previous.tool())) return null;
        MyHrConversationIntents.TimesheetRange before = periodOf(previous);
        // "How many hours?" / "who reviewed them?" about the timesheets just listed: the same period, another aspect.
        if (!WaviContinuation.asksForHours(normalized) && !WaviContinuation.asksBareTotal(normalized)
                && WaviContinuation.refersToPerson(normalized) && !WaviContinuation.asksForPeople(normalized)) return null;
        if (WaviContinuation.asksForHours(normalized) || WaviContinuation.asksBareTotal(normalized)) {
            if (WaviContinuation.hasFirstPerson(normalized) && MyHrConversationIntents.periodIn(normalized, LocalDate.now(clock)) != null) {
                return null; // a new question with its own period keeps its own route
            }
            if (before == null) return null;
            log.info("{} provider=none continuation=true tool={} follow-up=hours", PROVIDER_USED_MARKER, WaviTools.LIST_MY_TIMESHEETS);
            turn.keepPreviousState = true;
            return WaviContinuation.asksDaily(normalized) ? new ToolAnswers().dailyTimesheetHours(actor, before)
                    : timesheetHoursResponse(actor, before);
        }
        String decision = WaviContinuation.askedReviewDecision(normalized);
        if (decision == null && WaviContinuation.asksForPeople(normalized)
                && normalized.chars().filter(Character::isLetter).count() > 0
                && List.of(normalized.split("[^a-z]+")).stream().anyMatch(token -> token.startsWith("review")
                        || token.startsWith("approv") || token.startsWith("reject"))) {
            decision = "ANY";
        }
        if (decision != null) {
            log.info("{} provider=none continuation=true tool={} follow-up=review-decision", PROVIDER_USED_MARKER, WaviTools.LIST_MY_TIMESHEETS);
            turn.keepPreviousState = true;
            return ownReviewDecisionsResponse(actor, decision, before, false);
        }
        MyHrConversationIntents.TimesheetRange next = MyHrConversationIntents.followUpRange(normalized,
                LocalDate.now(clock), before);
        String status = WaviContinuation.timesheetStatusFilter(normalized);
        if (status == null && next == null && WaviContinuation.words(normalized) <= 5
                && !WaviContinuation.asksSubmissionTime(normalized) && WaviContinuation.namesAwaitingStatus(normalized)) {
            status = WaviTools.TimesheetStatusFilter.SUBMITTED.name();
        }
        boolean everything = status == null && next == null && WaviContinuation.workArea(normalized) == WaviTools.WorkArea.ALL
                && WaviContinuation.words(normalized) <= 3;
        if (next == null && status == null && !everything) return null;
        Object stored = previous.arguments().get("status");
        String filter = status != null ? status : everything ? WaviTools.TimesheetStatusFilter.ANY.name()
                : stored == null ? WaviTools.TimesheetStatusFilter.ANY.name() : stored.toString();
        MyHrConversationIntents.TimesheetRange range = next != null ? next : before;
        log.info("{} provider=none continuation=true tool={} follow-up={}", PROVIDER_USED_MARKER,
                WaviTools.LIST_MY_TIMESHEETS, next != null ? "period" : "status");
        turn.answeredWith(WaviTools.LIST_MY_TIMESHEETS, ownListState(filter, range));
        return myTimesheetListResponse(actor, filter, range);
    }

    /**
     * After a list of the user's own requests: "the latest one" / "the second one" selects one record of the same
     * list (newest first); "when was it requested?" repeats the selected record, whose line carries its date.
     */
    private MyHrChatResponse ownRequestListFollowUp(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                                    String normalized, Turn turn) {
        Object kind = previous.arguments().get("kind");
        Object status = previous.arguments().get("status");
        if (kind == null) return null;
        Integer ordinal = WaviContinuation.asksLatest(normalized) ? Integer.valueOf(1) : WaviContinuation.ordinal(normalized);
        boolean asksDate = WaviContinuation.refersBack(normalized) || WaviContinuation.asksSubmissionTime(normalized)
                || List.of(normalized.split("[^a-z]+")).contains("requested");
        if (ordinal == null && asksDate) {
            // The record selected before; with none selected, "when did I submit that?" means the newest one listed.
            ordinal = previous.arguments().get("ordinal") instanceof Number selected ? selected.intValue()
                    : WaviContinuation.asksSubmissionTime(normalized) || List.of(normalized.split("[^a-z]+")).contains("requested")
                    ? Integer.valueOf(1) : null;
        }
        if (ordinal == null) return null;
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("kind", kind.toString());
        state.put("status", status == null ? WaviTools.RequestStatusFilter.ANY.name() : status.toString());
        state.put("ordinal", ordinal);
        log.info("{} provider=none continuation=true tool={} follow-up=record", PROVIDER_USED_MARKER, WaviTools.LIST_MY_REQUESTS);
        turn.answeredWith(WaviTools.LIST_MY_REQUESTS, state);
        return myRequestListResponse(actor, kind.toString(), state.get("status").toString(), ordinal);
    }

    MyHrChatResponse myTimesheetListResponse(VerifiedMyHrActor actor, String status,
                                             MyHrConversationIntents.TimesheetRange range) {
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS).withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        String covering = range == null ? "" : " covering " + range.label();
        List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId()))
                .filter(timesheet -> range == null || overlaps(timesheet, range.from(), range.to()))
                .filter(timesheet -> statusMatches(timesheet.status(), status))
                // Newest work period first; for the same period, the most recent submission first.
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>reverseOrder()))
                        .thenComparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        log.info("{} provider=none my-timesheets status={}", PROVIDER_USED_MARKER, status);
        if (own.isEmpty() && range != null) {
            // Worded like the period status routes: what Wavi can see, not a claim about what exists.
            return new MyHrChatResponse("I don't see any " + statusWord(status) + "timesheets of yours" + covering + ".")
                    .withActions(actions);
        }
        if (own.isEmpty()) {
            return new MyHrChatResponse("You don't have any " + statusWord(status) + "timesheets" + covering
                    + ("NEEDS_ACTION".equals(status) ? ": nothing is waiting for you to submit or correct." : ".")).withActions(actions);
        }
        int shown = Math.min(own.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder("Your ").append(statusWord(status)).append("timesheets").append(covering)
                .append(" (").append(own.size()).append("):");
        // A period list may hold several submissions for the same dates (a resubmission is its own record): each
        // stays its own line, with its submission time and a "submission k of n" marker, never merged.
        boolean timeNeeded = range != null;
        List<String> lines = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : own.subList(0, shown)) {
            StringBuilder line = new StringBuilder(periodText(timesheet.periodStartDate(), timesheet.periodEndDate()))
                    .append(": ").append(periodStatusPhrase(timesheet.status()));
            if (timesheet.submittedAt() != null) {
                line.append(timeNeeded ? " (" + submittedText(timesheet.submittedAt()) + ")"
                        : " (submitted " + formatDay(timesheet.submittedAt().toLocalDate()) + ")");
            } else if (timeNeeded && timesheet.status() != com.waypoint.enums.TimesheetStatus.DRAFT) {
                line.append(" (no submission time recorded)");
            }
            if (timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED) {
                line.append(timesheet.reviewComment() == null || timesheet.reviewComment().isBlank()
                        ? "; no reviewer comment was recorded" : "; reviewer comment: " + timesheet.reviewComment());
            }
            lines.add(line.toString());
        }
        if (timeNeeded) {
            appendDistinctSubmissions(reply, lines, own.subList(0, shown));
        } else {
            lines.forEach(line -> reply.append("\n- ").append(line));
        }
        if (range != null && own.stream().anyMatch(timesheet -> timesheet.periodStartDate() != null
                && timesheet.periodEndDate() != null
                && (timesheet.periodStartDate().isBefore(range.from()) || timesheet.periodEndDate().isAfter(range.to())))) {
            reply.append("\nA timesheet whose work period only partly falls in ").append(range.label()).append(" is included.");
        }
        if (own.stream().anyMatch(timesheet -> timesheet.status() == com.waypoint.enums.TimesheetStatus.DRAFT)) {
            reply.append("\nA draft is saved but not submitted: it is not visible to your reviewer until you submit it.");
        }
        if (own.size() > shown) reply.append("\n...and ").append(own.size() - shown).append(" more.");
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** The user's own document or profile change requests in one status group, newest first. */
    MyHrChatResponse myRequestListResponse(VerifiedMyHrActor actor, String kind, String status) {
        return myRequestListResponse(actor, kind, status, null);
    }

    /** {@code ordinal}: one record of the list (1 = newest, -1 = oldest) instead of the whole list. */
    MyHrChatResponse myRequestListResponse(VerifiedMyHrActor actor, String kind, String status, Integer ordinal) {
        log.info("{} provider=none my-requests kind={} status={} ordinal={}", PROVIDER_USED_MARKER, kind, status, ordinal);
        // Each line with its request date, so the list is shown newest first and one record can be selected by position.
        List<String> lines = new ArrayList<>();
        List<LocalDateTime> requestedAt = new ArrayList<>();
        String noun;
        String page;
        if ("PROFILE_CHANGE".equals(kind)) {
            noun = "profile change request";
            page = MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name();
            // Company Admin accounts have no EmployeeProfile and therefore cannot create a personal profile
            // change request. Do not call listMine: that existing service deliberately rejects the admin role.
            if (actor.role() == UserRole.COMPANY_ADMIN) {
                return new MyHrChatResponse("Company Admin accounts don't have personal profile change requests.")
                        .withActions(List.of(page));
            }
            List<com.waypoint.dto.myhr.ProfileChangeRequestResponse> mine = profileChangeRequestService.listMine(actor.userId());
            for (com.waypoint.dto.myhr.ProfileChangeRequestResponse request : mine == null
                    ? List.<com.waypoint.dto.myhr.ProfileChangeRequestResponse>of() : mine) {
                if (request == null || request.status() == null) continue;
                String group = switch (request.status()) {
                    case PENDING -> "OPEN";
                    case APPROVED -> "COMPLETED";
                    case REJECTED -> "REJECTED";
                };
                if (!"ANY".equals(status) && !group.equals(status)) continue;
                requestedAt.add(request.requestedAt());
                lines.add(fieldLabel(request.fieldName()) + " change: " + request.status().name().toLowerCase(Locale.ROOT)
                        + (request.requestedAt() == null ? "" : ", requested " + formatDay(request.requestedAt().toLocalDate()))
                        + (request.status() == ProfileChangeStatus.REJECTED && request.processingComment() != null
                        && !request.processingComment().isBlank() ? "; reviewer comment: " + request.processingComment() : ""));
            }
        } else {
            noun = "document request";
            page = MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name();
            if (actor.role() == UserRole.EMPLOYEE) {
                // This bounded history is intentionally employee-only and omits participant identities and file data.
                List<MyHrOwnDocumentRequestHistory> mine = documentRequestService == null ? List.of()
                        : documentRequestService.listOwnHistory(actor.userId());
                for (MyHrOwnDocumentRequestHistory request : mine == null ? List.<MyHrOwnDocumentRequestHistory>of() : mine) {
                    if (request == null || request.status() == null) continue;
                    String group = requestStatusGroup(request.status());
                    if (!"ANY".equals(status) && !group.equals(status)) continue;
                    String what = request.requestTitle() != null && !request.requestTitle().isBlank() ? request.requestTitle()
                            : fieldLabel(request.documentType());
                    requestedAt.add(request.requestedAt());
                    lines.add(requestLine(what, request.status(), request.requestedAt(), request.rejectionReason()));
                }
            } else {
                // HR and Company Admin use the normal, already-authorized document-request list. Filter it to
                // participation by the verified actor before rendering so a Wavy "my" request never becomes a
                // direct-report or organization-wide lookup.
                List<com.waypoint.dto.myhr.DocumentRequestResponse> visible = documentRequestService == null ? List.of()
                        : documentRequestService.list(actor.userId());
                for (com.waypoint.dto.myhr.DocumentRequestResponse request : visible == null
                        ? List.<com.waypoint.dto.myhr.DocumentRequestResponse>of() : visible) {
                    if (request == null || request.status() == null
                            || (!actor.userId().equals(request.requestedByUserId())
                            && !actor.userId().equals(request.requestedForUserId()))) continue;
                    String group = requestStatusGroup(request.status());
                    if (!"ANY".equals(status) && !group.equals(status)) continue;
                    String what = request.requestTitle() != null && !request.requestTitle().isBlank() ? request.requestTitle()
                            : fieldLabel(request.documentType());
                    requestedAt.add(request.requestedAt());
                    lines.add(requestLine(what, request.status(), request.requestedAt(), request.rejectionReason()));
                }
            }
        }
        String label = switch (status) {
            case "OPEN" -> "open ";
            case "COMPLETED" -> "completed ";
            case "REJECTED" -> "rejected ";
            default -> "";
        };
        if (lines.isEmpty()) {
            return new MyHrChatResponse("You don't have any " + label + noun + "s.").withActions(List.of(page));
        }
        // Newest first; a record without a date goes last.
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) order.add(index);
        order.sort(java.util.Comparator.comparing((Integer index) -> requestedAt.get(index),
                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())));
        List<String> sorted = order.stream().map(lines::get).toList();
        if (ordinal != null) {
            int at = ordinal == -1 ? sorted.size() - 1 : ordinal - 1;
            if (at < 0 || at >= sorted.size()) {
                return new MyHrChatResponse("You have " + sorted.size() + " " + label + noun + (sorted.size() == 1 ? "" : "s")
                        + ", so there is no " + (ordinal == -1 ? "oldest" : "number " + ordinal) + " one to show.")
                        .withActions(List.of(page));
            }
            String which = ordinal == 1 ? "latest" : ordinal == -1 ? "oldest" : "number " + ordinal + " (newest first)";
            return new MyHrChatResponse("Your " + which + " " + label + noun + ": " + sorted.get(at)).withActions(List.of(page));
        }
        int shown = Math.min(sorted.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder("Your ").append(label).append(noun).append("s (").append(lines.size()).append("):");
        sorted.subList(0, shown).forEach(line -> reply.append("\n- ").append(line));
        if (lines.size() > shown) reply.append("\n...and ").append(lines.size() - shown).append(" more.");
        return new MyHrChatResponse(reply.toString()).withActions(List.of(page));
    }

    private static String requestStatusGroup(DocumentRequestStatus status) {
        return switch (status) {
            case PENDING, SUBMITTED, RETURNED_FOR_CORRECTION -> "OPEN";
            case APPROVED, FULFILLED -> "COMPLETED";
            case REJECTED, CANCELLED -> "REJECTED";
        };
    }

    private String requestLine(String what, DocumentRequestStatus status, LocalDateTime requestedAt, String rejectionReason) {
        return what + ": " + status.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (status == DocumentRequestStatus.RETURNED_FOR_CORRECTION ? " (waiting for you to correct it)" : "")
                + (requestedAt == null ? "" : ", requested " + formatDay(requestedAt.toLocalDate()))
                + (rejectionReason != null && !rejectionReason.isBlank() ? "; reason recorded: " + rejectionReason : "");
    }

    /** "What are those?" after the pending-work overview: the Employee's own open items, by kind. */
    private MyHrChatResponse myOpenItemsResponse(VerifiedMyHrActor actor) {
        StringBuilder reply = new StringBuilder();
        for (MyHrChatResponse part : List.of(myRequestListResponse(actor, "DOCUMENT_REQUEST", "OPEN"),
                myRequestListResponse(actor, "PROFILE_CHANGE", "OPEN"), myTimesheetListResponse(actor, "NEEDS_ACTION"))) {
            if (reply.length() > 0) reply.append("\n\n");
            reply.append(part.message());
        }
        return new MyHrChatResponse(reply.toString()).withActions(List.of(MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name(),
                MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name(), MyHrConversationIntents.Page.TIMESHEETS.name()));
    }

    /** At most this many items are named; the rest are counted and left to the page. */
    static final int MAX_LISTED_REVIEW_ITEMS = 10;

    /**
     * Who and what is waiting for the verified reviewer, from the same service lookups that power the review pages,
     * filtered to exactly the scope the queue counts use: HR - timesheets naming them as reviewer from their ACTIVE
     * direct reports, profile changes assigned to them, document requests addressed to them; Company Admin - their
     * organization. Only names, periods, change or document types and dates are shown: never requested values, hours,
     * comments or any other record content.
     */
    MyHrChatResponse reviewQueueItemsResponse(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) {
        return reviewQueueItemsResponse(actor, queue, null);
    }

    /**
     * The same queue narrowed to one person ({@code person} from the reviewer's current scope; null = everyone). The
     * queue's own scope rules still decide what is visible; the person only filters it further.
     */
    MyHrChatResponse reviewQueueItemsResponse(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue, ScopedPerson person) {
        log.info("{} provider=none review-list={} person={}", PROVIDER_USED_MARKER, queue, person != null);
        if (actor.role() != UserRole.HR && actor.role() != UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse(reviewQueueOutsideRoleReply(queue));
        }
        boolean hr = actor.role() == UserRole.HR;
        QueueItems collected = queueItems(actor, queue, person);
        List<String[]> items = collected.items();
        String noun = collected.noun();
        String page = collected.page();
        String scope = hr ? "waiting for your review" : "waiting for review in your organization";
        if (items.isEmpty()) {
            return new MyHrChatResponse("No " + noun + "s " + (person == null ? "" : "from "
                    + displayName(person.person().name()) + " ") + "are " + scope + " right now.").withActions(List.of(page));
        }
        long people = items.stream().map(item -> item[0]).distinct().count();
        int shown = Math.min(items.size(), MAX_LISTED_REVIEW_ITEMS);
        StringBuilder reply = new StringBuilder().append(items.size()).append(' ').append(plural(items.size(), noun))
                .append(" from ").append(person != null ? displayName(person.person().name())
                        : people + " " + (people == 1 ? "person" : "people")).append(' ')
                .append(items.size() == 1 ? "is " : "are ").append(scope).append(':');
        // Rows with identical visible details are separate records (for example two submissions made the same minute):
        // say so instead of showing what looks like a duplicate.
        java.util.Map<String, Integer> sameDetails = new java.util.HashMap<>();
        for (String[] item : items) sameDetails.merge(item[1], 1, Integer::sum);
        java.util.Map<String, Integer> seenDetails = new java.util.HashMap<>();
        for (int index = 0; index < shown; index++) {
            String text = items.get(index)[1];
            reply.append("\n- ").append(text);
            int count = sameDetails.get(text);
            if (count > 1) reply.append(" [separate record ").append(seenDetails.merge(text, 1, Integer::sum))
                    .append(" of ").append(count).append(" with these details]");
        }
        if (items.size() > shown) reply.append("\n...and ").append(items.size() - shown).append(" more.");
        reply.append("\nOpen the page below to review them.");
        return new MyHrChatResponse(reply.toString()).withActions(List.of(page));
    }

    private static String reviewQueueOutsideRoleReply(WaviTools.ReviewQueue queue) {
        return switch (queue) {
            case TIMESHEETS -> TIMESHEET_QUEUE_REVIEWERS_ONLY;
            case PROFILE_CHANGES -> PROFILE_CHANGE_QUEUE_REVIEWERS_ONLY;
            case DOCUMENT_REQUESTS -> DOCUMENT_QUEUE_REVIEWERS_ONLY;
        };
    }

    /** One review queue's waiting items: {person key, display line, person name}, in the queue's own scope. */
    private record QueueItems(String noun, String page, List<String[]> items) { }

    /**
     * How many distinct PEOPLE have items waiting in one queue (not how many items): "how many employees have
     * timesheets awaiting review?". Same scoped lookup as the list; the item count is given alongside.
     */
    MyHrChatResponse reviewQueuePeopleCountResponse(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) {
        if (!isReviewer(actor)) return new MyHrChatResponse(reviewQueueOutsideRoleReply(queue));
        QueueItems collected = queueItems(actor, queue, null);
        java.util.LinkedHashMap<String, String> people = new java.util.LinkedHashMap<>();
        for (String[] item : collected.items()) people.putIfAbsent(item[0], displayName(item[2]));
        String scope = actor.role() == UserRole.HR ? "waiting for your review" : "waiting for review in your organization";
        log.info("{} provider=none review-people-count={} people={}", PROVIDER_USED_MARKER, queue, people.size());
        if (people.isEmpty()) {
            return new MyHrChatResponse("No one has " + collected.noun() + "s " + scope + " right now.")
                    .withActions(List.of(collected.page()));
        }
        int items = collected.items().size();
        return new MyHrChatResponse(people.size() + " " + (people.size() == 1 ? "employee has " : "employees have ")
                + items + " " + plural(items, collected.noun()) + " " + scope + ": "
                + String.join(", ", people.values().stream().limit(MAX_LISTED_REVIEW_ITEMS).toList())
                + (people.size() > MAX_LISTED_REVIEW_ITEMS ? ", and " + (people.size() - MAX_LISTED_REVIEW_ITEMS) + " more" : "")
                + ".").withActions(List.of(collected.page()));
    }

    /**
     * Who is waiting for the reviewer across every queue: each person once, with what they are waiting on. The same
     * three scoped lookups as the single-queue lists.
     */
    MyHrChatResponse reviewWaitingPeopleResponse(VerifiedMyHrActor actor) {
        if (!isReviewer(actor)) {
            return new MyHrChatResponse("Employee accounts don't approve anything in Waypoint, so no one is waiting for "
                    + "your approval. Ask me what is waiting for you instead.").withActions(List.of());
        }
        java.util.LinkedHashMap<String, String> names = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, Integer>> waiting = new java.util.HashMap<>();
        List<String> pages = new ArrayList<>();
        for (WaviTools.ReviewQueue queue : WaviTools.ReviewQueue.values()) {
            QueueItems collected = queueItems(actor, queue, null);
            if (!collected.items().isEmpty()) pages.add(collected.page());
            for (String[] item : collected.items()) {
                names.putIfAbsent(item[0], displayName(item[2]));
                waiting.computeIfAbsent(item[0], ignored -> new java.util.LinkedHashMap<>())
                        .merge(collected.noun(), 1, Integer::sum);
            }
        }
        log.info("{} provider=none review-people people={}", PROVIDER_USED_MARKER, names.size());
        String scope = actor.role() == UserRole.HR ? "your review" : "review in your organization";
        if (names.isEmpty()) {
            return new MyHrChatResponse("No one is waiting for " + scope + " right now.").withActions(List.of());
        }
        StringBuilder reply = new StringBuilder().append(names.size()).append(names.size() == 1 ? " person is" : " people are")
                .append(" waiting for ").append(scope).append(':');
        int shown = 0;
        for (var person : names.entrySet()) {
            if (shown++ == MAX_LISTED_REVIEW_ITEMS) {
                reply.append("\n...and ").append(names.size() - MAX_LISTED_REVIEW_ITEMS).append(" more.");
                break;
            }
            List<String> parts = new ArrayList<>();
            waiting.get(person.getKey()).forEach((noun, count) -> parts.add(count + " " + plural(count, noun)));
            reply.append("\n- ").append(person.getValue()).append(": ").append(String.join(", ", parts));
        }
        return new MyHrChatResponse(reply.toString()).withActions(pages);
    }

    /** The user's own submitted (non-draft) timesheet records, optionally only those overlapping a period. */
    private List<com.waypoint.dto.myhr.TimesheetResponse> ownRecords(VerifiedMyHrActor actor,
                                                                     MyHrConversationIntents.TimesheetRange range,
                                                                     boolean includeDrafts) {
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
            // Only the verified user's own records, even when the service also returns a reviewer's team records.
            if (timesheet == null || !actor.userId().equals(timesheet.employeeUserId())) continue;
            if (!includeDrafts && timesheet.status() == TimesheetStatus.DRAFT) continue;
            if (range != null && !overlaps(timesheet, range.from(), range.to())) continue;
            own.add(timesheet);
        }
        own.sort(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::submittedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDateTime>reverseOrder())));
        return own;
    }

    /**
     * Of the user's own timesheets (covering {@code range}, or all), the one submitted most recently, by its recorded
     * submission time; the other matching submissions are counted, never merged into it.
     */
    MyHrChatResponse ownLatestSubmittedResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
        if (actor.role() == UserRole.COMPANY_ADMIN) return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS);
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = ownRecords(actor, range, false).stream()
                .filter(timesheet -> timesheet.submittedAt() != null).toList();
        String covering = range == null ? "" : " covering " + range.label();
        log.info("{} provider=none own-latest-submitted range={} matches={}", PROVIDER_USED_MARKER, range != null, own.size());
        if (own.isEmpty()) {
            return new MyHrChatResponse("I don't see a submitted timesheet of yours" + covering + ".").withActions(actions);
        }
        com.waypoint.dto.myhr.TimesheetResponse latest = own.getFirst();
        return new MyHrChatResponse("The most recently submitted one is " + periodText(latest.periodStartDate(),
                latest.periodEndDate()) + ", " + submittedText(latest.submittedAt()) + "; it "
                + periodStatusPhrase(latest.status()) + "."
                + (own.size() > 1 ? " It is one of " + own.size() + " separate submissions of yours" + covering + "." : ""))
                .withActions(actions);
    }

    /**
     * Why the user's own timesheet has its status: what Waypoint actually records (who reviews it and since when, the
     * reviewer's comment on a rejection), and plainly that it records no reason for a review still pending.
     * {@code range} null: the timesheets waiting for review (or, if none, the latest one).
     */
    MyHrChatResponse ownTimesheetReasonResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
        if (actor.role() == UserRole.COMPANY_ADMIN) return new MyHrChatResponse(ADMIN_NO_OWN_TIMESHEET_STATUS);
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = ownRecords(actor, range, true);
        if (range == null) {
            List<com.waypoint.dto.myhr.TimesheetResponse> waiting = own.stream()
                    .filter(timesheet -> timesheet.status() == TimesheetStatus.SUBMITTED).toList();
            own = waiting.isEmpty() ? own.stream().limit(1).toList() : waiting;
        }
        log.info("{} provider=none own-timesheet-reason range={} records={}", PROVIDER_USED_MARKER, range != null, own.size());
        if (own.isEmpty()) {
            return new MyHrChatResponse("I don't see a timesheet of yours" + (range == null ? "" : " covering "
                    + range.label()) + ", so there is no status to explain.").withActions(actions);
        }
        StringBuilder reply = new StringBuilder();
        boolean pending = false;
        int shown = Math.min(own.size(), 3);
        if (range == null && own.getFirst().status() == TimesheetStatus.SUBMITTED) {
            reply.append(own.size() == 1 ? "One timesheet of yours is waiting for review:"
                    : own.size() + " timesheets of yours are waiting for review:");
        } else if (own.size() > 1) {
            reply.append(own.size()).append(" separate timesheets of yours")
                    .append(range == null ? "" : " cover " + range.label()).append(':');
        }
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : own.subList(0, shown)) {
            String reviewer = isBlank(timesheet.reportingManagerName()) ? null : timesheet.reportingManagerName();
            if (!reply.isEmpty()) reply.append("\n- ");
            reply.append(periodText(timesheet.periodStartDate(), timesheet.periodEndDate())).append(": ");
            switch (timesheet.status() == null ? TimesheetStatus.DRAFT : timesheet.status()) {
                case SUBMITTED -> {
                    pending = true;
                    reply.append(submittedText(timesheet.submittedAt())).append(", waiting for ")
                            .append(reviewer == null ? "a reviewer (none is recorded on it - please contact HR)"
                                    : reviewer + " to review it").append('.');
                }
                case REJECTED -> reply.append("rejected").append(timesheet.reviewedAt() == null ? ""
                                : " on " + formatDay(timesheet.reviewedAt().toLocalDate()))
                        .append(isBlank(timesheet.reviewComment()) ? "; no reviewer comment was recorded."
                                : "; the reviewer's comment: \"" + timesheet.reviewComment().strip() + "\".")
                        .append(" Correct and resubmit it on Timesheets.");
                case APPROVED -> reply.append("is approved").append(timesheet.reviewedAt() == null ? "."
                        : " on " + formatDay(timesheet.reviewedAt().toLocalDate()) + ".");
                case DRAFT -> reply.append("a draft that hasn't been submitted, so no one is reviewing it yet.");
            }
        }
        if (own.size() > shown) reply.append("\n...and ").append(own.size() - shown).append(" more on Timesheets.");
        boolean commented = own.subList(0, shown).stream().anyMatch(timesheet -> !isBlank(timesheet.reviewComment()));
        if (!pending && !commented) {
            // Decided records with no recorded comment: the status is known, the reason behind it is not.
            reply.append(own.size() > 1 ? "\n" : " ").append(WaviContinuation.WHY_LIMITATION);
        }
        if (pending) {
            reply.append(own.size() > 1 || range == null ? "\n" : " ").append("Waypoint records when a timesheet was "
                    + "submitted and who reviews it, not why a review hasn't happened yet, so I can't tell you the "
                    + "reason for the wait. Your reviewer or HR can.");
        }
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** The people a reviewer can already see (HR: active direct reports; Company Admin: organization), by name. */
    MyHrChatResponse peopleListResponse(VerifiedMyHrActor actor) {
        if (!isReviewer(actor)) return new MyHrChatResponse(WaviTools.PEOPLE_AGGREGATE_REPLY).withActions(List.of());
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
        List<String> actions = List.of(MyHrConversationIntents.Page.EMPLOYEES.name());
        String scope = actor.role() == UserRole.HR ? "your active direct reports" : "your organization (HR and Employee accounts)";
        log.info("{} provider=none people-list={}", PROVIDER_USED_MARKER, people.size());
        if (people.isEmpty()) return new MyHrChatResponse("I don't see anyone in " + scope + ".").withActions(actions);
        StringBuilder reply = new StringBuilder().append(people.size()).append(people.size() == 1 ? " person" : " people")
                .append(" in ").append(scope).append(':');
        int shown = Math.min(people.size(), MAX_LISTED_PEOPLE);
        for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people.subList(0, shown)) {
            reply.append("\n- ").append(displayName(person.name()))
                    .append(isBlank(person.designation()) ? "" : ", " + person.designation())
                    .append(isBlank(person.personType()) ? "" : " (" + shortRoleLabel(person.personType()) + ")");
        }
        if (people.size() > shown) reply.append("\n...and ").append(people.size() - shown).append(" more on Employees.");
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    private static final int MAX_LISTED_PEOPLE = 15;

    private static String shortRoleLabel(String personType) {
        return switch (personType) {
            case "HR" -> "HR";
            case "EMPLOYEE" -> "Employee";
            case "COMPANY_ADMIN" -> "Company Admin";
            default -> personType;
        };
    }

    private QueueItems queueItems(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue, ScopedPerson person) {
        String onlyUserId = person == null ? null : person.person().userId();
        boolean hr = actor.role() == UserRole.HR;
        List<String[]> items = new ArrayList<>();
        String noun;
        String page;
        switch (queue) {
            case TIMESHEETS -> {
                noun = "timesheet";
                page = MyHrConversationIntents.Page.TIMESHEETS.name();
                java.util.Set<String> reports = hr ? activeDirectReportIds(actor) : null;
                List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
                for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                        ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
                    if (timesheet == null || timesheet.status() != com.waypoint.enums.TimesheetStatus.SUBMITTED
                            || actor.userId().equals(timesheet.employeeUserId())) continue;
                    if (hr && (!actor.userId().equals(timesheet.reportingManagerUserId())
                            || !reports.contains(timesheet.employeeUserId()))) continue;
                    if (onlyUserId != null && !onlyUserId.equals(timesheet.employeeUserId())) continue;
                    // Each submission is its own record: the submission time tells same-period submissions apart.
                    items.add(new String[]{personKey(timesheet.employeeUserId(), timesheet.employeeName()),
                            displayName(timesheet.employeeName()) + " - " + periodText(timesheet.periodStartDate(),
                                    timesheet.periodEndDate()) + ", " + submittedText(timesheet.submittedAt()),
                            timesheet.employeeName()});
                }
            }
            case PROFILE_CHANGES -> {
                noun = "profile change request";
                page = MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name();
                List<com.waypoint.dto.myhr.ProfileChangeRequestResponse> visible =
                        profileChangeRequestService.listForAdmin(actor.userId());
                for (com.waypoint.dto.myhr.ProfileChangeRequestResponse request : visible == null
                        ? List.<com.waypoint.dto.myhr.ProfileChangeRequestResponse>of() : visible) {
                    if (request == null || request.status() != ProfileChangeStatus.PENDING) continue;
                    if (hr && !actor.userId().equals(request.assignedToUserId())) continue;
                    if (onlyUserId != null && !onlyUserId.equals(request.requesterUserId())) continue;
                    items.add(new String[]{personKey(request.requesterUserId(), request.employeeName()),
                            displayName(request.employeeName()) + " - " + fieldLabel(request.fieldName()) + " change"
                                    + (request.requestedAt() == null ? ""
                                    : ", requested " + formatDay(request.requestedAt().toLocalDate())),
                            request.employeeName()});
                }
            }
            default -> {
                noun = "document request";
                page = hr ? MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name()
                        : MyHrConversationIntents.Page.DOCUMENT_APPROVALS.name();
                List<com.waypoint.dto.myhr.DocumentRequestResponse> visible =
                        documentRequestService == null ? List.of() : documentRequestService.list(actor.userId());
                for (com.waypoint.dto.myhr.DocumentRequestResponse request : visible == null
                        ? List.<com.waypoint.dto.myhr.DocumentRequestResponse>of() : visible) {
                    if (request == null) continue;
                    boolean waiting = hr ? request.status() == DocumentRequestStatus.PENDING
                            && actor.userId().equals(request.requestedForUserId())
                            : request.status() == DocumentRequestStatus.PENDING
                            || request.status() == DocumentRequestStatus.SUBMITTED;
                    if (!waiting) continue;
                    if (onlyUserId != null && !onlyUserId.equals(request.requestedByUserId())
                            && !onlyUserId.equals(request.requestedForUserId())) continue;
                    String what = request.requestTitle() != null && !request.requestTitle().isBlank()
                            ? request.requestTitle() : request.documentType() == null ? "document"
                            : fieldLabel(request.documentType());
                    items.add(new String[]{personKey(request.requestedByUserId(), request.requestedByName()),
                            displayName(request.requestedByName()) + " - " + what
                                    + (request.requestedAt() == null ? ""
                                    : ", requested " + formatDay(request.requestedAt().toLocalDate())),
                            request.requestedByName()});
                }
            }
        }
        return new QueueItems(noun, page, items);
    }

    private java.util.Set<String> activeDirectReportIds(VerifiedMyHrActor actor) {
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> reports = employeeManagementService.list(actor.userId());
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (reports != null) {
            for (com.waypoint.dto.myhr.EmployeeSummaryResponse report : reports) {
                if (report != null && report.userId() != null) ids.add(report.userId());
            }
        }
        return ids;
    }

    private static String personKey(String userId, String name) {
        return userId != null ? userId : String.valueOf(name);
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "An unnamed employee" : name;
    }

    private static String fieldLabel(Enum<?> value) {
        if (value == null) return "profile";
        String text = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private String periodText(LocalDate from, LocalDate to) {
        if (from == null || to == null) return "dates not recorded";
        return formatDay(from) + " to " + formatDay(to);
    }

    /**
     * The user's own recorded hours on the days of {@code range} (or of their latest timesheet when null), from the
     * same own-records lookup the Timesheets page uses. Each timesheet is listed with its status; a day recorded on
     * more than one timesheet is called out and never added twice.
     */
    /**
     * The answer to "how many (submitted / approved) hours on <date>": the Hours Overview value for that date under
     * the asked metric, first ("8 submitted hours recorded for November 10, 2026."), then a note only when the
     * figure would otherwise mislead:
     * <ul>
     *   <li>no timesheet entry for the date (not the same as a 0-hour entry, and not a claim that no work occurred);</li>
     *   <li>hours entered but not counted for this metric (a draft not yet submitted, a rejection awaiting correction,
     *       or entered hours not yet approved);</li>
     *   <li>the date on several submissions whose values differ, or the rejected-latest fallback to an approved entry.</li>
     * </ul>
     * The value itself comes from {@link TimesheetEffectiveHours}: the Hours Overview rule, unchanged.
     */
    private String oneDayHoursAnswer(LocalDate date, TimesheetEffectiveHours.Day day,
                                     List<com.waypoint.dto.myhr.TimesheetResponse> covering, HoursMetric metric) {
        // The same date form as every other Wavi answer ("Tue Nov 10, 2026").
        String when = formatDay(date);
        com.waypoint.enums.TimesheetStatus status = day == null ? null : day.status();
        java.math.BigDecimal entered = day == null ? java.math.BigDecimal.ZERO : day.hours();
        boolean counts = switch (metric) {
            case ALL -> true;
            case SUBMITTED -> status == com.waypoint.enums.TimesheetStatus.APPROVED
                    || status == com.waypoint.enums.TimesheetStatus.SUBMITTED;
            case APPROVED -> status == com.waypoint.enums.TimesheetStatus.APPROVED;
        };
        java.math.BigDecimal value = counts ? entered : java.math.BigDecimal.ZERO;
        String kind = metric == HoursMetric.SUBMITTED ? " submitted" : metric == HoursMetric.APPROVED ? " approved" : "";
        String figure = value.stripTrailingZeros().toPlainString();
        StringBuilder answer = new StringBuilder(figure).append(kind).append(value.compareTo(java.math.BigDecimal.ONE) == 0
                ? " hour" : " hours").append(" recorded for ").append(when).append('.');
        if (day == null || day.submissionsWithEntry() == 0) {
            answer.append(" Your timesheet").append(covering.size() == 1 ? " covering this date has" : "s covering this date have")
                    .append(" no hours entered for it.");
            return answer.toString();
        }
        if (!counts && entered.signum() > 0) {
            answer.append(switch (status) {
                case DRAFT -> " Your draft has " + hoursText(entered) + " for this date that " + (entered.compareTo(java.math.BigDecimal.ONE) == 0
                        ? "hasn't" : "haven't") + " been submitted yet.";
                case SUBMITTED -> " " + hoursText(entered) + " for this date " + (entered.compareTo(java.math.BigDecimal.ONE) == 0
                        ? "is" : "are") + " submitted and awaiting approval.";
                default -> "";
            });
        }
        if (status == com.waypoint.enums.TimesheetStatus.REJECTED) {
            answer.append(" The latest submission for this date was rejected, so nothing counts until it is corrected and resubmitted.");
        } else if (day.fallbackFromRejected()) {
            answer.append(" The latest submission for this date was rejected, so the earlier approved entry is counted.");
        } else if (counts && day.submissionsWithEntry() > 1) {
            // Only when the counted value is shown: a note about "the counted entry" would contradict a 0 answer.
            java.util.Set<java.math.BigDecimal> values = new java.util.HashSet<>();
            boolean rejectedEarlier = false;
            for (com.waypoint.dto.myhr.TimesheetResponse timesheet : covering) {
                for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                        ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                    if (entry != null && date.equals(entry.workDate()) && entry.hoursWorked() != null) {
                        values.add(entry.hoursWorked().stripTrailingZeros());
                        if (timesheet.status() == com.waypoint.enums.TimesheetStatus.REJECTED) rejectedEarlier = true;
                    }
                }
            }
            if (values.size() > 1) {
                answer.append(" This date is on ").append(day.submissionsWithEntry())
                        .append(" submissions with different hours; the latest one is counted.");
            } else if (rejectedEarlier) {
                // Same hours, but the counted entry is a resubmission after a rejection: worth one sentence.
                answer.append(" An earlier submission for this date was rejected; the resubmission is counted.");
            }
        }
        return answer.toString();
    }

    /**
     * "Why were these hours counted once?" after an hours answer: how Hours Overview counts each date, and for a single
     * date which submissions hold it. From the same records and rule as the answer; nothing is recalculated.
     */
    private String hoursCountingExplanation(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
        StringBuilder text = new StringBuilder("Each work date is counted once, the same way Timesheets > Hours Overview "
                + "counts it: every submission or resubmission is a separate record, and for a date on more than one, the "
                + "latest submission's entry counts. If that latest one was rejected, an earlier approved entry for the "
                + "date counts instead; otherwise the date counts 0 until it is resubmitted.");
        if (range != null && range.singleDay() && actor.role() != UserRole.COMPANY_ADMIN) {
            LocalDate date = range.from();
            List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
            List<com.waypoint.dto.myhr.TimesheetResponse> own = (visible == null
                    ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible).stream()
                    .filter(timesheet -> timesheet != null && actor.userId().equals(timesheet.employeeUserId())).toList();
            // Each submission's own entry for the date, as stored; the counted value comes from the Hours Overview rule.
            List<java.math.BigDecimal> entered = new ArrayList<>();
            for (com.waypoint.dto.myhr.TimesheetResponse timesheet : own) {
                java.math.BigDecimal onDate = null;
                for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                        ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                    if (entry != null && date.equals(entry.workDate()) && entry.hoursWorked() != null) {
                        onDate = (onDate == null ? java.math.BigDecimal.ZERO : onDate).add(entry.hoursWorked());
                    }
                }
                if (onDate != null) entered.add(onDate);
            }
            int holding = entered.size();
            text.append(" ").append(formatDay(date)).append(" is on ").append(holding).append(" of your submissions");
            if (holding > 1) {
                TimesheetEffectiveHours.Day counted = TimesheetEffectiveHours.compute(own, date, date).day(date);
                java.math.BigDecimal sum = entered.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                boolean same = entered.stream().map(java.math.BigDecimal::stripTrailingZeros).distinct().count() == 1;
                text.append(same ? ", each with " + hoursText(entered.getFirst()) : " with different hours")
                        .append(", so Hours Overview counts ")
                        .append(hoursText(counted == null ? java.math.BigDecimal.ZERO : counted.hours()))
                        .append(" for it, not the ").append(hoursText(sum)).append(" they would add up to.");
            } else {
                text.append('.');
            }
        }
        return text.toString();
    }

    /** Which hours a question asks for: everything entered, only submitted (not draft) hours, or only approved ones. */
    enum HoursMetric { ALL, SUBMITTED, APPROVED }

    /** The metric a question names: "approved" hours, hours "submitted"/"sent", or every recorded hour. */
    static HoursMetric hoursMetricOf(String normalized) {
        if (normalized == null) return HoursMetric.ALL;
        if (WaviTools.TimesheetStatusFilter.APPROVED.name().equals(WaviContinuation.timesheetStatusFilter(normalized))) {
            return HoursMetric.APPROVED;
        }
        List<String> tokens = List.of(normalized.split("[^a-z]+"));
        boolean submitted = tokens.stream().anyMatch(token -> token.startsWith("submit") || token.equals("sent"));
        return submitted && WaviContinuation.timesheetStatusFilter(normalized) == null ? HoursMetric.SUBMITTED : HoursMetric.ALL;
    }

    /**
     * The Hours Overview figure for a range, worded for the metric asked: the total, worked days and how the hours
     * split by status; and, when days are on more than one submission, how they were counted once.
     */
    private String effectiveHoursSummary(TimesheetEffectiveHours.Result result, HoursMetric metric) {
        java.math.BigDecimal approved = result.byStatus().getOrDefault(com.waypoint.enums.TimesheetStatus.APPROVED, java.math.BigDecimal.ZERO);
        java.math.BigDecimal waiting = result.byStatus().getOrDefault(com.waypoint.enums.TimesheetStatus.SUBMITTED, java.math.BigDecimal.ZERO);
        java.math.BigDecimal draft = result.byStatus().getOrDefault(com.waypoint.enums.TimesheetStatus.DRAFT, java.math.BigDecimal.ZERO);
        long approvedDays = result.days().stream().filter(day -> day.hours().signum() > 0
                && day.status() == com.waypoint.enums.TimesheetStatus.APPROVED).count();
        long submittedDays = result.days().stream().filter(day -> day.hours().signum() > 0
                && (day.status() == com.waypoint.enums.TimesheetStatus.APPROVED
                    || day.status() == com.waypoint.enums.TimesheetStatus.SUBMITTED)).count();
        StringBuilder text = new StringBuilder();
        switch (metric) {
            case APPROVED -> text.append("approved hours: ").append(hoursText(approved)).append(" on ").append(approvedDays)
                    .append(' ').append(plural((int) approvedDays, "day")).append(" (Hours Overview shows ")
                    .append(hoursText(result.total())).append(" in total).");
            case SUBMITTED -> text.append("submitted hours (approved or awaiting approval): ").append(hoursText(approved.add(waiting)))
                    .append(" on ").append(submittedDays).append(' ').append(plural((int) submittedDays, "day"))
                    .append(" (Hours Overview shows ").append(hoursText(result.total())).append(" in total").append(
                            draft.signum() > 0 ? ", including " + hoursText(draft) + " in drafts not yet submitted)." : ").");
            default -> text.append("the Hours Overview total is ").append(hoursText(result.total())).append(" on ")
                    .append(result.workedDays()).append(" worked ").append(plural(result.workedDays(), "day")).append('.');
        }
        List<String> parts = new ArrayList<>();
        if (approved.signum() > 0) parts.add(hoursText(approved) + " approved");
        if (waiting.signum() > 0) parts.add(hoursText(waiting) + " awaiting approval");
        if (draft.signum() > 0) parts.add(hoursText(draft) + " in drafts not yet submitted");
        if (metric == HoursMetric.ALL && parts.size() > 1) text.append(" By status: ").append(String.join(", ", parts)).append('.');
        // A submitted-hours question is a status-filtered metric. Surface the actual authorized status even when
        // there is only one kind of effective entry, so "submitted" never obscures that a day's 11 hours are approved.
        if (metric == HoursMetric.SUBMITTED && !parts.isEmpty()) text.append(" Effective status: ")
                .append(String.join(", ", parts)).append('.');
        if (result.overlappingDays() > 0) {
            text.append(" Days on more than one submission are counted once, as Hours Overview does: the latest "
                    + "submission's entry, or an earlier approved entry when the latest was rejected.");
        }
        text.append("\nBy submission:");
        return text.toString();
    }

    MyHrChatResponse timesheetHoursResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
        return timesheetHoursResponse(actor, range, HoursMetric.ALL);
    }

    MyHrChatResponse timesheetHoursResponse(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range,
                                            HoursMetric metric) {
        log.info("{} provider=none timesheet-hours={}", PROVIDER_USED_MARKER, range == null ? "latest" : range.unit());
        List<String> actions = List.of(MyHrConversationIntents.Page.TIMESHEETS.name());
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return new MyHrChatResponse("Company Admin accounts don't submit timesheets, so there are no hours of yours "
                    + "to show. Open Timesheets to review employees' submissions.").withActions(actions);
        }
        List<com.waypoint.dto.myhr.TimesheetResponse> visible = timesheetService.listForUser(actor.userId());
        List<com.waypoint.dto.myhr.TimesheetResponse> own = new ArrayList<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : visible == null
                ? List.<com.waypoint.dto.myhr.TimesheetResponse>of() : visible) {
            if (timesheet != null && actor.userId().equals(timesheet.employeeUserId())
                    && timesheet.periodStartDate() != null && timesheet.periodEndDate() != null) own.add(timesheet);
        }
        LocalDate from;
        LocalDate to;
        String label;
        if (range == null) {
            com.waypoint.dto.myhr.TimesheetResponse latest = own.stream()
                    .max(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate))
                    .orElse(null);
            if (latest == null) return new MyHrChatResponse("I don't see a timesheet of yours yet.").withActions(actions);
            from = latest.periodStartDate();
            to = latest.periodEndDate();
            label = "your latest timesheet";
        } else {
            from = range.from();
            to = range.to();
            label = range.label() + (range.singleDay() ? "" : " (" + formatDay(from) + " to " + formatDay(to) + ")");
        }
        LocalDate start = from;
        LocalDate end = to;
        List<com.waypoint.dto.myhr.TimesheetResponse> overlapping = own.stream()
                .filter(timesheet -> !timesheet.periodEndDate().isBefore(start) && !timesheet.periodStartDate().isAfter(end))
                .sorted(java.util.Comparator.comparing(com.waypoint.dto.myhr.TimesheetResponse::periodStartDate))
                .toList();
        if (overlapping.isEmpty()) {
            return new MyHrChatResponse("I don't see a timesheet of yours covering " + label + ".").withActions(actions);
        }
        TimesheetEffectiveHours.Result effective = TimesheetEffectiveHours.compute(own, start, end);
        StringBuilder reply;
        if (range != null && range.singleDay()) {
            // One day, any metric: one answer-first sentence and only the notes that change how to read it. The
            // submissions themselves are a follow-up away ("show the timesheets for that date").
            return new MyHrChatResponse(oneDayHoursAnswer(start, effective.day(start), overlapping, metric)).withActions(actions);
        } else {
            reply = new StringBuilder("Hours recorded for " + label + ":");
            // First the figure Timesheets > Hours Overview shows for these dates (one value per day, same rule), then
            // the per-submission detail it is made from.
            reply.append(' ').append(effectiveHoursSummary(effective, metric));
        }
        java.util.Map<LocalDate, Integer> recordsPerDay = new java.util.HashMap<>();
        for (com.waypoint.dto.myhr.TimesheetResponse timesheet : overlapping) {
            java.math.BigDecimal hours = java.math.BigDecimal.ZERO;
            int days = 0;
            for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                    ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                if (entry == null || entry.workDate() == null || entry.hoursWorked() == null
                        || entry.workDate().isBefore(start) || entry.workDate().isAfter(end)) continue;
                hours = hours.add(entry.hoursWorked());
                days++;
                recordsPerDay.merge(entry.workDate(), 1, Integer::sum);
            }
            reply.append("\n- Timesheet ").append(periodText(timesheet.periodStartDate(), timesheet.periodEndDate()))
                    .append(" (").append(timesheet.status() == null ? "status not recorded"
                            : timesheet.status().name().toLowerCase(Locale.ROOT)).append("): ")
                    .append(hours.stripTrailingZeros().toPlainString()).append(' ')
                    .append(hours.compareTo(java.math.BigDecimal.ONE) == 0 ? "hour" : "hours")
                    .append(days == 0 ? " (no hours entered on these days)" : " on " + days + " " + plural(days, "day"));
        }
        long shared = recordsPerDay.values().stream().filter(count -> count > 1).count();
        if (shared == 0 && overlapping.size() > 1) {
            // No day is on two timesheets, so the per-timesheet hours add up to the period total exactly once.
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (com.waypoint.dto.myhr.TimesheetResponse timesheet : overlapping) {
                for (com.waypoint.dto.myhr.TimesheetEntryResponse entry : timesheet.entries() == null
                        ? List.<com.waypoint.dto.myhr.TimesheetEntryResponse>of() : timesheet.entries()) {
                    if (entry == null || entry.workDate() == null || entry.hoursWorked() == null
                            || entry.workDate().isBefore(start) || entry.workDate().isAfter(end)) continue;
                    total = total.add(entry.hoursWorked());
                }
            }
            boolean allApproved = overlapping.stream()
                    .allMatch(timesheet -> timesheet.status() == com.waypoint.enums.TimesheetStatus.APPROVED);
            reply.append("\nTotal recorded across these timesheets: ").append(hoursText(total))
                    .append(allApproved ? "." : " (not all of these timesheets are approved).");
        }
        if (shared > 0) {
            reply.append("\n").append(shared).append(' ').append(plural((int) shared, "day"))
                    .append(shared == 1 ? " appears" : " appear")
                    .append(" on more than one of your timesheets, so I haven't added them together: the submissions above "
                            + "are shown separately, and the total above counts each day once. These records do not "
                            + "establish an official payable total. Open Timesheets to inspect each separate submission.");
        }
        return new MyHrChatResponse(reply.toString()).withActions(actions);
    }

    /** "How many hours was that?" after a timesheet answer: the hours for the same period. */
    private MyHrChatResponse timesheetHoursContinuation(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                                        Turn turn) {
        LocalDate today = previous.referenceDate() == null ? LocalDate.now(clock) : previous.referenceDate();
        java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>(previous.arguments());
        arguments.remove("detail");
        WaviToolArguments.Validation validation = WaviTimesheetPeriod.SCHEMA.validate(arguments, today);
        if (!validation.valid() || WaviTimesheetPeriod.check(validation.arguments(), today) != null) return null;
        boolean latest = WaviTimesheetPeriod.Period.LATEST.name().equals(arguments.get("period"));
        arguments.put("detail", WaviTimesheetPeriod.Detail.HOURS.name());
        turn.continuedWith(WaviTools.GET_MY_TIMESHEETS, arguments, previous);
        return timesheetHoursResponse(actor, latest ? null : WaviTimesheetPeriod.range(validation.arguments(), today));
    }

    /**
     * "When was it submitted?" right after a question about the user's own timesheets for a named day, week or month:
     * the same period again (never the latest timesheet instead), with each timesheet's submission date. The period
     * comes from the stored, re-validated enum/date arguments. Null for the latest timesheet, whose own routes already
     * keep the subject, or when the stored arguments no longer validate.
     */
    private MyHrChatResponse timesheetPeriodContinuation(VerifiedMyHrActor actor, WaviDialogueStore.State previous,
                                                         String normalized, Turn turn) {
        if (WaviTimesheetPeriod.Period.LATEST.name().equals(previous.arguments().get("period"))) return null;
        LocalDate today = previous.referenceDate() == null ? LocalDate.now(clock) : previous.referenceDate();
        WaviToolArguments.Validation validation = WaviTimesheetPeriod.SCHEMA.validate(previous.arguments(), today);
        if (!validation.valid() || WaviTimesheetPeriod.check(validation.arguments(), today) != null) return null;
        MyHrConversationIntents.TimesheetRange range = WaviTimesheetPeriod.range(validation.arguments(), today);
        if (range == null) return null;
        log.info("{} provider=none continuation=true tool={} same-period=true", PROVIDER_USED_MARKER,
                WaviTools.GET_MY_TIMESHEETS);
        MyHrChatResponse answer = timesheetRangeResponse(actor, range, true);
        turn.continuedWith(WaviTools.GET_MY_TIMESHEETS, previous.arguments(), previous);
        if (!WaviContinuation.asksWhy(normalized)) return answer;
        MyHrChatResponse limited = new MyHrChatResponse(answer.message() + " " + WaviContinuation.WHY_LIMITATION);
        return answer.actions() == null ? limited : limited.withActions(answer.actions());
    }

    /**
     * What semantic routing did for one message: an answer, or none. {@code inScope} means the router judged a
     * policy or general HR question that no explicit out-of-scope rule refuses, so the vocabulary-based scope guard
     * does not refuse it; grounding and the providers' own protections still apply.
     */
    private record SemanticOutcome(MyHrChatResponse response, boolean inScope, boolean attempted, boolean handoff,
                                   boolean routerFailed) {
        SemanticOutcome(MyHrChatResponse response, boolean inScope, boolean attempted, boolean handoff) {
            this(response, inScope, attempted, handoff, false);
        }

        /** The router could not decide at all (provider error, circuit open, limits, invalid output, deadline). */
        static final SemanticOutcome FAILED = new SemanticOutcome(null, false, true, false, true);
        static final SemanticOutcome NONE = new SemanticOutcome(null, false, false, false);
        /** Attempted, but no usable decision: the router abstained, failed or its decision could not be acted on. */
        static final SemanticOutcome NO_DECISION = new SemanticOutcome(null, false, true, false);
    }

    /**
     * The user typed their own timesheet(s) and a date, week or month ("did I submit my October 2026 timesheets?"),
     * so the record type and the period are both known. A router decision that still asks which record is meant, or
     * looks up the user's timesheets without that period (their latest, an unrelated one), would discard what was
     * typed: it is not acted on, and the deterministic period route answers instead.
     */
    private boolean dropsTypedTimesheetPeriod(String message, WaviRouterDecision decision, LocalDate today) {
        if (MyHrConversationIntents.timesheetRange(normalizeQuestion(message), today) == null) return false;
        if (WaviTools.CLARIFY.equals(decision.tool())) return true;
        if (!WaviTools.GET_MY_TIMESHEETS.equals(decision.tool()) && !WaviTools.LIST_MY_TIMESHEETS.equals(decision.tool())) {
            return false;
        }
        Object period = decision.arguments() == null ? null : decision.arguments().get("period");
        return period == null || WaviTimesheetPeriod.Period.LATEST.name().equals(period.toString());
    }

    private SemanticOutcome semanticResponse(String message, Conversation conversation, VerifiedMyHrActor actor,
                                             Turn turn) {
        LocalDate today = LocalDate.now(clock);
        WaviDialogueStore.State previous = turn.previousState;
        WaviSemanticRouting.Result routed = semanticRouting.route(actor, message, previous, today,
                toolLayer.registry().all().stream().filter(tool -> !(tool instanceof WaviTool.AgentOnly)).toList());
        WaviRouterDecision decision = routed.decision();
        // No decision because routing failed (not disabled): the fallback must not pretend it understood.
        if (decision == null && !"disabled".equals(routed.outcome())) return SemanticOutcome.FAILED;
        // No decision, or a guess: the existing path answers exactly as without semantic routing.
        if (decision == null || decision.confidence() == WaviRouterDecision.Confidence.LOW
                || WaviTools.OUT_OF_SCOPE.equals(decision.tool())) {
            return SemanticOutcome.NO_DECISION;
        }
        if (dropsTypedTimesheetPeriod(message, decision, today)) {
            log.info("{} provider=router tool={} result=DROPPED_TYPED_PERIOD", PROVIDER_USED_MARKER, decision.tool());
            return SemanticOutcome.NO_DECISION;
        }
        WaviToolLayer.Outcome outcome = toolLayer.execute(decision, actor, today, message);
        WaviToolResult result = outcome.result();
        log.info("{} provider=router tool={} result={}", PROVIDER_USED_MARKER, decision.tool(), result.reasonCode());
        if (WaviToolLayer.isHandoff(result)) {
            // Policy and general questions keep the existing grounded and provider paths with all their protections;
            // only a vocabulary-based scope refusal is lifted, never an explicit out-of-scope rule.
            return new SemanticOutcome(null, !MyHrScopeGuard.isExplicitlyOutOfScope(message), true, true);
        }
        if (result.kind() == WaviToolResult.Kind.REJECTED || result.text() == null
                || "NO_ANSWER".equals(result.reasonCode())) {
            // Invalid arguments, an identity argument or an unknown tool: never acted on.
            return SemanticOutcome.NO_DECISION;
        }
        if (outcome.externallyShareable()) turn.permitExternalUse();
        if ("UNSUPPORTED_WRITE".equals(result.reasonCode())) {
            WorkflowIntent workflow = classifyWorkflowIntent(message);
            String guidance = workflow == null ? null : workflowGuidance(workflow, actor.role());
            if (guidance != null) return new SemanticOutcome(
                    new MyHrChatResponse(ACTION_REQUEST_REPLY + " " + guidance), true, true, false);
        }
        if (result.kind() != WaviToolResult.Kind.CLARIFY) {
            turn.answeredWith(decision.tool(), withResolvedPerson(actor, decision.tool(), outcome.arguments(), message));
        }
        else turn.answeredWith(WaviTools.CLARIFY, WaviTools.CLARIFY.equals(decision.tool()) ? outcome.arguments() : Map.of());
        MyHrChatResponse response = new MyHrChatResponse(result.text());
        return new SemanticOutcome(result.pageKeys() == null ? response : response.withActions(result.pageKeys()), true,
                true, false);
    }

    /**
     * Tenant isolation at the point of use: a retrieved chunk recorded for ANOTHER organization is never grounded,
     * cited or shown, even if retrieval itself misbehaved. Retrieval already filters by organization twice; this is
     * the last check before any policy text reaches a model or a user.
     */
    private static KnowledgeRetrievalService.RetrievalResult sameOrganizationOnly(
            KnowledgeRetrievalService.RetrievalResult retrieval, VerifiedMyHrActor actor) {
        if (retrieval.chunks() == null || retrieval.chunks().isEmpty()) return retrieval;
        List<KnowledgeRetrievalService.RetrievedChunk> kept = retrieval.chunks().stream()
                .filter(chunk -> chunk.organizationId() == null || chunk.organizationId().equals(actor.organizationId()))
                .toList();
        if (kept.size() == retrieval.chunks().size()) return retrieval;
        log.warn("MyHR chat dropped {} retrieved chunk(s) recorded for another organization",
                retrieval.chunks().size() - kept.size());
        return new KnowledgeRetrievalService.RetrievalResult(kept.isEmpty()
                ? KnowledgeRetrievalService.RetrievalStatus.NO_RELEVANT_KNOWLEDGE : retrieval.status(), kept,
                retrieval.candidateCount(), retrieval.timings());
    }

    /**
     * A router decision about one person (named in the message, never in the arguments) is remembered by that
     * person's position in the reviewer's CURRENT scope, so "how many hours did he submit?" can follow it. Nothing is
     * added when the message names no one in scope.
     */
    private Map<String, Object> withResolvedPerson(VerifiedMyHrActor actor, String tool, Map<String, Object> arguments,
                                                   String message) {
        if (!isReviewer(actor) || arguments.containsKey("ordinal") || !PERSON_TOOLS.contains(tool)
                || WaviTools.GET_DIRECT_REPORTS.equals(tool) || message == null) return arguments;
        PersonResolution resolution = resolvePerson(teamScope(actor), message, null);
        if (resolution.person() == null) return arguments;
        Map<String, Object> remembered = new java.util.LinkedHashMap<>(arguments);
        remembered.putAll(personState(resolution.person()));
        return remembered;
    }

    /** Null when the tool layer is off. */
    WaviToolLayer toolLayer() {
        return toolLayer;
    }

    /**
     * Answers a deterministic route through the tool layer when it is on; otherwise, or when the tool layer cannot
     * reproduce the route, the existing answer. Only the verified actor is ever passed to a tool.
     */
    private MyHrChatResponse viaTools(VerifiedMyHrActor actor, Turn turn, WaviRouterDecision.Intent intent, String tool,
                                      Map<String, Object> arguments, Supplier<MyHrChatResponse> existing) {
        if (toolLayer == null || actor == null || arguments == null) {
            MyHrChatResponse answer = existing.get();
            // The fast-path decision is known even without the tool layer: remembered (tool + enum arguments only)
            // so a short follow-up can continue it.
            if (turn != null && actor != null && arguments != null && answer != null) turn.answeredWith(tool, arguments);
            return answer;
        }
        WaviToolLayer.Outcome outcome = toolLayer.execute(WaviRouterDecision.fastPath(intent, tool, arguments), actor,
                LocalDate.now(clock));
        WaviToolResult result = outcome.result();
        if (result.text() == null || result.kind() == WaviToolResult.Kind.REJECTED
                || WaviToolLayer.isHandoff(result) || "NO_ANSWER".equals(result.reasonCode())) {
            // A fast-path decision the layer cannot answer is a mapping defect; the user still gets the existing answer.
            log.warn("{} fast-path-fallback tool={} result={}", WaviToolLayer.TOOL_MARKER, tool, result.reasonCode());
            return existing.get();
        }
        if (turn != null && outcome.externallyShareable()) turn.permitExternalUse();
        if (turn != null) turn.answeredWith(tool, outcome.arguments());
        MyHrChatResponse response = new MyHrChatResponse(result.text());
        return result.pageKeys() == null ? response : response.withActions(result.pageKeys());
    }

    private MyHrChatResponse timesheetRangeViaTools(VerifiedMyHrActor actor, Turn turn,
                                                    MyHrConversationIntents.TimesheetRange range) {
        return viaTools(actor, turn, WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_MY_TIMESHEETS,
                WaviTimesheetPeriod.arguments(range, LocalDate.now(clock)), () -> timesheetRangeResponse(actor, range));
    }

    /** The existing answers, for the tools: the same methods and lookups the routes above use. */
    private final class ToolAnswers implements WaviRecordAnswers {

        @Override
        public MyHrChatResponse dailyTimesheetHours(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
            if (actor.role() == UserRole.COMPANY_ADMIN) return new MyHrChatResponse(ADMIN_NO_TIMESHEET_SUBMISSIONS);
            var data = timesheetService.getOwnDailyHours(actor.userId(), range == null ? null : range.from(),
                    range == null ? null : range.to());
            return new MyHrChatResponse(WaviDailyHoursAnswer.render(data))
                    .withActions(List.of(MyHrConversationIntents.Page.TIMESHEETS.name()));
        }

        @Override
        public String role(VerifiedMyHrActor actor, MyHrConversationIntents.AskedRole asked) {
            return asked == MyHrConversationIntents.AskedRole.ANY
                    ? MyHrConversationIntents.roleAndTitleReply(actor.role(), ownJobTitle(actor))
                    : MyHrConversationIntents.roleReply(asked, actor.role());
        }

        @Override
        public String profileField(VerifiedMyHrActor actor, String field) {
            return formatProfileAnswer(employeeManagementService.getOwnDetail(actor.userId()), ProfileQuestion.valueOf(field));
        }

        @Override
        public List<String> profileFields() {
            // The router-facing fields only; the manager name parts and relationship are answered by the question
            // frame, which keeps the live router prompt (and its approved spend ceiling) unchanged.
            return java.util.Arrays.stream(WaviTools.ProfileField.values()).map(Enum::name).toList();
        }

        @Override
        public String latestTimesheetSubmission(VerifiedMyHrActor actor) {
            return actor.role() == UserRole.COMPANY_ADMIN ? ADMIN_NO_TIMESHEET_SUBMISSIONS
                    : formatLatestTimesheetSubmission(timesheetService.getLatestSubmissionForUser(actor.userId()));
        }

        @Override
        public String latestTimesheet(VerifiedMyHrActor actor) {
            if (actor.role() == UserRole.COMPANY_ADMIN) return ADMIN_NO_OWN_TIMESHEET_STATUS;
            return formatRecentTimesheetStatus(timesheetService.getMostRecentStatusForUser(actor.userId()));
        }

        @Override
        public MyHrChatResponse timesheetRange(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
            return timesheetRangeResponse(actor, range);
        }

        @Override
        public MyHrChatResponse pendingWork(VerifiedMyHrActor actor) {
            return pendingOverviewResponse(actor);
        }

        @Override
        public MyHrChatResponse pendingWork(VerifiedMyHrActor actor, WaviTools.WorkArea area, Integer ordinal) {
            return pendingWorkResponse(actor, area, ordinal, null, null);
        }

        @Override
        public MyHrChatResponse teamTimesheets(VerifiedMyHrActor actor, WaviTeamTimesheetQuery query, String message) {
            return teamTimesheetsResponse(actor, query, message, null, false);
        }

        @Override
        public String directReports(VerifiedMyHrActor actor) {
            return directReportsReply(actor);
        }

        @Override
        public String directReportsOutsideRole(UserRole role) {
            return directReportsOutsideRoleReply(role);
        }

        @Override
        public String reviewQueue(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) {
            return switch (queue) {
                case TIMESHEETS -> timesheetQueueResponse(actor, actor.userId(), TimesheetQueueQuestion.MY_QUEUE);
                case PROFILE_CHANGES -> profileChangeQueueResponse(actor, actor.userId(), ProfileChangeQueueQuestion.MY_QUEUE);
                case DOCUMENT_REQUESTS -> documentRequestQueueResponse(actor, actor.userId(),
                        DocumentRequestQueueQuestion.MY_QUEUE);
            };
        }

        @Override
        public String reviewQueueOutsideRole(WaviTools.ReviewQueue queue) {
            return switch (queue) {
                case TIMESHEETS -> TIMESHEET_QUEUE_REVIEWERS_ONLY;
                case PROFILE_CHANGES -> PROFILE_CHANGE_QUEUE_REVIEWERS_ONLY;
                case DOCUMENT_REQUESTS -> DOCUMENT_QUEUE_REVIEWERS_ONLY;
            };
        }

        @Override
        public String ownRequest(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Intent intent) {
            return ownRecordResponse(actor, intent);
        }

        @Override
        public String payrollSchedule(VerifiedMyHrActor actor) {
            return payDateReply(actor, null);
        }

        @Override
        public String personInScope(VerifiedMyHrActor actor, String message) {
            if (!isReviewer(actor)) return OTHER_PERSON_REPLY;
            PersonResolution resolution = resolvePerson(teamScope(actor), message, null);
            if (resolution.person() != null) {
                return "Yes - the person named in the question is one of the people you can see in Waypoint.";
            }
            if (resolution.problem() != null) {
                // The existing reply may list the matching names; a yes/no answer must not.
                return "Several people you can see match that name. Ask which one is meant.";
            }
            return "No - nobody you can see in Waypoint matches the name in the question, or the question names no one.";
        }

        @Override
        public MyHrChatResponse policyCatalog(VerifiedMyHrActor actor) {
            return policyCatalogResponse(actor, null);
        }

        @Override
        public WorkTotals workTotals(VerifiedMyHrActor actor) {
            if (!isReviewer(actor)) return null;
            boolean admin = actor.role() == UserRole.COMPANY_ADMIN;
            // The same three queue summaries the review pages and the overview use, scoped by the services to this reviewer.
            MyHrTimesheetQueueSummary timesheets = timesheetService.queueSummary(actor.userId());
            MyHrProfileChangeQueueSummary profileChanges = profileChangeRequestService.queueSummary(actor.userId());
            long documents = 0;
            if (documentRequestService != null) {
                MyHrDocumentRequestQueueSummary summary = documentRequestService.queueSummary(actor.userId());
                documents = admin ? summary.pendingAdminApproval() : summary.pendingFulfillment() + summary.awaitingI983Review();
            }
            long total = timesheets.submitted() + profileChanges.pending() + documents;
            String text = "Waiting for you (" + (admin ? "in your organization" : "assigned to you") + "): "
                    + timesheets.submitted() + " submitted " + plural(timesheets.submitted(), "timesheet") + " to review; "
                    + profileChanges.pending() + " pending profile change " + plural(profileChanges.pending(), "request") + "; "
                    + documents + " document " + plural((int) documents, "request") + " to act on. In total: " + total + " "
                    + plural((int) total, "item") + ".";
            return new WorkTotals(text, List.of(MyHrConversationIntents.Page.TIMESHEETS.name(),
                    MyHrConversationIntents.Page.PROFILE_CHANGE_REQUESTS.name(),
                    admin ? MyHrConversationIntents.Page.DOCUMENT_APPROVALS.name() : MyHrConversationIntents.Page.DOCUMENT_REQUESTS.name()),
                    List.of((long) timesheets.submitted(), (long) profileChanges.pending(), documents, total));
        }

        @Override
        public MyHrChatResponse listPeople(VerifiedMyHrActor actor) {
            if (!isReviewer(actor)) return null;
            List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = teamScope(actor);
            if (people.isEmpty()) return new MyHrChatResponse("There is nobody in your scope.");
            int shown = Math.min(people.size(), MAX_LISTED_REVIEW_ITEMS);
            StringBuilder reply = new StringBuilder().append(people.size()).append(people.size() == 1 ? " person" : " people")
                    .append(" you can see:");
            for (com.waypoint.dto.myhr.EmployeeSummaryResponse person : people.subList(0, shown)) {
                reply.append("\n- ").append(displayName(person.name())).append(" - ")
                        .append(isBlank(person.designation()) ? "no job title recorded" : person.designation());
                if (!isBlank(person.personType())) reply.append(" (").append(person.personType()).append(')');
            }
            if (people.size() > shown) reply.append("\nand ").append(people.size() - shown).append(" more");
            return new MyHrChatResponse(reply.toString()).withActions(List.of(MyHrConversationIntents.Page.EMPLOYEES.name()));
        }

        @Override
        public String workflowGuidance(String topic, UserRole role) {
            for (WorkflowIntent intent : WorkflowIntent.values()) {
                if (intent.name().equals(topic)) return MyHrChatService.this.workflowGuidance(intent, role);
            }
            return null;
        }

        @Override
        public List<String> workflowTopics() {
            // Knowledge Base management is answered by the question frame, not offered to the router (prompt budget).
            return java.util.Arrays.stream(WorkflowIntent.values()).filter(intent -> intent != WorkflowIntent.KNOWLEDGE_BASE)
                    .map(Enum::name).toList();
        }

        @Override
        public MyHrChatResponse peopleSummary(VerifiedMyHrActor actor) {
            return peopleSummaryResponse(actor);
        }

        @Override
        public MyHrChatResponse reviewHistory(VerifiedMyHrActor actor, String queue, String decision) {
            return reviewHistoryResponse(actor, queue, decision);
        }

        @Override
        public MyHrChatResponse teamMember(VerifiedMyHrActor actor, String field, Integer ordinal, String message) {
            return teamMemberResponse(actor, field, ordinal, message, null, false);
        }

        @Override
        public MyHrChatResponse teamTimesheets(VerifiedMyHrActor actor, String status, String message) {
            return teamTimesheetsResponse(actor, status, message, null, false);
        }

        @Override
        public MyHrChatResponse myTimesheetList(VerifiedMyHrActor actor, String status) {
            return myTimesheetListResponse(actor, status);
        }

        @Override
        public MyHrChatResponse myRequestList(VerifiedMyHrActor actor, String kind, String status) {
            return myRequestListResponse(actor, kind, status);
        }

        @Override
        public MyHrChatResponse reviewQueueItems(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) {
            return reviewQueueItemsResponse(actor, queue);
        }

        @Override
        public MyHrChatResponse timesheetHours(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) {
            return timesheetHoursResponse(actor, range);
        }

        @Override
        public String unsupportedField(MyHrQuestionInterpreter.UnsupportedField field) {
            return unsupportedFieldReply(field);
        }
    }
}
