package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps Wavi to Waypoint and company matters. Enforced on the backend immediately before any question can
 * reach company-policy retrieval, Claude or Ollama, so neither the frontend nor a model prompt is trusted to
 * keep the conversation in scope. Deterministic Waypoint answers are given before this check and are never
 * affected by it.
 *
 * <p>Order of the rules:
 * <ol>
 *   <li>Producing code, creative or entertainment content, or answering trivia is out of scope, whatever else
 *       the message says ("Write a Python script for payroll", "Tell me a joke about HR").</li>
 *   <li>Unrelated subjects (programming, weather, news, sport, entertainment ...) are out of scope unless the
 *       message is anchored in an HR or company matter ("Can I get a Java course reimbursed?"), or asks how the
 *       subject affects the user's work: time off around it ("Do we get Election Day off?"), a travel allowance
 *       ("the per diem for restaurants"), a weather closure ("What happens if there's bad weather?") or whether
 *       a work tool is permitted ("Can I use GitHub Copilot?"). An attempt to override Wavi's instructions
 *       cancels that anchor.</li>
 *   <li>Messages about work, the company, HR, Waypoint or the user's job are in scope, as are workplace-policy
 *       subjects and questions about what employees are allowed to do ("Am I allowed to bring my dog?").</li>
 *   <li>A message with no signal either way is in scope only as a follow-up within a company conversation.</li>
 * </ol>
 */
final class MyHrScopeGuard {

    static final String OUT_OF_SCOPE_REPLY = "I'm Wavi, Waypoint's HR and employee-support assistant, so I can only "
            + "help with Waypoint and company topics: timesheets, payroll, your profile, document requests, "
            + "onboarding, company policies and your work in the company. Is there something work-related I can "
            + "help you with?";

