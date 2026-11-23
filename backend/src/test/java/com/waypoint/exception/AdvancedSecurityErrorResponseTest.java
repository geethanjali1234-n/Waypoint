package com.waypoint.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 9.3 T6: error responses and logs from the real GlobalExceptionHandler. A throwaway test
 * controller raises each failure with canary "secrets" in the exception message, causes, path,
 * query string or request body; no real secret is used. G6 and G7 are fixed; these are regression tests.
 */
class AdvancedSecurityErrorResponseTest {

    static final String CANARY_PASSWORD = "S3cr3t-Canary-93";
    static final String CANARY_URI = "mongodb+srv://rag_admin:" + CANARY_PASSWORD + "@cluster.example.net/waypoint_rag_test";
    static final String CANARY_JWT = "canary-jwt-secret-9f8e7d6c5b4a";
    static final String CANARY_API_KEY = "pa-canary-api-key-4c3b2a1f";
    static final List<String> FORBIDDEN_IN_LOGS = List.of(CANARY_PASSWORD, CANARY_JWT, CANARY_API_KEY, "mongodb+srv",
            "cluster.example.net", "rag_admin", "waypoint_rag_test");
    static final String GENERIC_500 = "{\"success\":false,\"message\":\"An unexpected error occurred\",\"data\":null}";
    static final String METHOD_NOT_ALLOWED_BODY = "{\"success\":false,\"message\":\"Method not allowed\",\"data\":null}";
    static final String NOT_FOUND_BODY = "{\"success\":false,\"message\":\"The requested endpoint was not found.\",\"data\":null}";
    static final List<String> FORBIDDEN_IN_BODIES = List.of(CANARY_PASSWORD, "mongodb+srv", "cluster.example.net",
            CANARY_JWT, "waypoint_rag_test", "Exception", "at com.", "at org.", "java.", "springframework", "jackson",
            "SQL", "BSON", "stack");

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        logs.start();
        handlerLogger.addAppender(logs);
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void validationErrorsReturnFieldMessagesButNeverTheRejectedValue() throws Exception {
        String body = perform(post("/t/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + CANARY_PASSWORD + "-too-long\"}"), 400);
        assertThat(body).contains("Validation failed").contains("Code is too long");
        assertSafe(body);
    }

    @Test
    void malformedJsonReturnsAGenericMessage() throws Exception {
        String body = perform(post("/t/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"" + CANARY_PASSWORD + "\", broken"), 400);
        assertThat(body).contains("Malformed JSON request body");
        assertSafe(body);
        assertThat(body).doesNotContain("line").doesNotContain("column");
    }

    @Test
    void typeMismatchNamesOnlyTheParameter() throws Exception {
        String body = perform(get("/t/typed").param("count", CANARY_PASSWORD), 400);
        assertThat(body).contains("Invalid value for parameter 'count'");
        assertSafe(body);
    }

    @Test
    void unauthorizedForbiddenAndNotFoundCarryOnlyTheirFixedMessages() throws Exception {
        assertThat(perform(get("/t/unauthorized"), 401)).isEqualTo("{\"success\":false,\"message\":\"Invalid or expired token\",\"data\":null}");
        assertThat(perform(get("/t/forbidden"), 403)).isEqualTo("{\"success\":false,\"message\":\"You do not have access to this resource\",\"data\":null}");
        assertThat(perform(get("/t/not-found"), 404)).isEqualTo("{\"success\":false,\"message\":\"Document not found\",\"data\":null}");
    }

    @Test
    void anUnexpectedFailureCarryingSecretsReturnsOnlyTheGenericMessage() throws Exception {
        String body = perform(get("/t/boom"), 500);
        assertThat(body).isEqualTo("{\"success\":false,\"message\":\"An unexpected error occurred\",\"data\":null}");
        assertSafe(body);
    }

    // --- G6 (fixed): unexpected failures are logged without exception messages or stack traces ------

