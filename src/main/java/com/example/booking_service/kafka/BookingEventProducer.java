package com.example.booking_service.kafka;

import com.example.booking_service.config.KafkaConfig;
import com.example.booking_service.kafka.dto.BookingCreatedEvent;
import com.example.booking_service.kafka.dto.BookingFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes booking lifecycle events to Kafka for other services to consume.
 * Keyed by trainId so that all events for the same train land on the same
 * partition and are observed in order by any given consumer.
 *
 * KafkaTemplate.send() is NOT purely async despite returning a
 * CompletableFuture: when the producer can't resolve topic metadata within
 * max.block.ms (e.g. broker unreachable), it throws synchronously from
 * send() itself rather than completing the future exceptionally. Publishing
 * a booking event must never affect the booking's actual outcome, so every
 * call here is wrapped - an unreachable Kafka broker must not turn a
 * successful booking into a 500, or replace a real validation error with a
 * Kafka exception.
 */
@Component
public class BookingEventProducer {
    private static final Logger log = LoggerFactory.getLogger(BookingEventProducer.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public BookingEventProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishBookingCreated(BookingCreatedEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.BOOKING_EVENTS_TOPIC, String.valueOf(event.trainId()), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish BookingCreatedEvent for booking {}: {}",
                                    event.bookingId(), ex.getMessage(), ex);
                        } else {
                            log.debug("Published BookingCreatedEvent for booking {}", event.bookingId());
                        }
                    });
        } catch (Exception ex) {
            log.error("Failed to send BookingCreatedEvent for booking {}: {}", event.bookingId(), ex.getMessage(), ex);
        }
    }

    public void publishBookingFailed(BookingFailedEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.BOOKING_EVENTS_TOPIC, String.valueOf(event.trainId()), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish BookingFailedEvent for user {}: {}",
                                    event.userId(), ex.getMessage(), ex);
                        } else {
                            log.debug("Published BookingFailedEvent for user {}", event.userId());
                        }
                    });
        } catch (Exception ex) {
            log.error("Failed to send BookingFailedEvent for user {}: {}", event.userId(), ex.getMessage(), ex);
        }
    }
}