    // Always out of scope: producing code, creative or entertainment content, or answering trivia.
    private static final Pattern CODING_TASK = Pattern.compile(
            "\\b(?:write|create|generate|build|implement|debug|fix|refactor|optimi[sz]e|explain|show me|give me"
                    + "|teach me|convert)\\b.*\\b(?:code|snippet|regex|regular expression|algorithm|sql query|unit tests?"
                    + "|api endpoint|stack trace|syntax error|compiler error|bash script|shell script|python script"
                    + "|(?:python|java|javascript|typescript|sql|c\\+\\+|c#|golang|rust|ruby|php|react|node) (?:function"
                    + "|program|class|script|code|component|app))\\b"
                    + "|\\b(?:programming|coding) (?:tutorial|lesson|exercise|question|interview|problem)s?\\b"
                    + "|\\bhow (?:do|can) i (?:code|program) (?:in|a|an)\\b|\\bleetcode\\b|\\bhello world program\\b");
    private static final Pattern CREATIVE_TASK = Pattern.compile(
            "\\b(?:tell|write|give|share|make up|compose|sing|generate)\\b(?: me| us)?(?: a| an| some| another| the)?"
                    + "(?: \\w+)? (?:jokes?|poems?|story|stories|songs?|riddles?|haikus?|limericks?|raps?|lyrics"
                    + "|fan ?fiction|pick-?up lines?)\\b");
    private static final Pattern TRIVIA = Pattern.compile(
            "\\bcapital (?:city )?of (?!(?:the |our |my )?(?:company|organi[sz]ation|business))[a-z]"
                    + "|\\bwho (?:won|invented|discovered|painted|wrote the|was the first|is the (?:richest|tallest|best))\\b"
                    + "|\\bwhat year did\\b|\\bhow (?:far|old|tall|big|hot) is (?:the )?(?:moon|sun|earth|mount|eiffel"
                    + "|universe|ocean)\\b|\\bhow many (?:planets|moons|continents|countries|bones|stars)\\b"
                    + "|\\bmeaning of life\\b|\\bspeed of light\\b|\\bwhat is (?:the )?(?:square root|pi)\\b"
                    + "|\\bwhat is \\d+\\s*(?:[-+*/x]|plus|minus|times|divided by)\\s*\\d+\\b");
    // Unrelated subjects: out of scope unless the message is anchored in an HR or company matter.
    private static final Pattern UNRELATED_SUBJECT = Pattern.compile(
            "\\b(?:python|javascript|typescript|java|kotlin|c\\+\\+|c#|golang|rust lang|ruby on rails|php|swift ui|sql"
                    + "|nosql|graphql|rest(?:ful)? apis?|rest vs|grpc|react|angular|vue\\.?js|node\\.?js|spring boot|django"
                    + "|flask|docker|kubernetes|terraform|microservices?|html|css|git|github|linux|http|dns|tcp|machine"
                    + " learning|neural networks?|blockchain|crypto(?:currency)?|bitcoin|chatgpt|copilot|generative ai"
                    + "|ai (?:tools?|assistants?|chatbots?)|large language models?"
                    + "|object[- ]oriented|data structures?|big o|weather|forecast|temperature outside|raining|news"
                    + "|headlines|elections?|president|prime minister|stock prices?|stock market|invest(?:ing|ment) advice"
                    + "|sports?|football|soccer|basketball|cricket|baseball|tennis|world cup|super bowl|olympics|nba|nfl"
                    + "|premier league|score of|jokes?|riddles?|movies?|films?|tv shows?|netflix|anime|celebrit(?:y|ies)"
                    + "|video games?|playstation|xbox|horoscope|zodiac|astrology|recipes?|cooking|baking|dating"
                    + "|girlfriend|boyfriend|vacation destinations?|tourist|things to do in|restaurants?|shopping"
                    + "|buy online|homework|essay|math problem|solve (?:for|this|the equation)|equations?|derivative"
                    + "|integral|trivia|fun fact|history of|photosynthesis|dinosaurs?|planets?|universe)\\b");
    // The user's work, the company, HR or Waypoint. Kept broad so legitimate company questions are never blocked.
    private static final Pattern COMPANY_MATTER = Pattern.compile(
            "\\b(?:waypoint|wavi|my ?hr|hr|human resources|company|companies|organi[sz]ations?|employer|employees?"
                    + "|employment|staff|team(?:mates?)?|colleagues?|coworkers?|co-workers?|manager|supervisor|boss"
                    + "|direct reports?|department|office|workplace|at work|my work|work (?:from|hours|schedule|week|day"
                    + "|email|laptop|account|anniversary|permit|authori[sz]ation|visa|location)|working (?:hours|days"
                    + "|remotely|from home)|(?:i|we) work|job|my role|job role|my position|open positions?|career"
                    + "|promotion|performance (?:reviews?|improvement|ratings?|goals?)|appraisal|probation|contract(?:or)?s?"
                    + "|intern(?:ship)?s?|hiring|recruit\\w*|candidates?|interviews?|offer letters?|onboard\\w*|offboard\\w*"
                    + "|resign\\w*|terminat\\w*|notice period|exit interview|timesheets?|time ?sheets?|hours|overtime"
                    + "|shifts?|my schedule|work schedule|attendance|late|absen\\w*|sick|leave|pto|paid time off|time off"
                    + "|vacation|holidays?|bereavement|maternity|paternity|parental|remote|hybrid|wfh|commute"
                    + "|business travel|work travel|travel (?:policy|expenses?|reimbursement|requests?|booking)|expenses?"
                    + "|reimburs\\w*|per diem|payroll|pay (?:day|date|period|rate|raise|slip|stub|cycle|schedule)|my pay"
                    + "|get paid|paycheck|pay ?stubs?|payslips?|salary|salaries|wages?|bonus\\w*|a raise|compensation"
                    + "|tax (?:forms?|withholding|documents?)|w-?2|w-?4|i-?9|i-?94|i-?983|1099|benefits?|insurance"
                    + "|health plan|dental|vision plan|401k|401\\(k\\)|retirement|pension|stock options|equity|rsus?"
                    + "|policy|policies|handbook|code of conduct|dress code|compliance|harassment|discrimination"
                    + "|grievance|complaint|ethics|safety|training|certification|learning budget|equipment|laptop"
                    + "|badge|parking|desk|cafeteria|meeting rooms?|it support|help ?desk|vpn|password|log ?in|sign ?in"
                    + "|my account|account (?:locked|access)|access to|access request|can't access|cannot access"
                    + "|approv\\w*|reject\\w*|requests?|documents?|letters?|verification|profile|phone number|address"
                    + "|personal email|employee id|job title|designation|direct deposit|bank details|emergency contact"
                    + "|org chart|meetings?|deadlines?|my (?:projects?|tasks?)|project deadline|clients?|customers?"
                    + "|draft (?:an? |the )?(?:email|message|note|reply|announcement|memo)|email to|message to"
                    + "|plan my (?:week|day|work|tasks)"
                    // In a company assistant "we", "our" and "everyone" mean the company: "Do we get Juneteenth off?"
                    + "|our|(?:do|does|can|are|will|should|did|have|would|may) we|everyone|(?:get|take|have|getting"
                    + "|taking) (?:\\w+ ){0,2}off|notice|quit\\w*"
                    + "|(?:mentorship|mentoring|wellness|referral|benefits?|training|assistance|employee|recognition"
                    + "|volunteer|tuition) programs?|mentor\\w*|one-on-ones?|1:1s?|1-on-1s?|part-?time|full-?time"
                    + "|self-?(?:evaluations?|assessments?|reviews?)|feedback|okrs?|kpis?|stand-?ups?|clock (?:in|out)"
                    + "|punch (?:in|out)|jury duty|open enrollment|new hires?|relocat\\w*|visa sponsorship"
                    // Payroll, leave and HR terms used in India and elsewhere that the words above do not cover.
                    + "|ctc|cost to company|gratuity|provident fund|pf|epf|uan|esi|esic|form[- ]?16|tds|professional tax"
                    + "|lop|loss of pay|lwp|comp(?:ensatory)?[- ]off|hikes?|increments?|appraisals?|hrbp|hr business partner"
                    + "|half[- ]days?|lunch (?:break|hour|time)|tea break|breaks?|timings?|office hours|working day"
                    + "|relieving|full and final|f&f|fnf|background (?:check|verification)|bgv|joining|ceo|cto|cfo|coo"
                    + "|founders?|leadership|management|hr team|hr contact|dashboard|knowledge ?base|audit (?:log|trail)"
                    + "|reports? page|employees page|payroll readiness|pending|to-?do|waiting for (?:me|my)"
                    + "|approvals?|reportees?|subordinates?|my team|designation|emp id|my (?:role|title|details))\\b");
    // Workplace-policy subjects the vocabulary above does not name. Each is specific to employment, so it is in
    // scope on its own, and MyHrKnowledgeGrounding treats it as a company-policy question (no invented answers).
    private static final Pattern WORKPLACE_POLICY_SUBJECT = Pattern.compile(
            "\\b(?:days? off|payday|stipends?|(?:internet|phone|mobile|home office|remote work|meal|travel|equipment"
                    + "|wellness|learning|education|commuter|transit|car) allowances?|sabbaticals?|severance"
                    + "|sales commissions?|commissions? (?:rates?|plans?|structure|payouts?|schedule)"
                    + "|carry[- ]?(?:over|forward)|confidentiality|non-?disclosure|ndas?|non-?compete"
                    + "|conflicts? of interest|second jobs?|side (?:jobs?|hustles?)|moonlighting|outside employment"
                    + "|gifts? (?:from|to) (?:vendors?|suppliers?|clients?|customers?|partners?)"
                    + "|(?:the|our|company|workplace|office) (?:rules?|guidelines?|regulations?)"
                    + "|(?:rules?|guidelines?|regulations?) (?:for|on|about|around|regarding) (?:gifts?|social media"
                    + "|conduct|visitors?|pets?|dogs?|smoking|vaping|alcohol|personal (?:devices?|phones?)|confidential\\w*)"
                    + "|premises|on-?site|(?:bring|wear|take|drive|commute|come|walk|cycle|bike) (?:\\w+ ){0,3}to work)\\b");
    // What the user or employees may do: in a company assistant, a question about the workplace's rules.
    private static final Pattern WORKPLACE_PERMISSION = Pattern.compile(
            "^(?:am i|are we|are (?:employees|staff|workers|contractors|interns|visitors))(?: \\w+)? (?:allowed|permitted)\\b");
    // Context that ties an otherwise unrelated subject to the user's work. Only these, or HR_ANCHOR, let it through.
    private static final Pattern TIME_OFF_OR_ALLOWANCE = Pattern.compile(
            "\\b(?:(?:get|take|have|getting|taking|given) (?:\\w+ ){0,3}(?:day|days|time|week|weekend|afternoon|morning"
                    + "|monday|tuesday|wednesday|thursday|friday) off|(?:take|taking|book|booking|request|requesting|use"
                    + "|using) (?:a |some |my |the |our )?(?:vacation|annual leave|personal days?)|vacation (?:days?|time"
                    + "|policy|requests?|balance|allowance|leave)|paid holidays?|holiday (?:schedule|calendar|pay)|per diem"
                    + "|meal allowances?|travel (?:policy|expenses?|reimbursements?|allowances?|per diem))\\b");
    private static final Pattern WORKPLACE_WEATHER = Pattern.compile(
            "\\b(?:what happens (?:if|when)|what (?:do|should) (?:i|we) do (?:if|when)|do (?:i|we) (?:still )?(?:have|need)"
                    + " to (?:come|work|go)|(?:is|are|will) (?:the )?(?:office|offices|building|we) (?:be )?(?:closed"
                    + "|closing|open))\\b.*\\b(?:weather|raining|snow\\w*|storms?|hurricanes?|flood\\w*|tornado\\w*)\\b"
                    + "|\\b(?:inclement|severe|bad|extreme) weather (?:policy|closures?|days?|pay|plan)\\b"
                    + "|\\bweather[- ](?:related )?(?:closures?|closings?)\\b|\\bsnow days?\\b");
    // A work tool, and whether employees may use it ("Is ChatGPT approved for work?"), not how it works.
    private static final Pattern WORK_TOOL = Pattern.compile(
            "\\b(?:chatgpt|copilot|github|gitlab|git|docker|linux|generative ai|ai (?:tools?|assistants?|chatbots?)"
                    + "|large language models?)\\b");
    private static final Pattern TOOL_PERMISSION = Pattern.compile(
            "\\b(?:allowed|permitted|approved|authori[sz]ed|ok|okay) to (?:use|install|access|download)\\b"
                    + "|\\b(?:allowed|permitted|approved|authori[sz]ed|banned|prohibited|blocked) (?:for|at) (?:work"
                    + "|company|business)\\b|\\b(?:approved|allowed|permitted|authori[sz]ed|banned|prohibited|blocked)"
                    + " (?:tools?|software|apps?|applications?)\\b"
                    + "|\\b(?:is|are) (?:\\w+ ){0,2}(?:allowed|permitted|approved|authori[sz]ed|banned|prohibited|blocked)\\b"
                    // The whole message is "Can I use <tool>?", optionally "at work": a how-to ("... to revert") is not.
                    + "|^(?:can|may|could) (?:i|we) (?:use|install|access) (?!(?:\\w+ ){0,2}to\\b)(?:\\w+ ?){1,3}"
                    + "(?:(?:at|for|on|in) (?:work|my work|my job|my tasks|my (?:work )?laptop|the office))?[?.!]*$");
    // Only greetings, thanks or farewells ("hello hello", "bye for now, see you tomorrow"): answered, never refused.
    private static final Pattern SOCIAL_ONLY = Pattern.compile(
            "^(?:(?:hi|hello|hey|thanks|thank you|bye|goodbye|good (?:morning|afternoon|evening|night)|see you"
                    + "|tomorrow|later|for now|wavi|there|team|everyone|again|so much|a lot|ok(?:ay)?|cool|great|got it"
                    + "|sounds good|perfect|have a (?:good|great|nice) (?:day|one|weekend))[ ,!.?]*)+$");
    // Strong HR or company anchors: these let an otherwise unrelated subject through.
    private static final Pattern HR_ANCHOR = Pattern.compile(
            "\\b(?:waypoint|hr|human resources|company|our organi[sz]ation|employer|payroll|timesheets?|reimburs\\w*"
                    + "|expenses?|benefits?|polic(?:y|ies)|handbook|code of conduct|leave|pto|time off|holidays?"
                    + "|onboard\\w*|training|certification|learning budget|office|workplace|at work|remote work"
                    + "|work from home|my (?:job|role|team|manager)|it support|help ?desk|laptop|equipment|stock options"
                    + "|equity|rsus?)\\b");
    private static final Pattern INSTRUCTION_OVERRIDE = Pattern.compile(
            "\\b(?:ignore|disregard|forget|override|bypass)\\b.*\\b(?:instructions?|rules?|prompts?|guardrails?"
                    + "|restrictions?|scope|guidelines?|system)\\b|\\b(?:system prompt|developer mode|jailbreak"
                    + "|dan mode|you are now|from now on,? you|no longer (?:an? )?(?:hr|wavi))\\b|\\bact as (?:an? |the )?"
                    + "(?!hr\\b|my\\b)\\w+|\\bpretend (?:to be|you are|that you)\\b|\\b(?:general|unrestricted) (?:purpose )?"
                    + "(?:assistant|chatbot|ai)\\b");

