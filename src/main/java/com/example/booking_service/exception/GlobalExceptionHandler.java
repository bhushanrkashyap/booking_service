package com.example.booking_service.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TrainNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTrainNotFound(TrainNotFoundException ex, WebRequest request) {
        log.warn("Train not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Train not found", "TRAIN_NOT_FOUND", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex, WebRequest request) {
        log.warn("Booking not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Booking not found", "BOOKING_NOT_FOUND", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotAvailable(SeatNotAvailableException ex, WebRequest request) {
        log.warn("Seats not available: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Insufficient seats", "NO_SEATS", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InvalidPassengerException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPassenger(InvalidPassengerException ex, WebRequest request) {
        log.warn("Invalid passenger data: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Invalid passenger data", "INVALID_PASSENGER", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(TrainNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleTrainNotAvailable(TrainNotAvailableException ex, WebRequest request) {
        log.warn("Train not available for booking: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Train not available", "TRAIN_NOT_AVAILABLE", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            org.springframework.security.core.AuthenticationException ex, WebRequest request) {
        log.warn("Authentication failed: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Authentication required", "UNAUTHENTICATED", "A valid bearer token is required");
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(org.springframework.web.client.HttpStatusCodeException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitOrUpstream(
            org.springframework.web.client.HttpStatusCodeException ex, WebRequest request) {
        log.warn("Upstream call failed: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Upstream error", "UPSTREAM_ERROR", "A dependent service call failed");
        return new ResponseEntity<>(error, ex.getStatusCode());
    }

    @ExceptionHandler(UnauthorizedBookingException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedBooking(UnauthorizedBookingException ex, WebRequest request) {
        log.warn("Unauthorized booking access: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse("Unauthorized access", "UNAUTHORIZED", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Booking conflicted with a concurrent update: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "Booking conflict", "BOOKING_CONFLICT",
                "This booking conflicted with a concurrent update. Please retry.");
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Validation error: {}", ex.getMessage());
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");
        ErrorResponse error = new ErrorResponse("Validation error", "VALIDATION_ERROR", details);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
        log.error("Unexpected error: ", ex);
        ErrorResponse error = new ErrorResponse("Internal server error", "INTERNAL_ERROR", "An unexpected error occurred");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
