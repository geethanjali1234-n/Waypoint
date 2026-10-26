package com.waypoint.exception;

import com.waypoint.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            Exception exception) {

        log.error("Unhandled exception", exception);

        ApiResponse<Void> response =
                new ApiResponse<>(false, "An unexpected error occurred", null);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}