package com.example.booking_service.kafka.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Published to the "booking-events" topic after a booking transaction commits.
 * Consumed by other services (notification, payment, analytics) that need to
 * react to a confirmed booking.
 */
public record BookingCreatedEvent(
        Long bookingId,
        String pnr,
        Long userId,
        String userEmail,
        Long trainId,
        Integer trainNumber,
        String trainName,
        String source,
        String destination,
        LocalDate journeyDate,
        List<String> seatNumbers,
        BigDecimal fare) {
}