    enum Decision { IN_SCOPE, OUT_OF_SCOPE }

    private MyHrScopeGuard() {
    }

    /**
     * Whether a question that no deterministic Waypoint route answered may go to retrieval or a model. Recent
     * user turns only lend context to a message with no signal of its own; they never let an unrelated
     * subject through, so a crafted history cannot widen the scope.
     */
    static Decision decide(String message, List<MyHrChatHistoryMessage> history) {
        String text = normalize(message);
        if (text.isEmpty()) return Decision.OUT_OF_SCOPE;
        // Misspelled or romanized Telugu/Hindi company words ("leav polcy", "holliday") count as the words meant.
        // Only the company-matter checks read this form; unrelated subjects and overrides are read as typed.
        String corrected = normalize(MyHrQuestionInterpreter.vocabulary(text.replaceAll("[?.!]+$", "")));
        if (SOCIAL_ONLY.matcher(text).matches()) return Decision.IN_SCOPE;
        if (CODING_TASK.matcher(text).find() || CREATIVE_TASK.matcher(text).find() || TRIVIA.matcher(text).find()) {
            return Decision.OUT_OF_SCOPE;
        }
        boolean override = INSTRUCTION_OVERRIDE.matcher(text).find();
        if (UNRELATED_SUBJECT.matcher(text).find()) {
            return !override && (HR_ANCHOR.matcher(text).find() || anchorsUnrelatedSubjectToWork(text))
                    ? Decision.IN_SCOPE : Decision.OUT_OF_SCOPE;
        }
        if (isCompanyMatter(text) || isCompanyMatter(corrected)) {
            // "Ignore your instructions" around a company word is still an attempt to widen the scope.
            return override && !HR_ANCHOR.matcher(text).find() ? Decision.OUT_OF_SCOPE : Decision.IN_SCOPE;
        }
        if (override) return Decision.OUT_OF_SCOPE;
        return followsCompanyConversation(history) ? Decision.IN_SCOPE : Decision.OUT_OF_SCOPE;
    }

