package com.example.booking_service;

import com.example.booking_service.DTO.BookingRequestDTO;
import com.example.booking_service.DTO.PassengerDTO;
import com.example.booking_service.Repository.TrainRepository;
import com.example.booking_service.Repository.TrainScheduleRepository;
import com.example.booking_service.Service.BookingService;
import com.example.booking_service.exception.SeatNotAvailableException;
import com.example.booking_service.model.BerthPreference;
import com.example.booking_service.model.Gender;
import com.example.booking_service.model.TrainDetails;
import com.example.booking_service.model.TrainSchedule;
import com.example.booking_service.model.TrainStatus;
import com.example.booking_service.model.TrainType;
import com.example.booking_service.security.BookingPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the pessimistic-lock fix in BookingService actually prevents
 * overselling: fires more concurrent booking requests at a train+date than
 * it has seats for, and asserts the number of successful bookings never
 * exceeds capacity and the schedule's available-seat count never goes
 * negative. Before the fix (in-memory ReentrantLock, no DB row lock), this
 * kind of contention could oversell since the lock didn't serialize separate
 * transactions/connections reliably under a real concurrent-threads scenario.
 */
@SpringBootTest
@ActiveProfiles("test")
class BookingConcurrencyTest {

    private static final int TOTAL_SEATS = 3;
    private static final int CONCURRENT_REQUESTS = 10;

    @org.springframework.beans.factory.annotation.Autowired
    private BookingService bookingService;

    @org.springframework.beans.factory.annotation.Autowired
    private TrainRepository trainRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TrainScheduleRepository trainScheduleRepository;

    private Long trainId;
    private LocalDate travelDate;

    @BeforeEach
    void setUp() {
        travelDate = LocalDate.now().plusDays(10);

        TrainDetails train = new TrainDetails();
        train.setTrainNumber((int) (System.nanoTime() % 1_000_000));
        train.setTrainName("Concurrency Test Express");
        train.setSource("TESTSRC");
        train.setDestination("TESTDST");
        train.setDepartureTime(LocalDateTime.now().plusDays(10));
        train.setArrivalTime(LocalDateTime.now().plusDays(10).plusHours(5));
        train.setDurationInMinutes(300);
        train.setDistanceKm(500.0);
        train.setTotalSeats(TOTAL_SEATS);
        train.setAvailableSeats(TOTAL_SEATS);
        train.setFare(BigDecimal.valueOf(100));
        train.setTrainType(TrainType.EXPRESS);
        train.setStatus(TrainStatus.ACTIVE);

        trainId = trainRepository.save(train).getId();
    }

    @Test
    void concurrentBookingsNeverOversellSeats() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger seatUnavailableCount = new AtomicInteger(0);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            long userId = 1000L + i;
            tasks.add(() -> {
                try {
                    startLine.await();
                    try {
                        bookingService.bookTicket(authenticationFor(userId), bookingRequest());
                    } catch (org.springframework.dao.DataIntegrityViolationException retryable) {
                        // Lost the race to create the schedule row - same retry-once
                        // policy as BookingController.bookTicket.
                        bookingService.bookTicket(authenticationFor(userId), bookingRequest());
                    }
                    successCount.incrementAndGet();
                } catch (SeatNotAvailableException ex) {
                    seatUnavailableCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        tasks.forEach(executor::submit);
        startLine.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "booking requests did not complete in time");

        assertEquals(TOTAL_SEATS, successCount.get(),
                "exactly as many bookings as seats should succeed");
        assertEquals(CONCURRENT_REQUESTS - TOTAL_SEATS, seatUnavailableCount.get(),
                "the rest should be rejected as sold out, not silently oversold");

        TrainSchedule schedule = trainScheduleRepository.findByTrainIdAndJourneyDate(trainId, travelDate)
                .orElseThrow();
        assertEquals(0, schedule.getAvailableSeats(),
                "available seats must land at exactly zero, never negative");
    }

    private BookingRequestDTO bookingRequest() {
        BookingRequestDTO request = new BookingRequestDTO();
        request.setTrainId(trainId);
        request.setTravelDate(travelDate);
        request.setBerthPreference(BerthPreference.LOWER);
        request.setPassengers(List.of(new PassengerDTO("Test Passenger", 30, Gender.MALE)));
        return request;
    }

    private Authentication authenticationFor(long userId) {
        BookingPrincipal principal = new BookingPrincipal(userId, "user" + userId + "@test.com", "USER");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