    @Test
    void g6_anUnexpectedFailureIsLoggedWithoutItsMessageOrStackTrace() throws Exception {
        perform(get("/t/boom"), 500);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("no stack trace or exception message is attached").isNull();
        });
        String logged = loggedText();
        assertNoCanary(logged);
        assertThat(logged).doesNotContain("Connection failed").doesNotContain("at com.").doesNotContain("Unhandled exception");
        // Useful, non-sensitive diagnostics remain.
        assertThat(logged).contains("UNEXPECTED_REQUEST_FAILURE")
                .contains("method=GET")
                .contains("route=/t/boom")
                .contains("handler=FailingController.boom")
                .contains("exceptionClass=java.lang.IllegalStateException")
                .contains("causeChain=java.lang.IllegalStateException")
                .contains("location=" + FailingController.class.getName() + ".boom")
                .containsPattern("applicationFrames=" + Pattern.quote(FailingController.class.getName()) + "\\.boom:\\d+");
    }

    @Test
    void g6_secretsInWrappedCausesAreNeverLoggedButTheCauseChainIs() throws Exception {
        assertThat(perform(get("/t/wrapped"), 500)).isEqualTo(GENERIC_500);

        String logged = loggedText();
        assertNoCanary(logged);
        assertThat(logged).doesNotContain("api_key").doesNotContain("Repository call failed")
                .contains("causeChain=java.lang.RuntimeException <- java.lang.IllegalArgumentException <- java.lang.IllegalStateException")
                .contains("location=" + FailingController.class.getName() + ".wrapped");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void g6_pathValuesQueryStringsAndRequestBodiesAreNeverLogged() throws Exception {
        assertThat(perform(get("/t/items/" + CANARY_PASSWORD).param("apiKey", CANARY_API_KEY), 500)).isEqualTo(GENERIC_500);
        assertThat(perform(post("/t/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + CANARY_PASSWORD + "\",\"token\":\"" + CANARY_JWT + "\"}"), 500)).isEqualTo(GENERIC_500);

        String logged = loggedText();
        assertNoCanary(logged);
        assertThat(logged).doesNotContain("apiKey=").doesNotContain("\"password\"")
                // Only the route template is logged, never the value in the path.
                .contains("method=GET route=/t/items/{id} handler=FailingController.item")
                .contains("method=POST route=/t/echo handler=FailingController.echo");
        assertThat(logs.list).hasSize(2).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    @Timeout(5)
    void g6_aCyclicCauseChainIsLoggedSafelyAndDoesNotStallTheHandler() {
        // Called directly: Spring MVC's own exception-handler lookup already recurses on a cyclic chain
        // before any advice runs, so this checks only that the handler's own cause walk is bounded.
        IllegalStateException outer = new IllegalStateException("outer " + CANARY_PASSWORD);
        IllegalArgumentException inner = new IllegalArgumentException("inner " + CANARY_JWT, outer);
        outer.initCause(inner);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/t/cyclic");

        var response = new GlobalExceptionHandler().handleGeneralException(outer, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
        String logged = loggedText();
        assertNoCanary(logged);
        assertThat(logged).contains("causeChain=java.lang.IllegalStateException <- java.lang.IllegalArgumentException location=");
    }

    // --- G7 (fixed): wrong methods and unknown paths get 405 / 404, not a 500 ----------------------

    @Test
    void g7_aWrongHttpMethodIsAnswered405WithTheAllowHeaderAndNoErrorLog() throws Exception {
        MvcResult result = mockMvc.perform(get("/t/post-only").param("token", CANARY_JWT)).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow")).isEqualTo("POST");
        assertThat(body).isEqualTo(METHOD_NOT_ALLOWED_BODY);
        assertSafe(body);
        assertThat(body).doesNotContain("post-only");
        assertNoErrorLogAndNoRequestDetail();
    }

    @Test
    void g7_anUnknownPathIsAnswered404WithoutEchoingThePath() throws Exception {
        MvcResult result = mockMvc.perform(get("/t/does-not-exist/" + CANARY_PASSWORD).param("apiKey", CANARY_API_KEY))
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(body).isEqualTo(NOT_FOUND_BODY);
        assertSafe(body);
        assertThat(body).doesNotContain("does-not-exist");
        assertNoErrorLogAndNoRequestDetail();
    }

    @Test
    void g7_aMissingStaticResourceIsAlso404() throws Exception {
        // The running application reports unknown paths as NoResourceFoundException (static-resource lookup).
        MvcResult result = mockMvc.perform(get("/t/no-resource")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(NOT_FOUND_BODY);
        assertNoErrorLogAndNoRequestDetail();
    }

    @Test
    void g7_aSupportedMethodOnTheSameRouteStillWorks() throws Exception {
        assertThat(perform(post("/t/post-only"), 200)).isEqualTo("ok");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void g7_failuresInsideAValidRouteAreStillGeneric500s() throws Exception {
        assertThat(perform(get("/t/boom"), 500)).isEqualTo(GENERIC_500);
        assertThat(perform(get("/t/not-found"), 404)).isEqualTo("{\"success\":false,\"message\":\"Document not found\",\"data\":null}");
    }

    // --- Helpers --------------------------------------------------------------------------------

    private String perform(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    /** Every captured log line, including any attached stack trace and exception messages. */
    private String loggedText() {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage() + "\n" + (event.getThrowableProxy() == null ? ""
                        : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
    }

    /** Client routing errors are not failures: nothing at WARN or above, and no path, query or message logged. */
    private void assertNoErrorLogAndNoRequestDetail() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getLevel().isGreaterOrEqual(Level.WARN)).as("level " + event.getLevel()).isFalse();
            assertThat(event.getThrowableProxy()).isNull();
        });
        String logged = loggedText();
        assertNoCanary(logged);
        assertThat(logged).doesNotContain("/t/").doesNotContain("does-not-exist").doesNotContain("no-resource");
    }

    private static void assertNoCanary(String logged) {
        for (String forbidden : FORBIDDEN_IN_LOGS) {
            assertThat(logged).as("log must not contain \"" + forbidden + "\"").doesNotContainIgnoringCase(forbidden);
        }
    }

    private static void assertSafe(String body) {
        for (String forbidden : FORBIDDEN_IN_BODIES) {
            assertThat(body).as("response body must not contain \"" + forbidden + "\"").doesNotContainIgnoringCase(forbidden);
        }
    }

    record CodeRequest(@Size(max = 8, message = "Code is too long") String code) {
    }

    @RestController
    @RequestMapping("/t")
    static class FailingController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody CodeRequest request) {
            return "ok";
        }

        @GetMapping("/typed")
        String typed(@RequestParam int count) {
            return "ok";
        }

        @GetMapping("/unauthorized")
        String unauthorized() {
            throw new UnauthorizedException("Invalid or expired token");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new ForbiddenException("You do not have access to this resource");
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Document not found");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("Connection failed for " + CANARY_URI + " with JWT_SECRET=" + CANARY_JWT);
        }

        @GetMapping("/wrapped")
        String wrapped() {
            throw new RuntimeException("Repository call failed for " + CANARY_URI,
                    new IllegalArgumentException("api_key=" + CANARY_API_KEY,
                            new IllegalStateException("JWT_SECRET=" + CANARY_JWT)));
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable String id, @RequestParam(required = false) String apiKey) {
            throw new IllegalStateException("Lookup failed for " + id + " using " + apiKey);
        }

        @PostMapping("/echo")
        String echo(@RequestBody Map<String, String> body) {
            throw new IllegalStateException("Could not process " + body);
        }

        @GetMapping("/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/t/no-resource/" + CANARY_PASSWORD, "t/no-resource");
        }

        @PostMapping("/post-only")
        String postOnly() {
            return "ok";
        }
    }
}
