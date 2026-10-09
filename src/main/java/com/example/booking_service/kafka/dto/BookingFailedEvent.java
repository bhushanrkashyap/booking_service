package com.example.booking_service.kafka.dto;

/**
 * Published to the "booking-events" topic when a booking attempt fails after
 * the train/seat lookup (never published for requests that fail before a
 * train is even identified, since there's nothing a downstream service could
 * act on in that case).
 */
public record BookingFailedEvent(
        Long userId,
        String userEmail,
        Long trainId,
        String trainName,
        String reason) {
}
