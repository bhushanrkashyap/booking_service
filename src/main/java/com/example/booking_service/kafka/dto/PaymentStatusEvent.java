package com.example.booking_service.kafka.dto;

/**
 * Consumed from the "payment-events" topic (published by payment-service).
 * status is expected to be "COMPLETED" or "FAILED".
 */
public record PaymentStatusEvent(
        Long bookingId,
        String status,
        String reason) {
}
