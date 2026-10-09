package com.example.booking_service.event;

import com.example.booking_service.kafka.BookingEventProducer;
import com.example.booking_service.kafka.dto.BookingFailedEvent;
import com.example.booking_service.model.BookingEntity;
import com.example.booking_service.model.TrainDetails;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entry point BookingService calls for booking outcomes. Success (payment
 * confirmed) is deferred to after the transaction commits (via an internal
 * ApplicationEvent handled by BookingCommitListener); failure/expiry is
 * published immediately since those paths don't have a corresponding commit
 * to defer to in the same sense. Neither method depends on an HTTP
 * Authentication - failure/expiry can originate from a Kafka consumer or a
 * scheduled sweep with no request in flight.
 */
@Component
public class BookingEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final BookingEventProducer bookingEventProducer;

    public BookingEventPublisher(ApplicationEventPublisher applicationEventPublisher,
                                  BookingEventProducer bookingEventProducer) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.bookingEventProducer = bookingEventProducer;
    }

    public void publishBookingSucceeded(BookingEntity savedBooking, TrainDetails train, List<String> seats) {
        applicationEventPublisher.publishEvent(new BookingCommittedEvent(savedBooking, train, seats));
    }

    public void publishBookingFailed(Long userId, String userEmail, Long trainId, String trainName, String reason) {
        bookingEventProducer.publishBookingFailed(new BookingFailedEvent(userId, userEmail, trainId, trainName, reason));
    }
}