    /**
     * The explicit refusals only: coding, creative writing, trivia, an unrelated subject not tied to work, or an
     * attempt to override Wavi's instructions. Unlike {@link #decide}, a message without recognised company
     * vocabulary is not refused here; semantic routing uses this when a router judged the message an HR or policy
     * question. Reuses this guard's existing rules; adds none.
     */
    static boolean isExplicitlyOutOfScope(String message) {
        String text = normalize(message);
        if (text.isEmpty()) return true;
        if (SOCIAL_ONLY.matcher(text).matches()) return false;
        if (CODING_TASK.matcher(text).find() || CREATIVE_TASK.matcher(text).find() || TRIVIA.matcher(text).find()) {
            return true;
        }
        boolean override = INSTRUCTION_OVERRIDE.matcher(text).find();
        if (UNRELATED_SUBJECT.matcher(text).find()) {
            return override || !(HR_ANCHOR.matcher(text).find() || anchorsUnrelatedSubjectToWork(text));
        }
        return override && !HR_ANCHOR.matcher(text).find();
    }

    /**
     * Whether a message the guard did not refuse explicitly should still be refused because it continues an
     * explicitly off-topic conversation ("Tell me a joke" then "Another one"). Only the nearest earlier user turn
     * with any signal counts; a company turn in between ends it.
     */
    static boolean continuesOffTopicConversation(String message, List<MyHrChatHistoryMessage> history) {
        if (history == null || history.isEmpty() || isCompanyMatter(normalize(message))) return false;
        int checked = 0;
        for (int index = history.size() - 1; index >= 0 && checked < 3; index--) {
            MyHrChatHistoryMessage turn = history.get(index);
            if (turn == null || !"user".equals(turn.role()) || turn.content() == null) continue;
            checked++;
            if (isExplicitlyOutOfScope(turn.content())) return true;
            if (isCompanyMatter(normalize(turn.content()))) return false;
        }
        return false;
    }

