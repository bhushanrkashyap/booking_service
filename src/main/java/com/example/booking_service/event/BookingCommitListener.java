package com.example.booking_service.event;

import com.example.booking_service.client.NotificationClient;
import com.example.booking_service.client.dto.BookingSuccessNotificationRequest;
import com.example.booking_service.kafka.BookingEventProducer;
import com.example.booking_service.kafka.dto.BookingCreatedEvent;
import com.example.booking_service.model.BookingEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * Runs only after the booking transaction has actually committed - so a
 * success notification/event can never be sent for a booking that ends up
 * rolled back. Both the legacy REST call to notification-service and the new
 * Kafka publish happen from here.
 */
@Component
public class BookingCommitListener {

    private final NotificationClient notificationClient;
    private final BookingEventProducer bookingEventProducer;

    public BookingCommitListener(NotificationClient notificationClient, BookingEventProducer bookingEventProducer) {
        this.notificationClient = notificationClient;
        this.bookingEventProducer = bookingEventProducer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCommitted(BookingCommittedEvent event) {
        BookingEntity booking = event.booking();

        notificationClient.sendBookingSuccess(new BookingSuccessNotificationRequest(
                booking.getId(),
                booking.getUserId(),
                booking.getUserEmail(),
                booking.getTrainName(),
                booking.getSource() + " to " + booking.getDestination(),
                booking.getJourneyDate().toString(),
                String.join(", ", event.seats()),
                booking.getFare() != null ? booking.getFare().doubleValue() : BigDecimal.ZERO.doubleValue()
        ));

        bookingEventProducer.publishBookingCreated(new BookingCreatedEvent(
                booking.getId(),
                booking.getPnr(),
                booking.getUserId(),
                booking.getUserEmail(),
                booking.getTrainId(),
                booking.getTrainNumber(),
                booking.getTrainName(),
                booking.getSource(),
                booking.getDestination(),
                booking.getJourneyDate(),
                event.seats(),
                booking.getFare()
        ));
    }
}
