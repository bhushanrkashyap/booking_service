package com.example.booking_service.kafka;

import com.example.booking_service.Service.BookingService;
import com.example.booking_service.config.KafkaConfig;
import com.example.booking_service.kafka.dto.PaymentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes payment outcome events published by payment-service. Any
 * exception thrown here is retried (fixed backoff) by the container's
 * DefaultErrorHandler and, after retries are exhausted, the record is
 * published to "payment-events.DLT" instead of being dropped (see
 * KafkaConfig.kafkaErrorHandler).
 */
@Component
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final BookingService bookingService;

    public PaymentEventConsumer(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @KafkaListener(topics = KafkaConfig.PAYMENT_EVENTS_TOPIC, groupId = "booking-service")
    public void onPaymentEvent(PaymentStatusEvent event) {
        log.info("Received payment event for booking {}: status={}", event.bookingId(), event.status());

        if (event.bookingId() == null || event.status() == null) {
            log.warn("Ignoring malformed payment event: {}", event);
            return;
        }

        switch (event.status().toUpperCase()) {
            case "FAILED", "EXPIRED", "CANCELLED" ->
                    bookingService.releaseBookingAfterPaymentFailure(event.bookingId());
            case "COMPLETED" ->
                    bookingService.confirmPayment(event.bookingId());
            default ->
                    log.warn("Unknown payment status '{}' for booking {}", event.status(), event.bookingId());
        }
    }
}
