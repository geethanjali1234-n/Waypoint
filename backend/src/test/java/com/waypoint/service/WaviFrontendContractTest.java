package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatRequest;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The browser/server contract of the Wavi chat API, checked from both sides: page action keys the server can send
 * exist in the frontend's table for exactly the roles that have the page, and every reply - including a very long
 * model answer - can be sent back as history on the next turn without failing request validation.
 */
class WaviFrontendContractTest {

    private static final Path FRONTEND_ACTIONS = Path.of("..", "frontend", "src", "components", "chatbot", "waviActions.js");
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void everyServerPageKeyHasAFrontendRouteForExactlyTheRolesThatHaveThePage() throws Exception {
        String source = Files.readString(FRONTEND_ACTIONS);
        Matcher row = Pattern.compile("^\\s+([A-Z_]+): \\{([^}]*)\\},?$", Pattern.MULTILINE).matcher(source);
        Set<String> frontendKeys = new TreeSet<>();
        while (row.find()) {
            String key = row.group(1);
            frontendKeys.add(key);
            MyHrConversationIntents.Page page = MyHrConversationIntents.Page.valueOf(key);
            for (UserRole role : List.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN)) {
                boolean routed = row.group(2).contains(role.name() + ": '");
                assertThat(routed).as(key + " route for " + role).isEqualTo(page.availableTo(role));
            }
        }
        Set<String> serverKeys = new TreeSet<>(Arrays.stream(MyHrConversationIntents.Page.values()).map(Enum::name).toList());
        assertThat(frontendKeys).isEqualTo(serverKeys);
    }

    @Test
    void aVeryLongAnswerStillFitsTheNextRequestsHistoryContract() {
        var options = new WaviEvaluationWorld.Options();
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        String longAnswer = ("Line of general workplace guidance that keeps going for a while.\n").repeat(200);
        when(world.claude.chat(anyString(), anyList())).thenReturn(longAnswer);
        String question = "Can you give me some tips for running a good team meeting at work?";
        MyHrChatResponse first = world.service.chat(world.actor, question, List.of(), StampedHistory.SESSION);
        assertThat(first.message().length()).isLessThanOrEqualTo(4000);

        List<MyHrChatHistoryMessage> history = new ArrayList<>(List.of(
                new MyHrChatHistoryMessage("user", question, first.historyProvenance().user()),
                new MyHrChatHistoryMessage("assistant", first.message(), first.historyProvenance().assistant())));
        assertThat(VALIDATOR.validate(new MyHrChatRequest("and for remote teams?", history))).isEmpty();
        // The shortened text is what was stamped, so the next turn verifies it rather than discarding the history.
        assertThat(world.provenance.verify(world.actor, StampedHistory.SESSION, history.get(1))).isPresent();
    }

    @Test
    void shorteningKeepsWholeLinesAndSaysSo() {
        String reply = ("row\n").repeat(2_000);
        MyHrChatResponse bounded = MyHrChatService.withinHistoryLimit(new MyHrChatResponse(reply));
        assertThat(bounded.message()).hasSizeLessThanOrEqualTo(MyHrChatService.MAX_REPLY_CHARS)
                .endsWith(MyHrChatService.SHORTENED_NOTE).doesNotContain("ro\n\n");
        MyHrChatResponse small = new MyHrChatResponse("short");
        assertThat(MyHrChatService.withinHistoryLimit(small)).isSameAs(small);
    }
}