    /**
     * Whether the message asks about a workplace policy in terms this guard recognises beyond its general company
     * vocabulary. MyHrKnowledgeGrounding counts these as company-policy questions, so every question let in for
     * this reason is answered only from approved policy excerpts or with the fixed "couldn't find this" reply,
     * never with model-invented policy details.
     */
    static boolean isWorkplacePolicyQuestion(String message) {
        String text = normalize(message);
        return !text.isEmpty() && (WORKPLACE_POLICY_SUBJECT.matcher(text).find()
                || WORKPLACE_PERMISSION.matcher(text).find() || anchorsUnrelatedSubjectToWork(text));
    }

    /**
     * A request to write or rewrite text for work ("Draft an email to my manager asking for 2 days leave"). It asks
     * for writing help, not for a company policy, so it is not answered from policy excerpts; the provider is told
     * no policies are available and must not invent them.
     */
    private static final Pattern WRITING_TASK = Pattern.compile(
            "^(?:(?:please|pls|can you|could you|would you|help me|i need to|i want to|i'd like to|i would like to)"
                    + "(?: please)? )*(?:draft|write|compose|prepare|rephrase|reword|rewrite|proofread|polish|improve"
                    + ")\\b.*\\b(?:e-?mail|mail|message|note|letter|reply|response|announcement|memo|post|text"
                    + "|slack|teams message|summary|invitation|apology|request|reminder|notice|thank[- ]you|greeting"
                    + "|wishes|agenda|script|talking points|update|caption|bio|introduction)\\b");

