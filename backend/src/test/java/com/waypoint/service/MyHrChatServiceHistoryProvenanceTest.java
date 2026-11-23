package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import com.waypoint.service.MyHrChatHistoryProvenance.ExternalUse;
import com.waypoint.service.MyHrChatHistoryProvenance.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conversation history is client input: the browser returns earlier messages with every request. Only history whose
 * server-issued stamp verifies for the current actor and login session, and which the server classified as
 * permitted for external use, may reach Claude (or an Ollama that is not local). Every turn below is produced by the
 * service itself and returned exactly as the browser would. Providers, Voyage and MongoDB are mocks; no network call.
 */
class MyHrChatServiceHistoryProvenanceTest {

    private static final String ORG = "org-history-51";
    private static final String OTHER_ORG = "org-history-52";
    private static final String GENERAL = "Write a polite email to my team";
    private static final String HR_POLICY_QUESTION = "How many extra carry-forward days can HR approve?";
    private static final String HR_TEXT = "HR-only: HR may approve up to 8 extra carry-forward days.";
    private static final String HR_ANSWER = "HR may approve up to 8 extra carry-forward days [S1].";
    private static final String COMPANY_QUESTION = "How many unused vacation days can employees carry forward?";
    private static final String COMPANY_TEXT = "Employees may carry forward up to 5 unused vacation days.";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-51", ORG, UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-51", ORG);
    private final Session session = new Session("1".repeat(64));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T09:00:00Z"));
    private final MyHrChatHistoryProvenance provenance = new MyHrChatHistoryProvenance(Duration.ofHours(3), clock);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        when(claude.chat(anyString(), anyList())).thenReturn("Claude reply.");
        when(claude.chat(any(MyHrGroundedPrompt.class))).thenReturn("Claude reply.");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    // --- HR-only policy answer followed by a general Claude question ---------------------------------------

    @Test
    void anHrOnlyPolicyAnswerIsNeverSentToClaudeAsHistoryForALaterGeneralQuestion() {
        MyHrChatService service = groundedService(ExternalPolicyProcessingAuthorization.DENY_ALL);
        when(retrieval.retrieve(eq(hr), anyString())).thenAnswer(invocation ->
                invocation.getArgument(1, String.class).contains("carry-forward days can HR")
                        ? retrieved(chunk(ChunkAccessLevel.HR_ONLY, HR_TEXT)) : noMatch());
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(HR_ANSWER);

        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatResponse hrAnswer = ask(service, hr, HR_POLICY_QUESTION, history);
        assertThat(hrAnswer.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(hrAnswer.historyProvenance().assistant()).startsWith("v1.R.");

        ask(service, hr, GENERAL, history);

        ArgumentCaptor<MyHrGroundedPrompt> toClaude = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(claude).chat(toClaude.capture());
        assertThat(toClaude.getValue().history()).isEmpty();
        assertThat(toClaude.getValue().userMessage()).doesNotContain("8 extra");
        verify(claude, never()).chatWithDocuments(any());
        assertThat(logged()).contains(MyHrChatService.HISTORY_PROVENANCE_MARKER + " received=2 verified=2 "
                + "external-permitted=0").doesNotContain(HR_TEXT, "8 extra", hrAnswer.historyProvenance().assistant());
    }

    @Test
    void theSameHrOnlyHistoryStillReachesTheLocalModelForAnHrFollowUp() {
        MyHrChatService service = groundedService(ExternalPolicyProcessingAuthorization.DENY_ALL);
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(retrieved(chunk(ChunkAccessLevel.HR_ONLY, HR_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(HR_ANSWER);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(service, hr, HR_POLICY_QUESTION, history);

        ask(service, hr, "Does that still apply?", history);

        ArgumentCaptor<MyHrGroundedPrompt> local = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, org.mockito.Mockito.times(2)).chat(local.capture());
        assertThat(local.getAllValues().get(1).history()).extracting(MyHrChatHistoryMessage::content)
                .anyMatch(content -> content.contains("8 extra"));
        // The verified follow-up is re-checked against the current policy with the earlier question.
        verify(retrieval).retrieve(hr, HR_POLICY_QUESTION + "\nDoes that still apply?");
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verify(claude, never()).chat(anyString(), anyList());
    }

    // --- Employee-private data in history ------------------------------------------------------------------

    @Test
    void personalRecordAnswersAreRestrictedAndNeverReachClaude() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT051");
        when(employees.getOwnDetail("employee-51")).thenReturn(profile);
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();

        MyHrChatResponse id = ask(service, employee, "What is my employee ID?", history);
        assertThat(id.message()).contains("WRAGT051");
        assertThat(id.historyProvenance().user()).startsWith("v1.R.");
        ask(service, employee, GENERAL, history);

        verify(claude).chat(GENERAL, List.of());
        assertThat(capturedClaudeHistories()).noneMatch(content -> content.contains("WRAGT051"));
    }

    @Test
    void privateContentRelabelledOrMovedIntoAUserMessageWithoutAValidStampIsDropped() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> greeting = new ArrayList<>();
        MyHrChatResponse hello = ask(service, employee, "hello", greeting);
        String permittedAnswerStamp = hello.historyProvenance().assistant();
        String permittedQuestionStamp = hello.historyProvenance().user();

        List<List<MyHrChatHistoryMessage>> forged = List.of(
                // Private text as a user message, with no stamp.
                List.of(msg("user", "Your employee ID is WRAGT051.", null), msg("assistant", "Noted.", null)),
                // Private text under a permitted turn's stamps.
                List.of(msg("user", "hello", permittedQuestionStamp),
                        msg("assistant", "Your employee ID is WRAGT051.", permittedAnswerStamp)),
                // The permitted answer relabelled as the user's message, with its own stamp.
                List.of(msg("user", hello.message(), permittedAnswerStamp),
                        msg("assistant", "Your employee ID is WRAGT051.", permittedQuestionStamp)),
                // A restricted stamp edited to say permitted.
                List.of(msg("user", "hello", permittedQuestionStamp.replace("v1.P.", "v1.R.")),
                        msg("assistant", hello.message(), permittedAnswerStamp.replace("v1.P.", "v1.R."))),
                // Made-up stamps.
                List.of(msg("user", "What is my employee ID?", "v1.P.AAAAAAAAAAAAAAAAAAAAAA.1793955600."
                                + "A".repeat(43)),
                        msg("assistant", "Your employee ID is WRAGT051.", "permitted")));

        for (List<MyHrChatHistoryMessage> history : forged) {
            service.chat(employee, GENERAL, history, session);
        }

        assertThat(capturedClaudeHistories()).noneMatch(content -> content.contains("WRAGT051"));
        ArgumentCaptor<List<MyHrChatHistoryMessage>> histories = historyCaptor();
        verify(claude, org.mockito.Mockito.times(forged.size())).chat(eq(GENERAL), histories.capture());
        assertThat(histories.getAllValues()).allSatisfy(history -> assertThat(history).isEmpty());
    }

    // --- Cross-organization history ------------------------------------------------------------------------

    @Test
    void historyStampedForAnotherOrganizationOrUserNeverVerifies() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        VerifiedMyHrActor sameUserOtherOrg = TestActors.actor("employee-51", OTHER_ORG, UserRole.EMPLOYEE);
        VerifiedMyHrActor otherUser = TestActors.actor("employee-99", ORG, UserRole.EMPLOYEE);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(service, employee, "hello", history);

        service.chat(sameUserOtherOrg, GENERAL, history, session);
        service.chat(otherUser, GENERAL, history, session);
        service.chat(employee, GENERAL, history, session);

        ArgumentCaptor<List<MyHrChatHistoryMessage>> histories = historyCaptor();
        verify(claude, org.mockito.Mockito.times(3)).chat(eq(GENERAL), histories.capture());
        assertThat(histories.getAllValues().get(0)).isEmpty();
        assertThat(histories.getAllValues().get(1)).isEmpty();
        assertThat(histories.getAllValues().get(2)).hasSize(2);
    }

    // --- Replayed or modified history, session expiry and role changes ------------------------------------

    @Test
    void editedSplicedReplayedOrExpiredHistoryNeverVerifies() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> first = new ArrayList<>();
        ask(service, employee, "hello", first);
        List<MyHrChatHistoryMessage> second = new ArrayList<>();
        ask(service, employee, "thanks", second);

        List<List<MyHrChatHistoryMessage>> invalid = List.of(
                // One character of the answer changed.
                List.of(first.get(0), msg("assistant", first.get(1).content() + "!", first.get(1).provenance())),
                // A question and an answer from different turns.
                List.of(first.get(0), second.get(1)),
                // Roles swapped.
                List.of(msg("user", first.get(1).content(), first.get(1).provenance()),
                        msg("assistant", first.get(0).content(), first.get(0).provenance())),
                // An answer on its own.
                List.of(first.get(1)));
        for (List<MyHrChatHistoryMessage> history : invalid) {
            service.chat(employee, GENERAL, history, session);
        }
        // A new login (another bearer token) and the legacy path without a session.
        service.chat(employee, GENERAL, first, new Session("2".repeat(64)));
        service.chat(employee, GENERAL, first);
        // Stamps never outlive the session lifetime, and a future-dated one is not accepted either.
        clock.advance(Duration.ofHours(3).plusSeconds(1));
        service.chat(employee, GENERAL, first, session);

        ArgumentCaptor<List<MyHrChatHistoryMessage>> histories = historyCaptor();
        verify(claude, org.mockito.Mockito.times(invalid.size() + 3)).chat(eq(GENERAL), histories.capture());
        assertThat(histories.getAllValues()).allSatisfy(history -> assertThat(history).isEmpty());

        // Replaying the same valid turn within the session only repeats content already permitted for Claude.
        List<MyHrChatHistoryMessage> fresh = new ArrayList<>();
        ask(service, employee, "hello", fresh);
        org.mockito.Mockito.clearInvocations(claude);
        service.chat(employee, GENERAL, List.of(fresh.get(0), fresh.get(1), fresh.get(0), fresh.get(1)), session);
        verify(claude).chat(GENERAL, StampedHistory.unstamped(List.of(fresh.get(0), fresh.get(1), fresh.get(0),
                fresh.get(1))));
    }

    @Test
    void aRoleChangeInvalidatesEarlierHistory() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(service, hr, "hello", history);
        VerifiedMyHrActor demoted = TestActors.actor("hr-51", ORG, UserRole.EMPLOYEE);

        service.chat(demoted, GENERAL, history, session);
        service.chat(hr, GENERAL, history, session);

        ArgumentCaptor<List<MyHrChatHistoryMessage>> histories = historyCaptor();
        verify(claude, org.mockito.Mockito.times(2)).chat(eq(GENERAL), histories.capture());
        assertThat(histories.getAllValues().get(0)).isEmpty();
        assertThat(histories.getAllValues().get(1)).hasSize(2);
    }

    // --- Authorized company-wide content ------------------------------------------------------------------

    @Test
    void anAuthorizedCompanyWideAnswerKeepsItsContinuityWithClaudeButALocalOneDoesNot() {
        when(retrieval.retrieve(eq(employee), anyString())).thenAnswer(invocation ->
                invocation.getArgument(1, String.class).contains("carry forward")
                        ? retrieved(chunk(ChunkAccessLevel.COMPANY_WIDE, COMPANY_TEXT)) : noMatch());
        when(claude.chatWithDocuments(any())).thenReturn(List.of(new MyHrCitedSegment("Up to 5 days.", List.of(
                new MyHrCitedSegment.Citation("char_location", COMPANY_TEXT, 0, 0, COMPANY_TEXT.length())))));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Up to 5 days [S1].");

        // Authorized for external processing: answered by Claude, stamped permitted, kept as Claude history.
        MyHrChatService approved = groundedService((organizationId, chunks) -> ORG.equals(organizationId));
        List<MyHrChatHistoryMessage> approvedHistory = new ArrayList<>();
        MyHrChatResponse cited = ask(approved, employee, COMPANY_QUESTION, approvedHistory);
        assertThat(cited.historyProvenance().assistant()).startsWith("v1.P.");
        assertThat(cited.message()).isEqualTo("“" + COMPANY_TEXT + "” [1]");
        ask(approved, employee, GENERAL, approvedHistory);

        // Not authorized: answered locally, stamped restricted, never Claude history.
        MyHrChatService unapproved = groundedService(ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> localHistory = new ArrayList<>();
        MyHrChatResponse local = ask(unapproved, employee, COMPANY_QUESTION, localHistory);
        assertThat(local.historyProvenance().assistant()).startsWith("v1.R.");
        ask(unapproved, employee, GENERAL, localHistory);

        ArgumentCaptor<MyHrGroundedPrompt> toClaude = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(claude, org.mockito.Mockito.times(2)).chat(toClaude.capture());
        assertThat(toClaude.getAllValues().get(0).history()).extracting(MyHrChatHistoryMessage::content)
                .containsExactly(COMPANY_QUESTION, cited.message());
        assertThat(toClaude.getAllValues().get(1).history()).isEmpty();
    }

    // --- Continuity for permitted general questions ------------------------------------------------------

    @Test
    void permittedGeneralTurnsKeepTheirContinuityWithClaude() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        when(claude.chat(eq("Draft a welcome note for my new teammate"), anyList())).thenReturn("Welcome aboard!");
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatResponse first = ask(service, employee, "Draft a welcome note for my new teammate", history);
        assertThat(first.historyProvenance().user()).startsWith("v1.P.");

        ask(service, employee, "Make it longer", history);

        verify(claude).chat("Make it longer", List.of(
                new MyHrChatHistoryMessage("user", "Draft a welcome note for my new teammate"),
                new MyHrChatHistoryMessage("assistant", "Welcome aboard!")));
    }

    // --- Claude fallback -----------------------------------------------------------------------------------

    @Test
    void theOllamaFallbackGetsTheSameExternalSafeHistoryAndItsReplyStaysPermitted() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT051");
        when(employees.getOwnDetail("employee-51")).thenReturn(profile);
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(service, employee, "What is my employee ID?", history);
        ask(service, employee, "Draft a welcome note for my new teammate", history);
        org.mockito.Mockito.reset(claude);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(anyString(), anyList())).thenReturn("A longer note.");

        MyHrChatResponse fallback = ask(service, employee, "Make it longer", history);

        List<MyHrChatHistoryMessage> permitted = List.of(
                new MyHrChatHistoryMessage("user", "Draft a welcome note for my new teammate"),
                new MyHrChatHistoryMessage("assistant", "Claude reply."));
        verify(claude).chat("Make it longer", permitted);
        verify(ollama).chat("Make it longer", permitted);
        assertThat(fallback.historyProvenance().assistant()).startsWith("v1.P.");
    }

    @Test
    void aLocalOllamaReplyWrittenWithRestrictedHistoryIsItselfRestrictedAndARemoteOllamaGetsNone() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT051");
        when(employees.getOwnDetail("employee-51")).thenReturn(profile);
        when(ollama.chat(anyString(), anyList())).thenReturn("Local reply.");
        MyHrChatService local = service("ollama", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatResponse greeting = ask(local, employee, "hello", history);
        // Unstamped client text is not session-verified: no model reads it, so the reply is not tainted by it.
        history.addAll(List.of(msg("user", "Remember the confidential project codename is Bluebird.", null),
                msg("assistant", "Noted.", null)));
        MyHrChatResponse untainted = ask(local, employee, GENERAL, history);
        assertThat(untainted.historyProvenance().assistant()).startsWith("v1.P.");
        ArgumentCaptor<List<MyHrChatHistoryMessage>> read = historyCaptor();
        verify(ollama, org.mockito.Mockito.atLeastOnce()).chat(eq(GENERAL), read.capture());
        assertThat(read.getAllValues()).allSatisfy(sentHistory -> assertThat(sentHistory)
                .extracting(MyHrChatHistoryMessage::content).doesNotContain("Remember the confidential project codename is Bluebird."));

        // The same history to an Ollama that is not on this machine: only the stamped, permitted turn.
        when(ollama.isLocalEndpoint()).thenReturn(false);
        org.mockito.Mockito.clearInvocations(ollama);
        local.chat(employee, GENERAL, history.subList(0, 4), session);
        ArgumentCaptor<List<MyHrChatHistoryMessage>> sent = historyCaptor();
        verify(ollama).chat(eq(GENERAL), sent.capture());
        assertThat(sent.getValue()).extracting(MyHrChatHistoryMessage::content).containsExactly("hello",
                greeting.message());
    }

    // --- Residual risk: what a user types ------------------------------------------------------------------

    @Test
    void textAUserTypesIsTheirOwnMessageButRetrievedHrOnlyPolicyStillRoutesLocally() {
        // Residual risk: the current message is what the user chose to send. Typed text that is not a policy question
        // and matches no approved excerpt goes to Claude; stamps cannot stop a user disclosing what they type.
        String typed = GENERAL + " saying the confidential Q3 reorganisation moves 14 people to Austin.";
        MyHrChatService general = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        general.chat(hr, typed, List.of(), session);
        verify(claude).chat(typed, List.of());

        // Pasted restricted policy text that retrieves the HR-only excerpt it came from stays on the local model.
        MyHrChatService service = groundedService(ExternalPolicyProcessingAuthorization.DENY_ALL);
        org.mockito.Mockito.clearInvocations(claude);
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(retrieved(chunk(ChunkAccessLevel.HR_ONLY, HR_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(HR_ANSWER);
        service.chat(hr, "Rewrite this more politely: " + HR_TEXT, List.of(), session);
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verify(claude, never()).chatWithDocuments(any());
        verify(ollama).chat(any(MyHrGroundedPrompt.class));
    }

    // --- Stamps themselves ---------------------------------------------------------------------------------

    @Test
    void stampsAreIssuedOnlyWithASessionAndNeverReachAProvider() {
        MyHrChatService service = service("claude", false, ExternalPolicyProcessingAuthorization.DENY_ALL);
        assertThat(service.chat(employee, GENERAL, List.of()).historyProvenance()).isNull();

        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(service, employee, "Draft a welcome note for my new teammate", history);
        ask(service, employee, "Make it longer", history);

        ArgumentCaptor<List<MyHrChatHistoryMessage>> sent = historyCaptor();
        verify(claude, org.mockito.Mockito.atLeastOnce()).chat(anyString(), sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(list -> assertThat(list)
                .allSatisfy(message -> assertThat(message.provenance()).isNull()));
        assertThat(history.get(0).toString()).doesNotContain("welcome note");
    }

    @Test
    void theProvenanceRejectsMalformedInputWithoutThrowing() {
        for (String stamp : new String[]{"", "v1", "v1.X.AAAAAAAAAAAAAAAAAAAAAA.1.AAAA", "v2.P.a.b.c",
                "v1.P.AAAAAAAAAAAAAAAAAAAAAA.99999999999999.AAAA", "x".repeat(500)}) {
            assertThat(provenance.verify(employee, session, msg("user", "hello", stamp))).as(stamp).isEmpty();
        }
        assertThat(provenance.verify(employee, null, msg("user", "hello", "v1"))).isEmpty();
        assertThat(provenance.verify(null, session, msg("user", "hello", "v1"))).isEmpty();
        assertThat(Session.fromAuthorizationHeader(null)).isNull();
        assertThat(Session.fromAuthorizationHeader("Basic abc")).isNull();
        assertThat(Session.fromAuthorizationHeader("Bearer abc"))
                .isEqualTo(Session.fromAuthorizationHeader("Bearer abc"))
                .isNotEqualTo(Session.fromAuthorizationHeader("Bearer abd"));
        // A second process (another key) cannot verify this one's stamps.
        String turn = provenance.newTurnId();
        String stamp = provenance.stamp(employee, session, turn, "user", "hello", ExternalUse.PERMITTED);
        assertThat(provenance.verify(employee, session, msg("user", "hello", stamp))).isPresent();
        assertThat(new MyHrChatHistoryProvenance(Duration.ofHours(3), clock)
                .verify(employee, session, msg("user", "hello", stamp))).isEmpty();
        // Instances configured with the same shared key verify each other's stamps (no session affinity needed);
        // the stamp stays bound to the actor and session, and a different shared key still rejects it.
        byte[] shared = new byte[32];
        new java.security.SecureRandom().nextBytes(shared);
        String sharedBase64 = java.util.Base64.getEncoder().encodeToString(shared);
        MyHrChatHistoryProvenance instanceA = new MyHrChatHistoryProvenance(Duration.ofHours(3), clock,
                MyHrChatHistoryProvenance.sharedKey(sharedBase64));
        MyHrChatHistoryProvenance instanceB = new MyHrChatHistoryProvenance(Duration.ofHours(3), clock,
                MyHrChatHistoryProvenance.sharedKey(sharedBase64));
        String crossTurn = instanceA.newTurnId();
        String crossStamp = instanceA.stamp(employee, session, crossTurn, "user", "hello", ExternalUse.PERMITTED);
        assertThat(instanceB.verify(employee, session, msg("user", "hello", crossStamp))).isPresent();
        assertThat(instanceB.verify(employee, session, msg("user", "hello!", crossStamp))).isEmpty();
        assertThat(instanceB.verify(employee, Session.fromAuthorizationHeader("Bearer other"),
                msg("user", "hello", crossStamp))).isEmpty();
        assertThat(provenance.verify(employee, session, msg("user", "hello", crossStamp))).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MyHrChatHistoryProvenance.sharedKey("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MyHrChatHistoryProvenance.sharedKey("not base64!"))
                .isInstanceOf(IllegalStateException.class);
        // A stamp dated more than a minute in the future (a clock that moved backwards) is not accepted.
        clock.advance(Duration.ofMinutes(-2));
        assertThat(provenance.verify(employee, session, msg("user", "hello", stamp))).isEmpty();
    }

    // --- Helpers -------------------------------------------------------------------------------------------

    /** Sends one turn as the browser does and appends it, with the returned stamps, to the history. */
    private MyHrChatResponse ask(MyHrChatService service, VerifiedMyHrActor actor, String question,
                                 List<MyHrChatHistoryMessage> history) {
        MyHrChatResponse response = service.chat(actor, question, List.copyOf(history), session);
        assertThat(response.historyProvenance()).as(question).isNotNull();
        history.add(msg("user", question, response.historyProvenance().user()));
        history.add(msg("assistant", response.message(), response.historyProvenance().assistant()));
        return response;
    }

    private MyHrChatService service(String provider, boolean grounding,
                                    ExternalPolicyProcessingAuthorization authorization) {
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                employees, mock(DocumentRequestService.class), provider, retrieval, grounding, true, false,
                authorization, provenance);
    }

    private MyHrChatService groundedService(ExternalPolicyProcessingAuthorization authorization) {
        return service("claude", true, authorization);
    }

    private static MyHrChatHistoryMessage msg(String role, String content, String stamp) {
        return new MyHrChatHistoryMessage(role, content, stamp);
    }

    private static RetrievalResult retrieved(RetrievedChunk chunk) {
        return new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunk), 1);
    }

    private static RetrievalResult noMatch() {
        return new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0);
    }

    private static RetrievedChunk chunk(ChunkAccessLevel level, String text) {
        return new RetrievedChunk("policy-ingestion-00000", "policy", "Policy", "stored-policy", "policy.pdf",
                "policy-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<MyHrChatHistoryMessage>> historyCaptor() {
        return ArgumentCaptor.forClass((Class<List<MyHrChatHistoryMessage>>) (Class<?>) List.class);
    }

    private List<String> capturedClaudeHistories() {
        ArgumentCaptor<List<MyHrChatHistoryMessage>> histories = historyCaptor();
        verify(claude, org.mockito.Mockito.atLeast(0)).chat(anyString(), histories.capture());
        return histories.getAllValues().stream().flatMap(List::stream).map(MyHrChatHistoryMessage::content).toList();
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
