package com.example.booking_service.event;

import com.example.booking_service.model.BookingEntity;
import com.example.booking_service.model.TrainDetails;

import java.util.List;

/**
 * Internal (same-JVM) event used purely to defer notification/Kafka delivery
 * for a confirmed booking until after the surrounding @Transactional method
 * commits. Not sent over the wire itself - see BookingCommitListener. Fired
 * on payment confirmation (not at hold placement), since that's the point a
 * booking is actually settled.
 */
public record BookingCommittedEvent(BookingEntity booking, TrainDetails train, List<String> seats) {
}
