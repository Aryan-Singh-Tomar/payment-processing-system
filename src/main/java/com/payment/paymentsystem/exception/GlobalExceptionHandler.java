package com.payment.paymentsystem.exception;

import com.payment.paymentsystem.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -----------------------------------------------------------------
    // 400 Bad Request — request couldn't be understood
    // -----------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request
    ){
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " : " + fe.getDefaultMessage())
                .toList();

        log.warn("Validation failed on {}: {}", request.getRequestURI(), details);

        return build(HttpStatus.BAD_REQUEST, "Validation failed",
                request.getRequestURI(), details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request
    ){
        log.warn("Malformed request body on {}: {}", request.getRequestURI(), ex.getMessage());

        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request body",
                request.getRequestURI(), null);

    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request)
    {
        String message = "Parameter '" + ex.getName() + "' has invalid value '" + ex.getValue() + "'";
        log.warn("{} on {}", message, request.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, message, request.getRequestURI(), null);
    }

    // -----------------------------------------------------------------
    // 404 Not Found — resource doesn't exist
    // -----------------------------------------------------------------
    @ExceptionHandler({OrderNotFoundException.class, PaymentNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(
            RuntimeException ex, HttpServletRequest request)
    {
        log.warn("Not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(),
                request.getRequestURI(), null);
    }

    // -----------------------------------------------------------------
    // 405 Method Not Allowed
    // -----------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                ex.getMessage(),
                request.getRequestURI(), null);
    }

    // -----------------------------------------------------------------
    // 409 Conflict — DB constraint violation
    // -----------------------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        // Log the full cause for ourselves; do not leak it to the client.
        log.warn("Data integrity violation on {}: {}",
                request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT,
                "Request conflicts with existing data",
                request.getRequestURI(), null);
    }

    // -----------------------------------------------------------------
    // 422 Unprocessable Entity — business rule violation
    // -----------------------------------------------------------------

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPayment(
            InvalidPaymentRequestException ex, HttpServletRequest request) {
        log.warn("Invalid payment request on {}: {}",
                request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(),
                request.getRequestURI(), null);
    }

    // -----------------------------------------------------------------
    // 500 Internal Server Error — fallback
    // -----------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                request.getRequestURI(), null);
    }



    // -----------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                String path, List<String> details) {
        ErrorResponse body = new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                details
        );
        return ResponseEntity.status(status).body(body);
    }




}
