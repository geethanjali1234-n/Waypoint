package com.waypoint.exception;

import com.waypoint.common.ApiResponse;
import com.waypoint.controller.MyHrChatController;
import com.waypoint.service.OllamaClient;
import com.waypoint.service.ClaudeChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String CHAT_FAILURE_MARKER = "MYHR_CHAT_UNEXPECTED_FAILURE";
    private static final String UNEXPECTED_FAILURE_MARKER = "UNEXPECTED_REQUEST_FAILURE";
    private static final String CLIENT_ROUTE_ERROR_MARKER = "REQUEST_ROUTE_REJECTED";
    private static final String PROVIDER_UNAVAILABLE_MARKER = "MYHR_CHAT_PROVIDER_UNAVAILABLE";
    /** Bounds cause-chain walks, so a cyclic or very deep chain cannot stall error handling. */
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_APPLICATION_FRAMES = 5;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException exception) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ApiResponse<Map<String, String>> response =
                new ApiResponse<>(false, "Validation failed", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception) {

        Map<String, String> details = new HashMap<>();
        Throwable cause = exception.getCause();

        if (cause instanceof InvalidFormatException invalidFormatException
                && invalidFormatException.getTargetType() != null
                && invalidFormatException.getTargetType().isEnum()) {

            List<InvalidFormatException.Reference> path = invalidFormatException.getPath();
            String fieldName = path.isEmpty()
                    ? "value"
                    : path.get(path.size() - 1).getPropertyName();

            String allowedValues = Arrays.stream(invalidFormatException.getTargetType().getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));

            details.put(fieldName, "Must be one of: " + allowedValues);
        } else {
            details.put("request", "Malformed JSON request body");
        }

        ApiResponse<Map<String, String>> response =
                new ApiResponse<>(false, "Invalid request value", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, "Invalid value for parameter '" + exception.getName() + "'", null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
            ResourceNotFoundException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, exception.getMessage(), null);

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(
            UnauthorizedException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, exception.getMessage(), null);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(
            ForbiddenException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, exception.getMessage(), null);

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, exception.getMessage(), null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /** Not a failure: the message may already be with the mail server, so the caller must not resend blindly. */
    @ExceptionHandler(EmailDeliveryUnconfirmedException.class)
    public ResponseEntity<ApiResponse<Void>> handleEmailDeliveryUnconfirmedException(
            EmailDeliveryUnconfirmedException exception) {

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(true, exception.getMessage(), null));
    }

    @ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<ApiResponse<Void>> handleEmailDeliveryException(
            EmailDeliveryException exception) {

        ApiResponse<Void> response =
                new ApiResponse<>(false, exception.getMessage(), null);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    @ExceptionHandler(OllamaClient.OllamaUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleOllamaUnavailable(
            OllamaClient.OllamaUnavailableException exception) {
        ApiResponse<Void> response = new ApiResponse<>(false,
                "The Waypoint Assistant is temporarily unavailable. Please try again shortly.", null);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(ClaudeChatClient.ClaudeConfigurationException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaudeConfiguration(
            ClaudeChatClient.ClaudeConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(false,
                "The configured AI provider is unavailable. Check its server-side configuration.", null));
    }

    /** The configured spending cap stopped this request before it was sent; nothing falls back to another provider. */
    @ExceptionHandler(ClaudeChatClient.ClaudeSpendLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaudeSpendLimit(
            ClaudeChatClient.ClaudeSpendLimitException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(false,
                "The AI assistant has reached its configured usage limit and cannot answer this request.", null));
    }

    @ExceptionHandler(ClaudeChatClient.ClaudeRequestRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaudeRequestRejected(
            ClaudeChatClient.ClaudeRequestRejectedException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiResponse<>(false,
                "The AI provider could not process this request.", null));
    }

    /**
     * Claude was temporarily unavailable or returned no complete answer, and no other provider answers in its
     * place (Claude-only mode; otherwise the chat service falls back before this is reached). Only the exception
     * class and the non-sensitive incomplete reason are logged, never a prompt, policy text or provider output.
     */
    @ExceptionHandler(ClaudeChatClient.ClaudeUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaudeUnavailable(
            ClaudeChatClient.ClaudeUnavailableException exception) {
        String reason = exception instanceof ClaudeChatClient.ClaudeIncompleteResponseException incomplete
                ? String.valueOf(incomplete.reason()) : "none";
        log.warn("{} provider=claude exceptionClass={} reason={}", PROVIDER_UNAVAILABLE_MARKER,
                exception.getClass().getSimpleName(), reason);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(false,
                "The Waypoint Assistant is temporarily unavailable. Please try again shortly.", null));
    }

    /**
     * A known route called with a method it does not support: 405 with the standard Allow header.
     * Client errors, not failures, so only the method is logged, at debug level, never the path.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        log.debug("{} status=405 method={}", CLIENT_ROUTE_ERROR_MARKER, request.getMethod());
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(new ApiResponse<>(false, "Method not allowed", null));
    }

    /**
     * No controller or resource matches the path: 404 without echoing the path. The message is the production
     * contract (HealthControllerTest asserts it); the chat-specific handling below is additive.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoRoute(Exception exception, HttpServletRequest request) {
        log.debug("{} status=404 method={}", CLIENT_ROUTE_ERROR_MARKER, request.getMethod());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResponse<>(false, "The requested endpoint was not found.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            Exception exception, HttpServletRequest request) {

        if (isMyHrChatHandler(request)) {
            // Chat exceptions can carry the user's prompt or provider output, so only class names
            // and the application location are logged, never the message or stack trace.
            log.warn("{} exceptionClass={} rootCauseClass={} location={}", CHAT_FAILURE_MARKER,
                    exception.getClass().getName(), rootCause(exception).getClass().getName(),
                    safeApplicationLocation(rootCause(exception)));
        } else {
            // Exception messages and stack traces can carry connection strings, credentials, tokens or
            // request data, so only the route template, class names and application frames are logged.
            log.error("{} method={} route={} handler={} exceptionClass={} causeChain={} location={} applicationFrames={}",
                    UNEXPECTED_FAILURE_MARKER, request.getMethod(), safeRoute(request), safeHandler(request),
                    exception.getClass().getName(), causeChain(exception), safeApplicationLocation(rootCause(exception)),
                    applicationFrames(exception));
        }

        ApiResponse<Void> response =
                new ApiResponse<>(false, "An unexpected error occurred", null);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private boolean isMyHrChatHandler(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        return handler instanceof HandlerMethod handlerMethod
                && MyHrChatController.class.isAssignableFrom(handlerMethod.getBeanType())
                && "chat".equals(handlerMethod.getMethod().getName());
    }

    private Throwable rootCause(Throwable exception) {
        List<Throwable> chain = causes(exception);
        return chain.get(chain.size() - 1);
    }

    /** The exception and its causes, outermost first; cycle-safe and bounded. */
    private List<Throwable> causes(Throwable exception) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && chain.size() < MAX_CAUSE_DEPTH && seen.add(cause);
                cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    /** Class names only: exception messages are never logged. */
    private String causeChain(Throwable exception) {
        return causes(exception).stream().map(cause -> cause.getClass().getName()).collect(Collectors.joining(" <- "));
    }

    /** The template the request matched, such as /api/myhr/documents/{id}; never the raw path or query string. */
    private String safeRoute(HttpServletRequest request) {
        return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern
                ? pattern : "unmatched";
    }

    private String safeHandler(HttpServletRequest request) {
        return request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) instanceof HandlerMethod handlerMethod
                ? handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName()
                : "none";
    }

    /** Up to five application frames (class.method:line), root cause first; frames hold no exception message. */
    private String applicationFrames(Throwable exception) {
        List<Throwable> chain = causes(exception);
        List<String> frames = new ArrayList<>();
        for (int index = chain.size() - 1; index >= 0 && frames.size() < MAX_APPLICATION_FRAMES; index--) {
            for (StackTraceElement frame : chain.get(index).getStackTrace()) {
                if (frames.size() == MAX_APPLICATION_FRAMES) {
                    break;
                }
                String line = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                if (frame.getClassName().startsWith("com.waypoint.")
                        && !frame.getClassName().equals(GlobalExceptionHandler.class.getName())
                        && !frames.contains(line)) {
                    frames.add(line);
                }
            }
        }
        return frames.isEmpty() ? "none" : String.join(", ", frames);
    }

    private String safeApplicationLocation(Throwable exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (frame.getClassName().startsWith("com.waypoint.")
                    && !frame.getClassName().equals(GlobalExceptionHandler.class.getName())) {
                return frame.getClassName() + "." + frame.getMethodName();
            }
        }
        return "unknown";
    }
}
