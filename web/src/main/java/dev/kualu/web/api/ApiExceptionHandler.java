package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.ErrorResponse;
import dev.kualu.web.chat.LlmNotConfiguredException;
import dev.kualu.web.chat.UpstreamLlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::fieldMessage)
                .orElse("validation failed");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "malformed request body");
    }

    @ExceptionHandler(LlmNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleLlmNotConfigured(LlmNotConfiguredException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(UpstreamLlmException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(UpstreamLlmException ex) {
        return body(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        return body(status, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private String fieldMessage(FieldError error) {
        return error.getField() + " " + (error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
