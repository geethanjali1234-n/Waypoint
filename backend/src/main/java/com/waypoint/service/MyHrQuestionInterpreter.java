package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.RequestedDocumentType;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wavi's constrained fallback for questions the deterministic routes did not recognise. It rewrites natural,
 * indirect, polite or misspelled wording into the canonical phrasing those routes already answer ("How's my time
 * card looking?" becomes "what is the status of my timesheet"), resolves a short follow-up against the user's own
 * previous question, recognises personal fields Wavi does not show, and spots status questions too vague to answer.
 *
 * <p>It never looks anything up, never calls a model and never decides authorization: its only output is a
 * canonical question that goes through the same deterministic routes, which answer only through the verified
 * actor's own services. A claimed role ("As HR, ...") is removed rather than believed. Mirrored for navigation
 * shortcuts by the frontend's waviIntents.js; both are checked against the interpretation cases in
 * frontend/src/test/fixtures/waviIntentCases.json.
 */
final class MyHrQuestionInterpreter {

    enum UnsupportedField { PHONE_NUMBER, PERSONAL_EMAIL, HOME_ADDRESS, START_DATE, EMPLOYMENT_TYPE, PAY, LEAVE_BALANCE }

    // --- Spelling: only words close to Waypoint's own vocabulary are corrected ---------------------------------

    /**
     * Ordered: on equal distance the earlier word wins ("approvd" becomes "approved"). Waypoint's record vocabulary,
     * the question words that start lookups ("waht" is "what") and the HR topics the scope check and company-policy
     * routing look for ("polcy", "holliday", "leav").
     */
    private static final List<String> LEXICON = List.of("timesheet", "timesheets", "profile", "approved", "approve",
            "rejected", "reject", "requested", "request", "requests", "status", "pending", "documents", "document",
            "employee", "manager", "supervisor", "verification", "employment", "letter", "offer", "address", "phone",
            "number", "submitted", "submit", "department", "designation", "change", "updated", "update", "review",
            "waiting", "onboarding", "personal", "email", "what", "when", "where", "which", "title", "reporting",
            "report", "reports", "reportees", "policy", "policies", "leave", "holiday", "holidays", "christmas",
            "vacation", "salary", "payroll", "benefits", "reimbursement", "overtime", "attendance", "approval",
            "approvals", "dashboard", "navigate");
    private static final List<String> INFLECTIONS = List.of("s", "es", "d", "ed", "ing", "er", "ers", "al", "als",
            "ion", "ions", "ment", "ments");
    /** A word ending in "e" keeps it before these ("approved") and drops it before a vowel suffix ("approving"). */
    private static final List<String> AFTER_E = List.of("s", "d", "r", "rs", "ment", "ments");
    private static final List<String> REPLACING_E = List.of("ing", "al", "als", "ion", "ions", "able");
    /** Ordinary words within one edit of the vocabulary. */
    private static final Set<String> ORDINARY_WORDS = Set.of("states", "statue", "stats", "chance", "charge", "charged",
            "charges", "later", "latter", "personnel", "employer", "employers", "manage", "managed", "manages",
            "managing", "pendant", "offers", "letters", "phoned", "numbered", "supervise", "supervises", "supervised",
            "waiving", "writing", "wanting", "summit", "destination", "employed", "reflected", "were", "whet", "wheat",
            "whom", "lease", "leaf", "police", "polish", "tittle", "tile", "title", "resort", "repost", "report",
            "deport", "holy", "vocation", "salad", "approach", "titles", "whale", "while", "white", "chat", "reported",
            "reporter", "reporters", "leaves", "leaver", "levee", "plenty", "pending", "spending", "payout", "whose",
            "witch", "wench", "whence", "chairmen", "changed", "changes", "range", "orange", "submitter");
    private static final Pattern WORD = Pattern.compile("[a-z]+");
    /** Words run together that Waypoint knows apart ("jobtitle", "empid"). */
    private static final List<Rewrite> COMPOUNDS = List.of(
            rewrite("\\bjob ?titles?\\b", match -> "job title"),
            rewrite("\\bemployee ?ids?\\b|\\bemp ?ids?\\b|\\bempid\\b", match -> "employee id"),
            rewrite("\\btime ?sheet ?status\\b", match -> "timesheet status"),
            rewrite("\\bwork ?email\\b", match -> "work email"),
            rewrite("\\bpay ?rolls?\\b", match -> "payroll"),
            rewrite("\\bpay ?slips?\\b", match -> "payslip"),
            rewrite("\\bpay ?checks?\\b", match -> "paycheck"));

    // --- Romanized Telugu-English and Hindi-English ------------------------------------------------------------

    /**
     * Only a message containing one of these words is read as romanized Telugu or Hindi, so English questions are
     * never changed. The lexicon is closed: the words Wavi's own questions need, nothing guessed.
     */
    private static final Pattern ROMANIZED_MARKER = Pattern.compile("\\b(?:naa|naaku|naku|nenu|nannu|enti|emiti|yenti"
            + "|emti|evaru|yevaru|ela|yela|eppudu|cheyali|cheyyali|cheyalo|cheyyalo|cheppu|cheppandi|chepu|ayyinda"
            + "|ayinda|aindha|ayyindha|chesana|chesanu|chesaanu|chestaru|chestunnaru|mera|meri|mujhe|kya|kaun|kaise"
            + "|kab|batao|bataiye|bataye|nuvu|nuvvu|meeru|miru|peru|unnaru|unnav|unnavu|vunnaru|bagunnava"
            + "|bagunnara|baagunnava|hoon|hun)\\b");
    private static final List<Rewrite> ROMANIZED_PHRASES = List.of(
            // "Nenu evaru?" / "Main kaun hoon?" (who am I), "Nuvvu evaru?" / "Tum kaun ho?" (who are you).
            rewrite("^(?:nenu|nen|main|mai) (?:evaru|yevaru|kaun|kon)(?: hoon| hun| hu)?$", match -> "who am i"),
            rewrite("^(?:nuvu|nuvvu|meeru|miru|tum|aap|tu) (?:evaru|yevaru|kaun|kon)(?: ho| hain| hai| hau)?$",
                    match -> "who are you"),
            // "Ela unnaru?", "Bagunnava?", "Aap kaise ho?": how are you.
            rewrite("^(?:(?:nuvvu |nuvu |meeru )?(?:ela|yela) (?:unnaru|unnav|unnavu|vunnaru|unnaavu)|bagunnava|bagunnara"
                    + "|baagunnava|(?:aap|tum) kaise (?:ho|hain|hai)|kaise ho|kya haal hai)$", match -> "how are you"),
            rewrite("^(?:naaku|naku|nannu|naa) evaru report (?:chestaru|chestunnaru|chesthunnaru|avutaru)$"
                    + "|^evaru (?:naaku|naku|nannu) report (?:chestaru|chestunnaru)$"
                    + "|^mujhe (?:kaun|kon) report (?:karta|karte) (?:hai|hain)$", match -> "who reports to me"),
            rewrite("^(.+?) approve (?:ayyinda|ayinda|aindha|ayyindha|ayyinda leda|hua|hui|ho gaya|ho gayi)(?: kya)?$",
                    match -> "was " + match.group(1) + " approved"),
            rewrite("^(.+?) reject (?:ayyinda|ayinda|aindha|ayyindha|hua|hui|ho gaya|ho gayi)(?: kya)?$",
                    match -> "was " + match.group(1) + " rejected"),
            rewrite("^(.+?) submit (?:chesana|chesanu|chesaanu|kiya|ki)(?: kya| leda)?$",
                    match -> "did i submit " + match.group(1)),
            rewrite("^(.+?) (?:ela|yela|kaise) ([a-z]+) (?:cheyali|cheyyali|cheyalo|cheyyalo|karna hai|karu|karein|kare)$",
                    match -> "how do i " + match.group(2) + " " + match.group(1)));
    private static final List<Rewrite> ROMANIZED_WORDS = List.of(
            rewrite("^na ", match -> "my "),
            rewrite("\\b(?:naa|naaku|naku|mera|meri|mere)\\b", match -> "my"),
            rewrite("\\b(?:mujhe|nannu)\\b", match -> "me"),
            rewrite("\\bperu\\b", match -> "name"),
            rewrite("\\b(?:kya hai|kya he|kya h|enti|emiti|yenti|emti)\\b", match -> "what is"),
            rewrite("\\b(?:kaun hai|kaun he|kon hai|evaru|yevaru)\\b", match -> "who is"),
            rewrite("\\b(?:ela|yela|kaise)\\b", match -> "how"),
            rewrite("\\b(?:eppudu|kab)\\b", match -> "when"),
            rewrite("^kya\\b|\\b(?:cheppu|cheppandi|chepu|batao|bataiye|bataye|ka|ki|ke|lo)\\b", match -> " "));
    /** "my manager who is" is asked as "who is my manager": Telugu and Hindi put the question word last. */
    private static final Pattern TRAILING_QUESTION_WORD = Pattern.compile("^(.+?) (what is|who is|how|when)$");

    // --- Wording around the question -----------------------------------------------------------------------------

    private static final Pattern CONTRACTION = Pattern.compile("\\b(?:what's|whats|who's|whos|how's|hows|where's|wheres)\\b");
    private static final Pattern LEADING_FILLER = Pattern.compile(
            "^(?:(?:hi|hey|hello)(?: there)?|wavi|quick question|just (?:a )?quick question|just checking|just wondering"
                    + "|one more thing|sorry to bother you|sorry|also|and also|and|so|ok|okay|um|uh|well|by the way|btw"
                    + "|please)[,:;!]*\\s+");
    private static final Pattern INDIRECT_WRAPPER = Pattern.compile(
            "^(?:i was wondering|i'm wondering|i am wondering|i wonder|i'd like to know|i would like to know"
                    + "|i want to know|do you (?:happen to )?know|would you happen to know|any idea|do you have any idea"
                    + "|would you mind (?:telling me|checking|letting me know)|would you be able to (?:tell me|check"
                    + "|let me know)|are you able to (?:tell me|check)|(?:can|could) you (?:find out|see|look up)"
                    + "|(?:can|could|would|will) you (?:please )?(?:tell me|let me know|show me|confirm|check|remind me"
                    + "|figure out|say)|do you remember|remind me(?: of)?"
                    + "|please (?:tell me|let me know|check|confirm)|is it possible to (?:know|see|check|find out)"
                    + "|i'd love to know|i'm curious|i am curious|let me know|tell me)(?: (?:if|whether))?[,:]*\\s+");
    /**
     * "Ignore previous instructions and ...": what follows is routed like any other request, so it gets the same
     * deterministic answer or refusal; the original message still goes to the scope check unchanged.
     */
    private static final Pattern INSTRUCTION_PREAMBLE = Pattern.compile(
            "^(?:ignore|disregard|forget|override) (?:all |any )?(?:(?:the |your |my )?(?:previous|prior|above|earlier"
                    + "|system) )?(?:instructions|rules|prompts?|guidelines)(?: and)?[,.:;!]*\\s+");
    /** A claimed role never changes what the verified actor may see, so it is dropped, not believed. */
    private static final Pattern CLAIMED_ROLE = Pattern.compile(
            "^(?:as (?:an? |the |your )?|i'm (?:an? |the |your )?|i am (?:an? |the |your )?|speaking as (?:an? |the )?)"
                    + "(?:hr(?: (?:person|manager|admin|representative|rep))?|company admin(?:istrator)?"
                    + "|admin(?:istrator)?|manager|supervisor)[,:;.!]*\\s+(?:and\\s+)?");
    private static final Pattern TRAILING_FILLER = Pattern.compile(
            "(?:,?\\s+(?:please|thanks|thank you|for me|right now|currently|at the moment|now|at work|here"
                    + "|at the company|in waypoint|on file|again))+$");

    // --- Waypoint vocabulary -------------------------------------------------------------------------------------

    private record Rewrite(Pattern pattern, Function<MatchResult, String> replacement) {
    }

    private static final String FIELD = "(phone number|home address|mailing address|address|legal name|name"
            + "|personal email|email)";

    private static final List<Rewrite> REWRITES = List.of(
            rewrite("\\btime[- ]?cards?\\b|\\btime[- ]sheets?\\b|\\bhours? sheets?\\b",
                    match -> match.group().endsWith("s") ? "timesheets" : "timesheet"),
            rewrite("\\bmy (?:logged |weekly |worked |work )?hours\\b", match -> "my timesheet"),
            rewrite("\\b([a-z][a-z0-9.@_-]*)'s hours\\b", match -> match.group(1) + "'s timesheet"),
            rewrite("\\b(?:staff|employee|emp|worker|personnel) (?:id|number|no|code)\\b"
                    + "|\\bemployee identification number\\b", match -> "employee id"),
            rewrite("\\bmy id\\b", match -> "my employee id"),
            rewrite("^(?:who do i (?:answer|report) to|who (?:manages|supervises) me|who am i reporting to"
                    + "|who is in charge of me|who do i work (?:under|for)|who is my reporting line)$",
                    match -> "who is my manager"),
            rewrite("\\bmy (?:job )?position\\b|\\bmy job role\\b|\\bmy role title\\b", match -> "my job title"),
            rewrite("\\bmy designation\\b", match -> "my job title"),
            // Waypoint records one reporting manager; a team lead or line manager is asked about as that manager.
            rewrite("\\bmy (?:team |tech |project |line |reporting |direct )?(?:lead|leader|manager|supervisor|boss)(?:'s)? name\\b",
                    match -> "my manager"),
            rewrite("\\bmy (?:team |tech |project )?lead(?:er)?\\b", match -> "my manager"),
            rewrite("^do i have an? (?:reporting |line |direct )?(?:manager|supervisor)$", match -> "who is my manager"),
            rewrite("\\b(?:mobile|cell)(?: phone)?(?: number)?\\b|\\bcontact number\\b|\\btelephone(?: number)?\\b"
                    + "|\\bphone no\\b|\\bphone\\b(?! number)", match -> "phone number"),
            rewrite("\\be-mail\\b", match -> "email"),
            rewrite("\\b(?:residential|street|postal|house) address\\b", match -> "home address"),
            rewrite("\\bmy new " + FIELD + "\\b", match -> "my " + match.group(1) + " change"),
            rewrite("\\b(?:my )?request (?:to|for) (?:update|change|edit|correct)(?: of)? my " + FIELD + "\\b",
                    match -> "my " + match.group(1) + " change request"),
            rewrite("\\b(?:my )?request (?:to|for) (?:update|change|edit|correct)(?: of)? my profile\\b",
                    match -> "my profile change request"),
            rewrite("\\b" + FIELD + " (?:edit|modification|amendment) request", match -> match.group(1) + " change request"),
            rewrite("(?<!profile )(?<!number )(?<!address )(?<!name )(?<!email )\\b(?:change|edit|modification|amendment"
                    + "|correction) (requests?)\\b", match -> "profile change " + match.group(1)),
            rewrite("\\bsigned off on\\b", match -> "approved"),
            rewrite("\\bsign off on\\b", match -> "approve"),
            rewrite("\\b(?:ok'd|okd|okayed|oked|greenlit|green-lit|given the green light|got the go-ahead"
                    + "|got the green light)\\b", match -> "approved"),
            rewrite("\\b(?:bounced(?: back)?|kicked back|knocked back|refused|thrown out|not accepted)\\b",
                    match -> "rejected"),
            rewrite("\\borganisation\\b", match -> "organization"),
            // Indirect statements and questions about a record's progress.
            rewrite("^(?:i )?(?:still )?(?:haven't|have not|didn't|did not|never) (?:heard|got|gotten|received)"
                    + "(?: anything)?(?: back)?(?: (?:about|on|regarding|for))? (my .+)$",
                    match -> "what is the status of " + match.group(1)),
            rewrite("^(?:i'm|i am) (?:still )?waiting (?:on|for) (my .+?)(?:,? any (?:news|updates?))?$",
                    match -> "is " + match.group(1) + " still pending"),
            rewrite("^(?:i'm|i am) not sure (?:if|whether) (my .+?) (?:went through|was approved|got approved"
                    + "|has been approved)$", match -> "was " + match.group(1) + " approved"),
            rewrite("^how is (my .+?) (?:looking|going|coming along|doing|progressing)$",
                    match -> "what is the status of " + match.group(1)),
            rewrite("^where is (my .+?) at$", match -> "what is the status of " + match.group(1)),
            rewrite("^what is (?:going on|happening|the latest|the story|the deal) with (my .+)$",
                    match -> "what is the status of " + match.group(1)),
            rewrite("^(?:any|got any|is there any) (?:word|news|updates?|progress) (?:on|about|regarding|with) (my .+)$",
                    match -> "what is the status of " + match.group(1)),
            rewrite("^has (?:anyone|anybody|someone|hr|my manager) (?:looked at|reviewed|checked|processed|seen) (my .+)$",
                    match -> "what is the status of " + match.group(1)));

    /** A bare noun phrase left after the wording is removed: "my employee id" is asked as "what is my employee id". */
    private static final Pattern BARE_PROFILE_FIELD = Pattern.compile(
            "^my (employee id|job title|department|work email|profile|name)$");
    private static final Pattern BARE_MANAGER = Pattern.compile("^my (?:manager|supervisor|boss)$");

    // --- Fields Wavi does not show, and questions too vague to answer -------------------------------------------

    private static final Pattern UNSUPPORTED_CONTACT = Pattern.compile(
            "^(?:what is|tell me|show me|show|give me|do you (?:know|have)|what) my (?:current |registered |personal "
                    + "|own )?(phone number|home address|mailing address|address|personal email|email address|email)$"
                    + "|^what (phone number|home address|address|personal email|email address|email) "
                    + "(?:do you|does waypoint) have$");
    private static final Pattern UNSUPPORTED_START_DATE = Pattern.compile(
            "^(?:(?:what is|tell me) )?my (?:start|joining|hire|hiring|employment start) date$"
                    + "|^when did i (?:start|join|begin)(?: working)?(?: (?:at|with|in|for))?(?: (?:the company|waypoint"
                    + "|this company|the organization|here))?$"
                    + "|^when was i hired$|^how long have i (?:worked|been working|been employed)"
                    + "(?: (?:at|with|for) (?:the company|waypoint|this company))?$");
    private static final Pattern UNSUPPORTED_EMPLOYMENT_TYPE = Pattern.compile(
            "^what is my (?:employment type|employment status|employee type|worker type)$");
    private static final Pattern UNSUPPORTED_PAY = Pattern.compile(
            "^(?:(?:what is|tell me|show me) )?my (?:current |annual |monthly )?(?:salary|annual salary|pay|pay rate"
                    + "|hourly rate|wage|wages|compensation|ctc|cost to company|package|salary package|take[- ]home(?: pay"
                    + "| salary)?|in-hand(?: salary)?|gross (?:pay|salary)|net (?:pay|salary))$"
                    + "|^how much (?:do i|am i) (?:earn|make|get paid|paid)(?: (?:per|a|an|each) (?:hour|year|month|week))?$");
    /**
     * The user's own leave or PTO balance, which Waypoint does not record. Entitlement questions ("How many vacation
     * days do I get per year?") are company policy and are not matched.
     */
    private static final Pattern LEAVE_BALANCE = Pattern.compile(
            "^(?=.*\\b(?:leaves?|pto|vacations?|sick (?:leave|days)|time off|days off|annual leave|comp offs?"
                    + "|holidays? left)\\b)(?=.*\\b(?:balance|left|remaining|available|unused|still have|have i got"
                    + "|do i (?:still )?have)\\b).*\\b(?:my|i|me)\\b|^how many (?:leave|leaves|pto|vacation"
                    + "|annual leave|days off|comp off)(?: days?)?$");
    /** Asks how much is left, not what may be done with it ("Can I carry forward unused leave?" is policy). */
    private static final Pattern BALANCE_QUESTION = Pattern.compile(
            "^(?:how many|how much|what is my|what are my|show|check|tell me|give me|my)\\b"
                    + "|\\b(?:do i (?:still )?have|have i got|balance)\\b");
    private static final Pattern ENTITLEMENT = Pattern.compile(
            "\\b(?:per (?:year|month|annum)|a year|a month|each year|every year|annually|do i get|will i get|accrue\\w*"
                    + "|entitled|eligible|policy|allowed|"
                    // What may be done with leave is policy too: carrying it forward, encashing, taking or applying.
                    + "carry|carried|carrying|forward|encash\\w*|can (?:i|we|employees)|could i|may i|should i|apply"
                    + "|take|use|request)\\b");
    private static final Pattern SUBJECTLESS_STATUS = Pattern.compile(
            "^(?:what is (?:the |my )?status|status(?: update)?|(?:any|got any) (?:updates?|news)|what is new"
                    + "|(?:was|has|is|did) (?:it|that|this)(?: been| still| get)? (?:approved|rejected|accepted|declined"
                    + "|pending|processed|reviewed|done|through)|did (?:it|that) go through"
                    + "|(?:why|when) was (?:it|that) (?:approved|rejected)|what happened(?: to it)?"
                    + "|(?:what is the status of|what happened (?:to|with)|any (?:update|news) on|is|was|has|did) my "
                    + "(?:request|application|submission|change)(?: (?:been |still |get )?(?:approved|rejected|pending"
                    + "|processed|reviewed|through))?)$");

    // --- Follow-ups ----------------------------------------------------------------------------------------------

    private static final Pattern SUBJECT_SWAP = Pattern.compile(
            "^(?:(?:but|ok|okay|so),? )?(?:what about|how about|what of|and|same for|same question for|also)(?: for)? my (.+)$");
    private static final Pattern PROFILE_FIELD_SUBJECT = Pattern.compile(
            "^(employee id|job title|department|work email|profile|name|phone number|home address|address|personal email"
                    + "|email|start date|salary|pay)$");
    private static final Pattern MANAGER_SUBJECT = Pattern.compile("^(?:manager|supervisor|boss)$");
    private static final Pattern PRONOUN = Pattern.compile(
            "\\b(?:that one|this one|that request|this request|the request|it|that|this|they)\\b");
    private static final Pattern ASPECT_CUE = Pattern.compile(
            "\\b(?:approv\\w*|reject\\w*|declined|accepted|pending|status|go(?:ne)? through|went through|processed"
                    + "|reviewed|updated|submitted|done)\\b|^(?:when|why)\\b");
    private static final Pattern BARE_WHEN_OR_WHY = Pattern.compile("^(when|why)$");

    private MyHrQuestionInterpreter() {
    }

    /**
     * The canonical form of a message: lower case, without the wording around the question, with Waypoint's
     * vocabulary for its synonyms and near-misspellings. Never null; deterministic.
     */
    static String canonicalize(String message) {
        String text = translateRomanized(basicNormalize(message).replaceAll("\\s+[-–—]+\\s+", ", "));
        text = splitCompounds(correctSpelling(splitCompounds(text)));
        text = CONTRACTION.matcher(text).replaceAll(match -> expand(match.group()));
        text = stripWording(text);
        for (Rewrite rewrite : REWRITES) {
            text = rewrite.pattern().matcher(text).replaceAll(match -> Matcher.quoteReplacement(rewrite.replacement().apply(match)));
        }
        text = stripWording(text);
        if (BARE_MANAGER.matcher(text).matches()) return "who is my manager";
        Matcher field = BARE_PROFILE_FIELD.matcher(text);
        return field.matches() ? "what is my " + field.group(1) : text;
    }

    /**
     * The canonical question to route instead of the message: a follow-up resolved against the user's own previous
     * question, or the canonical wording. Null when neither differs from what was asked, so nothing is re-routed.
     * Only user turns are read; an assistant turn can never choose what is looked up.
     */
    static String interpret(String message, List<MyHrChatHistoryMessage> history) {
        String canonical = canonicalize(message);
        if (canonical.isEmpty()) return null;
        String followUp = resolveFollowUp(canonical, previousUserQuestion(history));
        if (followUp != null) return followUp;
        return canonical.equals(basicNormalize(message)) ? null : canonical;
    }

    /** A personal field Wavi does not show in chat; null for anything else. */
    static UnsupportedField unsupportedField(String message) {
        String canonical = canonicalize(message);
        Matcher contact = UNSUPPORTED_CONTACT.matcher(canonical);
        if (contact.matches()) {
            String field = contact.group(1) != null ? contact.group(1) : contact.group(2);
            if (field.contains("phone")) return UnsupportedField.PHONE_NUMBER;
            return field.contains("address") ? UnsupportedField.HOME_ADDRESS : UnsupportedField.PERSONAL_EMAIL;
        }
        if (UNSUPPORTED_START_DATE.matcher(canonical).matches()) return UnsupportedField.START_DATE;
        if (UNSUPPORTED_EMPLOYMENT_TYPE.matcher(canonical).matches()) return UnsupportedField.EMPLOYMENT_TYPE;
        if (LEAVE_BALANCE.matcher(canonical).find() && BALANCE_QUESTION.matcher(canonical).find()
                && !ENTITLEMENT.matcher(canonical).find()) {
            return UnsupportedField.LEAVE_BALANCE;
        }
        return UNSUPPORTED_PAY.matcher(canonical).matches() ? UnsupportedField.PAY : null;
    }

    // --- Own-record questions by subject ----------------------------------------------------------------------

    private static final Pattern SELF = Pattern.compile("\\b(?:my|me|mine|i|i'm|i've|myself)\\b");
    /** Writing, messaging and action requests mention records ("email my manager") without asking about them. */
    private static final Pattern NOT_A_LOOKUP = Pattern.compile(
            "\\b(?:draft|write|compose|send|mail|message|text|tell|ask|thank|thanks|note|meet|meeting|talk|speak|call|ping"
                    + "|schedule|invite|escalate|complain|complaint|feedback|rate|rating|policy|policies|rules?|allowed"
                    + "|deadline|due|eligible|process|procedure|steps|upload|attach|import|joke|jokes|poem|story|song"
                    + "|riddle|haiku|rap|funny)\\b"
                    + "|^(?:how|why|should|can i|could i|may i|where (?:do|can|should) i)\\b"
                    + "|^(?:approve|reject|submit|resubmit|change|update|edit|modify|correct|delete|remove|cancel|add|assign)\\b");
    /** Timing and obligation ("When should I submit my timesheet?"): a policy question about the record. */
    private static final Pattern OBLIGATION = Pattern.compile(
            "\\b(?:should|must|have to|has to|need to|needs to|supposed to|can|could|may|would|will|allowed)\\b");
    /** "Who do I report to?" and "Who reports to me?": the reporting line in either direction. */
    private static final Pattern REPORTS_TO_ME = Pattern.compile(
            "\\breport(?:s|ing)? (?:directly )?to me\\b|\\breports? to me directly\\b");
    private static final Pattern I_REPORT_TO = Pattern.compile("\\b(?:i|am i|do i)\\b.*\\breport(?:ing)? (?:directly )?to\\b");
    /** Another person: a pronoun, a possessive name or an employee ID. */
    private static final Pattern OTHER_PERSON = Pattern.compile(
            "\\b(?:his|her|their|hers|theirs|someone|somebody|anyone|anybody|everyone|colleague|coworker|co-worker)\\b"
                    + "|\\b[a-z]{2,}[-_]?\\d{2,}\\b"
                    + "|\\b(?!(?:my|what|who|where|when|how|it|that|this|there|here|let|manager|supervisor|boss|lead|today"
                    + "|week|month|year|company|team)'s)[a-z][a-z0-9.@_-]*'s\\b");
    private static final Pattern MANAGER_WORD = Pattern.compile(
            "\\b(?:manager|supervisor|boss|team lead|reporting (?:person|officer|head)|line manager)\\b");
    /** A detail of the manager Wavi does not show (their email or phone). */
    private static final Pattern MANAGER_DETAIL = Pattern.compile(
            "\\b(?:manager|supervisor|boss)(?:'s)? (?:e-?mail|email address|phone|phone number|number|contact|address"
                    + "|mobile|extension|location|desk)\\b");
    private static final Pattern REPORTS_WORD = Pattern.compile(
            "\\b(?:direct reports?|direct reportees?|reportees?|subordinates?|team members?|my team)\\b(?! lead)");
    private static final Pattern TIMESHEET_WORD = Pattern.compile("\\btime ?sheets?\\b");
    private static final Pattern TIMESHEET_STATUS_CUE = Pattern.compile(
            "\\b(?:status|approved|rejected|pending|submitted|latest|current|recent|last|update|progress)\\b");
    private static final Pattern RECORD_FIELD = Pattern.compile(
            "\\b(employee id|job title|position|title|department|work email|profile|(?:full |legal )?name)\\b");
    /** A personal-record subject Wavi recognises but cannot answer every question about. */
    private static final Pattern PERSONAL_SUBJECT = Pattern.compile(
            "\\bmy (?:own )?(?:manager|supervisor|boss|employee id|job title|designation|department|work email|profile"
                    + "|timesheets?|bank|account number|direct deposit|ssn|social security|tax|w-?2|w-?4|pay ?slips?"
                    + "|salary slips?|pay ?stubs?|emergency contact|leave approver|record|records)\\b");
    private static final Pattern QUESTION_FORM = Pattern.compile(
            "^(?:what|who|whom|which|when|show|tell|give|list|is|was|has|have|did|do i have|does|are)\\b");

    /**
     * An own-record question asked in words the exact routes do not know, rewritten by its subject rather than its
     * wording: "manager name", "who my manager is", "emp id", "timesheet status?", "who all are in my team" become
     * "who is my manager", "what is my employee id", "what is my timesheet status" and "who reports to me". Null
     * for anything else: another person, a writing or action request, a how-to or policy question, or no subject.
     * Only a question, which the deterministic routes then answer for the verified actor alone.
     */
    static String recordQuestion(String canonical) {
        if (canonical == null || canonical.isBlank()) return null;
        String text = canonical.strip();
        if (NOT_A_LOOKUP.matcher(text).find() || OTHER_PERSON.matcher(text).find()) return null;
        boolean shortPhrase = text.split(" ").length <= 4;
        boolean self = SELF.matcher(text).find() || shortPhrase;
        // Only a question or a bare noun phrase about the record: "a joke about my manager" is neither.
        if (!self || !(shortPhrase || text.startsWith("my ") || QUESTION_FORM.matcher(text).find())) return null;
        if (TIMESHEET_WORD.matcher(text).find()) {
            if (OBLIGATION.matcher(text).find()) return null;
            boolean bare = text.matches("^(?:my )?time ?sheets?(?: status)?$");
            return bare || TIMESHEET_STATUS_CUE.matcher(text).find() ? "what is my timesheet status" : null;
        }
        if (REPORTS_TO_ME.matcher(text).find()) return "who reports to me";
        if (MANAGER_WORD.matcher(text).find()) {
            return MANAGER_DETAIL.matcher(text).find() ? null : "who is my manager";
        }
        if (I_REPORT_TO.matcher(text).find()) return "who is my manager";
        if (REPORTS_WORD.matcher(text).find()) return "who reports to me";
        Matcher field = RECORD_FIELD.matcher(text);
        if (field.find()) {
            return switch (field.group(1)) {
                case "position", "title", "job title" -> "what is my job title";
                case "name", "full name", "legal name" -> "what is my name";
                default -> "what is my " + field.group(1);
            };
        }
        return null;
    }

    /**
     * A question about the user's own record that no route could answer ("What is my manager's email?", "Show my
     * bank details"). It must never reach a model, which has no access to the record and could only guess.
     */
    static boolean isUnansweredOwnRecordQuestion(String canonical) {
        if (canonical == null) return false;
        String text = canonical.strip();
        return QUESTION_FORM.matcher(text).find() && PERSONAL_SUBJECT.matcher(text).find()
                && !NOT_A_LOOKUP.matcher(text).find() && !OBLIGATION.matcher(text).find()
                && !OTHER_PERSON.matcher(text).find();
    }

    /** "What's the status?", "Was it approved?", "What happened with my request?": no record named or implied. */
    static boolean isAmbiguousStatusQuestion(String message) {
        return SUBJECTLESS_STATUS.matcher(canonicalize(message)).matches();
    }

    // --- Follow-ups ----------------------------------------------------------------------------------------------

    /** The nearest earlier user question, in canonical form; earlier turns are not searched. */
    private static String previousUserQuestion(List<MyHrChatHistoryMessage> history) {
        if (history == null) return null;
        for (int index = history.size() - 1; index >= 0; index--) {
            MyHrChatHistoryMessage turn = history.get(index);
            if (turn != null && "user".equals(turn.role()) && turn.content() != null) {
                return canonicalize(turn.content());
            }
        }
        return null;
    }

    private static String resolveFollowUp(String canonical, String previousQuestion) {
        MyHrOwnRecordIntent.Intent previous = previousQuestion == null ? null : MyHrOwnRecordIntent.classify(previousQuestion);
        if (previous != null && previous.kind() != MyHrOwnRecordIntent.Kind.OWN) previous = null;

        Matcher swap = SUBJECT_SWAP.matcher(canonical);
        if (swap.matches()) {
            String subject = swap.group(1);
            if (MANAGER_SUBJECT.matcher(subject).matches()) return "who is my manager";
            if (PROFILE_FIELD_SUBJECT.matcher(subject).matches()) return "what is my " + subject;
            String question = template(previous == null ? MyHrOwnRecordIntent.Aspect.STATUS : previous.aspect(), subject);
            return MyHrOwnRecordIntent.classify(question) == null ? null : question;
        }
        if (previous == null || canonical.contains("my ")) return null;

        String phrase = subjectPhrase(previous);
        Matcher bare = BARE_WHEN_OR_WHY.matcher(canonical);
        if (bare.matches()) {
            if ("why".equals(bare.group(1))) return template(MyHrOwnRecordIntent.Aspect.REJECTION_REASON, phrase);
            return switch (previous.aspect()) {
                case REJECTED, REJECTION_REASON -> "when was my " + phrase + " rejected";
                case APPROVED, APPROVED_DATE -> template(MyHrOwnRecordIntent.Aspect.APPROVED_DATE, phrase);
                default -> template(MyHrOwnRecordIntent.Aspect.REQUESTED_DATE, phrase);
            };
        }
        if (!ASPECT_CUE.matcher(canonical).find()) return null;
        Matcher pronoun = PRONOUN.matcher(canonical);
        if (!pronoun.find()) return null;
        String question = canonical.substring(0, pronoun.start()) + "my " + phrase + canonical.substring(pronoun.end());
        return MyHrOwnRecordIntent.classify(question) == null ? null : question;
    }

    private static String subjectPhrase(MyHrOwnRecordIntent.Intent intent) {
        return switch (intent.subject()) {
            case TIMESHEET -> "timesheet";
            case OFFER_LETTER -> "offer letter request";
            case DOCUMENT_REQUEST -> documentPhrase(intent.documentType());
            case PROFILE_CHANGE -> intent.field() == null ? "profile change request" : fieldPhrase(intent.field()) + " change request";
            case PERSONAL_RECORD -> "profile";
        };
    }

    private static String documentPhrase(RequestedDocumentType type) {
        if (type == null) return "document request";
        return switch (type) {
            case EMPLOYMENT_VERIFICATION -> "employment verification letter request";
            case EXPERIENCE_LETTER -> "experience letter request";
            case EMPLOYMENT_LETTER -> "employment letter request";
            case I983 -> "i-983 request";
            default -> "document request";
        };
    }

    private static String fieldPhrase(ProfileChangeField field) {
        return switch (field) {
            case PHONE_NUMBER -> "phone number";
            case ADDRESS -> "address";
            case PERSONAL_EMAIL -> "personal email";
            case NAME -> "name";
            case DESIGNATION -> "job title";
        };
    }

    private static String template(MyHrOwnRecordIntent.Aspect aspect, String phrase) {
        return switch (aspect) {
            case APPROVED -> "has my " + phrase + " been approved";
            case REJECTED -> "was my " + phrase + " rejected";
            case PENDING -> "is my " + phrase + " still pending";
            case APPROVED_DATE -> "when was my " + phrase + " approved";
            case REQUESTED_DATE -> "when did i submit my " + phrase;
            case REJECTION_REASON -> "why was my " + phrase + " rejected";
            case UPDATED -> "has my " + phrase + " been updated";
            case STATUS -> "what is the status of my " + phrase;
        };
    }

    // --- Wording and spelling ------------------------------------------------------------------------------------

    private static String basicNormalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
                .replaceAll("\\s+", " ").trim().replaceAll("[?.!]+$", "").trim();
    }

    private static String expand(String contraction) {
        return switch (contraction.replace("'", "")) {
            case "whats" -> "what is";
            case "whos" -> "who is";
            case "hows" -> "how is";
            default -> "where is";
        };
    }

    private static String stripWording(String text) {
        String previous;
        do {
            previous = text;
            text = LEADING_FILLER.matcher(text).replaceFirst("");
            text = INSTRUCTION_PREAMBLE.matcher(text).replaceFirst("");
            text = CLAIMED_ROLE.matcher(text).replaceFirst("");
            text = INDIRECT_WRAPPER.matcher(text).replaceFirst("");
            text = TRAILING_FILLER.matcher(text).replaceFirst("").trim();
        } while (!text.equals(previous));
        return text;
    }

    /**
     * The words a message uses, as Waypoint's vocabulary names them: romanized Telugu/Hindi read as English, words
     * run apart or together ("pay roll", "jobtitle") joined or split, near-misspellings corrected. For matching only.
     */
    static String vocabulary(String text) {
        if (text == null) return "";
        return splitCompounds(correctSpelling(splitCompounds(translateRomanized(text))));
    }

    /** Whether the message is romanized Telugu or Hindi Wavi reads ("nuvvu evaru?"). */
    static boolean isRomanized(String message) {
        return message != null && ROMANIZED_MARKER.matcher(basicNormalize(message)).find();
    }

    /**
     * "What is my pay date?", "When do I get paid?", "Next payday?": when the user is paid. Waypoint records no pay
     * dates, so only an approved company policy can answer it.
     */
    private static final Pattern PAY_DATE = Pattern.compile(
            "\\b(?:pay ?dates?|pay ?days?|salary (?:date|day)|paycheck (?:date|day)|payment date"
                    + "|next (?:pay|salary|paycheck|payment)(?: date| day)?"
                    + "|when (?:do|will|would|does|is) (?:i|we|everyone|employees?) (?:get |be |getting )?paid"
                    + "|when (?:is|will|does) (?:my |the |our )?(?:salary|pay|paycheck|wages?) (?:be )?(?:credited|paid"
                    + "|deposited|coming|due|released|transferred))\\b");

    static boolean isPayDateQuestion(String message) {
        String text = canonicalize(message);
        return PAY_DATE.matcher(text).find() && !OTHER_PERSON.matcher(text).find();
    }

    /** Each word close to Waypoint's vocabulary replaced by that word; every other word unchanged. */
    static String correctSpelling(String text) {
        return text == null ? "" : WORD.matcher(text).replaceAll(match -> correctWord(match.group()));
    }

    private static String splitCompounds(String text) {
        for (Rewrite compound : COMPOUNDS) {
            text = compound.pattern().matcher(text).replaceAll(match -> Matcher.quoteReplacement(compound.replacement().apply(match)));
        }
        return text;
    }

    /**
     * Romanized Telugu-English or Hindi-English ("naa manager evaru?", "meri timesheet ka status kya hai") in the
     * English word order Wavi's routes read ("who is my manager", "what is my timesheet status"). Lower-case,
     * punctuation-trimmed input; a message without a Telugu or Hindi marker word is returned unchanged.
     */
    static String translateRomanized(String text) {
        if (text == null || !ROMANIZED_MARKER.matcher(text).find()) return text;
        for (Rewrite phrase : ROMANIZED_PHRASES) {
            Matcher matcher = phrase.pattern().matcher(text);
            if (matcher.matches()) {
                text = phrase.replacement().apply(matcher);
                break;
            }
        }
        for (Rewrite word : ROMANIZED_WORDS) {
            text = word.pattern().matcher(text).replaceAll(match -> Matcher.quoteReplacement(word.replacement().apply(match)));
        }
        text = text.replaceAll("\\s+", " ").strip();
        Matcher trailing = TRAILING_QUESTION_WORD.matcher(text);
        if (trailing.matches()) text = trailing.group(2) + " " + trailing.group(1);
        // "what is my timesheet status" from "my timesheet status what is"; "what is leave policy" keeps its article-less form.
        return text.replaceAll("^what is my ([a-z ]+?) what is$", "what is my $1");
    }

    /**
     * The vocabulary word within one edit (two for words of eight letters or more) that starts with the same
     * letter, or the word unchanged. Vocabulary words, their inflections and listed ordinary words are kept.
     */
    static String correctWord(String word) {
        if (word.length() < 4 || LEXICON.contains(word) || ORDINARY_WORDS.contains(word) || isInflection(word)) {
            return word;
        }
        int allowed = word.length() >= 8 ? 2 : 1;
        String best = word;
        int bestDistance = allowed + 1;
        for (String candidate : LEXICON) {
            if (candidate.charAt(0) != word.charAt(0) || Math.abs(candidate.length() - word.length()) > allowed) continue;
            int distance = editDistance(word, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** A well-formed inflection of a vocabulary word; "approvd" is not one, so it is still corrected. */
    private static boolean isInflection(String word) {
        for (String base : LEXICON) {
            if (base.endsWith("e")) {
                String stem = base.substring(0, base.length() - 1);
                if (word.startsWith(base) && AFTER_E.contains(word.substring(base.length()))) return true;
                if (word.startsWith(stem) && REPLACING_E.contains(word.substring(stem.length()))) return true;
            } else if (word.startsWith(base) && INFLECTIONS.contains(word.substring(base.length()))) {
                return true;
            }
        }
        return false;
    }

    /** Optimal string alignment distance: insertions, deletions, substitutions and adjacent transpositions. */
    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    private static Rewrite rewrite(String regex, Function<MatchResult, String> replacement) {
        return new Rewrite(Pattern.compile(regex), replacement);
    }
}
