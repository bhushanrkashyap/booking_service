package com.example.booking_service;

import com.example.booking_service.DTO.BookingRequestDTO;
import com.example.booking_service.DTO.BookingResponseDTO;
import com.example.booking_service.DTO.PassengerDTO;
import com.example.booking_service.Repository.TrainRepository;
import com.example.booking_service.Service.BookingService;
import com.example.booking_service.config.KafkaConfig;
import com.example.booking_service.model.BerthPreference;
import com.example.booking_service.model.Gender;
import com.example.booking_service.model.TrainDetails;
import com.example.booking_service.model.TrainStatus;
import com.example.booking_service.model.TrainType;
import com.example.booking_service.security.BookingPrincipal;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against real Redis + Kafka (docker containers on localhost:6379 /
 * localhost:9092 - see test instructions) instead of the no-broker-available
 * path the rest of the suite exercises. Verifies: (1) a seat hold actually
 * writes a TTL marker in Redis, (2) a confirmed booking's event actually
 * lands on the booking-events topic, (3) a payment-events message that keeps
 * failing actually lands on payment-events.DLT after the configured retries.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.producer.properties.max.block.ms=5000"
})
class KafkaRedisIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void holdWritesRedisMarker_andConfirmedBookingReachesKafka() {
        Long trainId = seedTrain();
        LocalDate travelDate = LocalDate.now().plusDays(20);

        BookingRequestDTO request = new BookingRequestDTO();
        request.setTrainId(trainId);
        request.setTravelDate(travelDate);
        request.setBerthPreference(BerthPreference.LOWER);
        request.setPassengers(List.of(new PassengerDTO("Redis Kafka Test", 30, Gender.MALE)));

        BookingResponseDTO response = bookingService.bookTicket(authFor(5001L), request);
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey("seat-hold:booking:" + response.getBookingId())),
                "seat hold marker should be written to real Redis");

        try (KafkaConsumer<String, String> consumer = rawConsumer("booking-events-test")) {
            consumer.subscribe(List.of(KafkaConfig.BOOKING_EVENTS_TOPIC));
            bookingService.confirmPayment(response.getBookingId());

            ConsumerRecords<String, String> records = pollUntilNonEmpty(consumer, Duration.ofSeconds(15));
            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains("\"bookingId\":" + response.getBookingId())) {
                    found = true;
                }
            }
            assertTrue(found, "BookingCreatedEvent for booking " + response.getBookingId()
                    + " should have been published to " + KafkaConfig.BOOKING_EVENTS_TOPIC);
        }
    }

    @Test
    void repeatedlyFailingPaymentEventLandsOnDeadLetterTopic() {
        // No booking with this id exists, so PaymentEventConsumer.onPaymentEvent ->
        // BookingService.confirmPayment throws BookingNotFoundException every time,
        // exhausting the 3 retries configured in KafkaConfig.kafkaErrorHandler.
        kafkaTemplate.send(KafkaConfig.PAYMENT_EVENTS_TOPIC, "999999999",
                new com.example.booking_service.kafka.dto.PaymentStatusEvent(999999999L, "COMPLETED", null));

        try (KafkaConsumer<String, String> consumer = rawConsumer("dlt-test")) {
            consumer.subscribe(List.of(KafkaConfig.PAYMENT_EVENTS_DLT));
            ConsumerRecords<String, String> records = pollUntilNonEmpty(consumer, Duration.ofSeconds(20));
            assertTrue(records.count() > 0, "failing payment event should land on " + KafkaConfig.PAYMENT_EVENTS_DLT);
        }
    }

    private ConsumerRecords<String, String> pollUntilNonEmpty(KafkaConsumer<String, String> consumer, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records;
            }
        }
        return ConsumerRecords.empty();
    }

    private KafkaConsumer<String, String> rawConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private Long seedTrain() {
        TrainDetails train = new TrainDetails();
        train.setTrainNumber((int) (System.nanoTime() % 1_000_000));
        train.setTrainName("Kafka Redis Test Express");
        train.setSource("TESTSRC");
        train.setDestination("TESTDST");
        train.setDepartureTime(LocalDateTime.now().plusDays(20));
        train.setArrivalTime(LocalDateTime.now().plusDays(20).plusHours(5));
        train.setDurationInMinutes(300);
        train.setDistanceKm(500.0);
        train.setTotalSeats(10);
        train.setAvailableSeats(10);
        train.setFare(BigDecimal.valueOf(100));
        train.setTrainType(TrainType.EXPRESS);
        train.setStatus(TrainStatus.ACTIVE);
        return trainRepository.save(train).getId();
    }

    private Authentication authFor(long userId) {
        BookingPrincipal principal = new BookingPrincipal(userId, "user" + userId + "@test.com", "USER");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
