package com.example.booking_service.model;

public enum BookingStatus {
    /** Seats are held (schedule already decremented) pending payment confirmation. */
    PENDING_PAYMENT,
    CONFIRMED,
    RAC,
    WAITLISTED,
    /** Hold expired before payment was completed; seats were released. */
    EXPIRED,
    CANCELLED
}