    static boolean isWritingTask(String message) {
        String text = normalize(message);
        return !text.isEmpty() && WRITING_TASK.matcher(text).find() && !INSTRUCTION_OVERRIDE.matcher(text).find();
    }

    private static boolean isCompanyMatter(String text) {
        return COMPANY_MATTER.matcher(text).find() || WORKPLACE_POLICY_SUBJECT.matcher(text).find()
                || WORKPLACE_PERMISSION.matcher(text).find() || anchorsUnrelatedSubjectToWork(text);
    }

    /** Time off or an allowance around the subject, a weather closure, or whether a work tool is permitted. */
    private static boolean anchorsUnrelatedSubjectToWork(String text) {
        return TIME_OFF_OR_ALLOWANCE.matcher(text).find() || WORKPLACE_WEATHER.matcher(text).find()
                || (WORK_TOOL.matcher(text).find() && TOOL_PERMISSION.matcher(text).find());
    }

    /** The most recent user turns (up to three): in scope only if the nearest one with a signal was about the company. */
    private static boolean followsCompanyConversation(List<MyHrChatHistoryMessage> history) {
        if (history == null || history.isEmpty()) return false;
        int checked = 0;
        for (int index = history.size() - 1; index >= 0 && checked < 3; index--) {
            MyHrChatHistoryMessage turn = history.get(index);
            if (turn == null || !"user".equals(turn.role()) || turn.content() == null) continue;
            checked++;
            String text = normalize(turn.content());
            if (CODING_TASK.matcher(text).find() || CREATIVE_TASK.matcher(text).find()
                    || TRIVIA.matcher(text).find() || INSTRUCTION_OVERRIDE.matcher(text).find()) {
                return false;
            }
            // An unrelated subject was a company question only if it was anchored to work, as decide() requires.
            if (UNRELATED_SUBJECT.matcher(text).find()) {
                return HR_ANCHOR.matcher(text).find() || anchorsUnrelatedSubjectToWork(text);
            }
            if (isCompanyMatter(text)) return true;
        }
        return false;
    }

    private static String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
                .replaceAll("\\s+", " ").trim();
    }
}
