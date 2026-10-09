package com.example.booking_service.Service;

import com.example.booking_service.DTO.BookingRequestDTO;
import com.example.booking_service.DTO.BookingResponseDTO;
import com.example.booking_service.DTO.MyBookingDTO;
import com.example.booking_service.DTO.PassengerDTO;
import com.example.booking_service.Mapper.BookingMapper;
import com.example.booking_service.Repository.BookingRepository;
import com.example.booking_service.Repository.PassengerRepository;
import com.example.booking_service.Repository.TrainRepository;
import com.example.booking_service.Repository.TrainScheduleRepository;
import com.example.booking_service.event.BookingEventPublisher;
import com.example.booking_service.exception.BookingNotFoundException;
import com.example.booking_service.exception.InvalidPassengerException;
import com.example.booking_service.exception.SeatNotAvailableException;
import com.example.booking_service.exception.TrainNotAvailableException;
import com.example.booking_service.exception.UnauthorizedBookingException;
import com.example.booking_service.model.BerthPreference;
import com.example.booking_service.model.BookingEntity;
import com.example.booking_service.model.BookingQuota;
import com.example.booking_service.model.BookingStatus;
import com.example.booking_service.model.Gender;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.model.TrainClass;
import com.example.booking_service.model.TrainDetails;
import com.example.booking_service.model.TrainSchedule;
import com.example.booking_service.model.TrainStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final int MAX_PNR_ATTEMPTS = 5;

    private final BookingRepository bookingRepo;
    private final TrainRepository trainRepo;
    private final TrainScheduleRepository trainScheduleRepo;
    private final PassengerRepository passengerRepo;
    private final PnrGeneratorService pnrGeneratorService;
    private final BookingEventPublisher eventPublisher;
    private final SeatHoldService seatHoldService;

    public BookingService(BookingRepository bookingRepo,
                          TrainRepository trainRepo,
                          TrainScheduleRepository trainScheduleRepo,
                          PassengerRepository passengerRepo,
                          PnrGeneratorService pnrGeneratorService,
                          BookingEventPublisher eventPublisher,
                          SeatHoldService seatHoldService) {
        this.bookingRepo = bookingRepo;
        this.trainRepo = trainRepo;
        this.trainScheduleRepo = trainScheduleRepo;
        this.passengerRepo = passengerRepo;
        this.pnrGeneratorService = pnrGeneratorService;
        this.eventPublisher = eventPublisher;
        this.seatHoldService = seatHoldService;
    }

    /**
     * Places a seat hold for the authenticated caller - does NOT confirm the
     * booking. Seat accounting is done immediately (so the seats can't be
     * double-sold while payment is pending) against the per-(train,
     * journeyDate) TrainSchedule row, locked with SELECT ... FOR UPDATE (see
     * TrainScheduleRepository.findForUpdate) so concurrent requests for the
     * same train/date serialize on the database row instead of racing on a
     * check-then-act decrement. This is correct across multiple app
     * instances, unlike an in-process lock.
     *
     * The booking is created as PENDING_PAYMENT with a holdExpiresAt
     * holdTtlMinutes from now. The caller must complete payment before then;
     * see confirmPayment / expireUnpaidHolds for what happens on each outcome.
     */
    @Transactional
    public BookingResponseDTO bookTicket(Authentication authentication, BookingRequestDTO request) {
        TrainDetails train = null;
        try {
            train = trainRepo.findById(request.getTrainId())
                    .orElseThrow(() -> new com.example.booking_service.exception.TrainNotFoundException(
                            "Train not found with id: " + request.getTrainId()));

            if (train.getStatus() != TrainStatus.ACTIVE) {
                throw new TrainNotAvailableException("Train is not available for booking");
            }

            List<PassengerDTO> passengers = resolvePassengers(request);
            if (passengers.isEmpty()) {
                throw new InvalidPassengerException("At least one passenger is required");
            }
            int passengerCount = passengers.size();

            // Row-level lock on the train+date schedule; held for the rest of this transaction.
            TrainSchedule schedule = getOrCreateScheduleForUpdate(train, request.getTravelDate());

            if (schedule.getAvailableSeats() < passengerCount) {
                throw new SeatNotAvailableException(
                        "Only " + schedule.getAvailableSeats() + " seat(s) left. Please refresh availability.");
            }

            List<String> seats = resolveSeats(request, passengerCount, train, request.getTravelDate());

            BookingEntity booking = BookingMapper.toEntity(request);
            booking.setTrainId(train.getId());
            PassengerDTO primary = passengers.get(0);
            booking.setPassengerName(primary.getName());
            booking.setAge(primary.getAge());
            booking.setGender(primary.getGender() != null ? primary.getGender() : Gender.MALE);
            booking.setUserEmail(authentication.getName());
            booking.setUserId(resolveUserId(authentication));
            booking.setBookingReference("BK" + UUID.randomUUID());
            booking.setTrainNumber(train.getTrainNumber());
            booking.setTrainName(train.getTrainName());
            booking.setSource(train.getSource());
            booking.setDestination(train.getDestination());
            booking.setFare(train.getFare().multiply(BigDecimal.valueOf(passengerCount)));
            booking.setSeatNumber(String.join(", ", seats));
            booking.setTrainClass(TrainClass.CC);
            booking.setQuota(BookingQuota.GENERAL);
            booking.setBookingStatus(BookingStatus.PENDING_PAYMENT);
            booking.setHoldExpiresAt(java.time.LocalDateTime.now().plus(seatHoldService.holdTtl()));

            schedule.setAvailableSeats(schedule.getAvailableSeats() - passengerCount);
            trainScheduleRepo.save(schedule);

            BookingEntity savedBooking = saveWithUniquePnr(booking);
            seatHoldService.placeHold(savedBooking.getId());

            List<Passenger> savedPassengers = new ArrayList<>();
            for (int i = 0; i < passengers.size(); i++) {
                PassengerDTO pd = passengers.get(i);
                savedPassengers.add(BookingMapper.toPassengerEntity(
                        savedBooking.getId(),
                        pd.getName(),
                        pd.getAge(),
                        pd.getGender() != null ? pd.getGender() : Gender.MALE,
                        seats.get(i)));
            }
            passengerRepo.saveAll(savedPassengers);

            BookingResponseDTO response = BookingMapper.toDTO(savedBooking);
            response.setHoldExpiresAt(savedBooking.getHoldExpiresAt());
            response.setMessage("Seats held. Complete payment within "
                    + seatHoldService.holdTtl().toMinutes() + " minutes or the hold will be released.");

            return response;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // BookingController retries this specific race once (lost schedule-row
            // creation, or a PNR pre-check collision) and the retry commonly
            // succeeds. Don't publish a failure event here - if we did, a booking
            // that the user experiences as a single success would also emit a
            // BookingFailedEvent. If the retry fails too, it reaches this same
            // catch again and still won't publish; GlobalExceptionHandler reports
            // the final outcome to the caller instead.
            throw ex;
        } catch (RuntimeException ex) {
            eventPublisher.publishBookingFailed(
                    resolveUserId(authentication), authentication.getName(),
                    request.getTrainId(), train != null ? train.getTrainName() : "Unknown Train",
                    ex.getMessage());
            throw ex;
        }
    }

    /**
     * Invoked when payment-service reports a completed payment for a held
     * booking: flips PENDING_PAYMENT -> CONFIRMED, clears the hold, and fires
     * the success notification/Kafka event that used to fire at hold time.
     * Idempotent - already-CONFIRMED bookings are a no-op so a redelivered
     * Kafka message can't double-fire the notification.
     */
    @Transactional
    public void confirmPayment(Long bookingId) {
        BookingEntity booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + bookingId));

        if (booking.getBookingStatus() != BookingStatus.PENDING_PAYMENT) {
            log.info("Booking {} is {} (not PENDING_PAYMENT), ignoring payment-completed event",
                    bookingId, booking.getBookingStatus());
            return;
        }

        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setHoldExpiresAt(null);
        bookingRepo.save(booking);
        seatHoldService.clearHold(bookingId);

        TrainDetails train = trainRepo.findById(booking.getTrainId()).orElse(null);
        List<String> seats = booking.getSeatNumber() != null
                ? List.of(booking.getSeatNumber().split(",\\s*"))
                : List.of();

        eventPublisher.publishBookingSucceeded(booking, train, seats);
        log.info("Booking {} confirmed after payment", bookingId);
    }

    /**
     * Picks a PNR confirmed not already in use (plain SELECTs, safe to repeat
     * in this transaction) before doing the single save() that actually
     * persists the booking. Deliberately does NOT retry the save itself on a
     * constraint violation: a failed insert can leave the Hibernate session
     * in a state where a same-transaction retry behaves unreliably under
     * real contention, and collisions are astronomically rare given
     * PnrGeneratorService's randomized per-JVM seed - if the final save still
     * collides despite the pre-check, this lets the exception propagate and
     * the whole request fails cleanly for the caller to retry.
     */
    private BookingEntity saveWithUniquePnr(BookingEntity booking) {
        String pnr = pnrGeneratorService.generatePnr();
        for (int attempt = 1; attempt < MAX_PNR_ATTEMPTS && bookingRepo.findByPnr(pnr).isPresent(); attempt++) {
            log.warn("PNR {} already in use (pre-check attempt {}), regenerating", pnr, attempt);
            pnr = pnrGeneratorService.generatePnr();
        }
        booking.setPnr(pnr);
        BookingEntity saved = bookingRepo.save(booking);
        bookingRepo.flush();
        return saved;
    }

    /**
     * Fetches (locking) the schedule row for this train+date, creating it from
     * the train's configured capacity on first booking for that date. If two
     * requests race to create it for the first time, the loser's insert
     * throws a unique-constraint violation that is deliberately left
     * uncaught here: letting it propagate rolls back this whole (otherwise
     * untouched) transaction cleanly, rather than trying to catch-and-continue
     * mid-transaction, which would leave Spring's transaction marked
     * rollback-only regardless (see BookingController.bookTicket, which
     * retries once on exactly this exception - the retry's findForUpdate then
     * finds the winner's already-committed row).
     */
    private TrainSchedule getOrCreateScheduleForUpdate(TrainDetails train, LocalDate journeyDate) {
        return trainScheduleRepo.findForUpdate(train.getId(), journeyDate)
                .orElseGet(() -> trainScheduleRepo.save(
                        new TrainSchedule(train.getId(), journeyDate, train.getTotalSeats())));
    }

    private List<PassengerDTO> resolvePassengers(BookingRequestDTO request) {
        List<PassengerDTO> result = new ArrayList<>();
        if (request.getPassengers() != null && !request.getPassengers().isEmpty()) {
            for (PassengerDTO p : request.getPassengers()) {
                if (p.getName() == null || p.getName().isBlank()) {
                    throw new InvalidPassengerException("Passenger name is required");
                }
                if (p.getAge() == null || p.getAge() < 1) {
                    throw new InvalidPassengerException("Passenger age is invalid");
                }
                result.add(p);
            }
            return result;
        }
        // Legacy single-passenger request.
        if (request.getPassengerName() == null || request.getPassengerName().isBlank()) {
            throw new InvalidPassengerException("Passenger name is required");
        }
        if (request.getAge() == null || request.getAge() < 1) {
            throw new InvalidPassengerException("Passenger age is invalid");
        }
        PassengerDTO single = new PassengerDTO(
                request.getPassengerName(), request.getAge(), request.getGender());
        result.add(single);
        return result;
    }

    /**
     * Allocates the next available seats based on the user's berth preference.
     * The user is not allowed to manually select a seat; the system assigns one
     * that matches the preferred berth when possible and falls back to any open seat.
     */
    private List<String> resolveSeats(BookingRequestDTO request, int count,
                                      TrainDetails train, LocalDate journeyDate) {
        List<String> requested = request.getSeatNumbers();
        if (requested != null && !requested.isEmpty()) {
            if (requested.size() != count) {
                throw new SeatNotAvailableException(
                        "Seat count must match the passenger count.");
            }
            Set<String> normalized = new HashSet<>();
            Set<String> alreadyBooked = bookedSeats(train.getId(), journeyDate);
            for (String seat : requested) {
                String s = seat.trim().toUpperCase();
                if (s.isEmpty()) {
                    throw new SeatNotAvailableException("A selected seat is empty.");
                }
                if (!normalized.add(s)) {
                    throw new SeatNotAvailableException("Duplicate seat selected: " + s);
                }
                if (alreadyBooked.contains(s)) {
                    throw new SeatNotAvailableException(
                            "Seat " + s + " was just booked by another user. Please refresh availability.");
                }
            }
            return new ArrayList<>(normalized);
        }

        BerthPreference preference = request.getBerthPreference();
        Set<String> alreadyBooked = bookedSeats(train.getId(), journeyDate);
        List<String> auto = new ArrayList<>();

        List<String> preferredPool = preferredSeatPool(preference);
        for (String seat : preferredPool) {
            if (auto.size() >= count) {
                break;
            }
            if (!alreadyBooked.contains(seat)) {
                auto.add(seat);
            }
        }

        if (auto.size() < count) {
            int totalSeats = train.getTotalSeats() != null ? train.getTotalSeats() : 200;
            for (int i = 1; i <= totalSeats && auto.size() < count; i++) {
                String seat = "S" + i;
                if (!alreadyBooked.contains(seat) && !preferredPool.contains(seat)) {
                    auto.add(seat);
                }
            }
        }

        if (auto.size() < count) {
            throw new SeatNotAvailableException("Seat allocation failed for the requested berth preference. Please retry.");
        }

        return auto;
    }

    private List<String> preferredSeatPool(BerthPreference preference) {
        return switch (preference) {
            case LOWER -> List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10");
            case MIDDLE -> List.of("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "M10");
            case UPPER -> List.of("U1", "U2", "U3", "U4", "U5", "U6", "U7", "U8", "U9", "U10");
            case SIDE_LOWER -> List.of("SL1", "SL2", "SL3", "SL4", "SL5", "SL6", "SL7", "SL8", "SL9", "SL10");
            case SIDE_UPPER -> List.of("SU1", "SU2", "SU3", "SU4", "SU5", "SU6", "SU7", "SU8", "SU9", "SU10");
        };
    }

    private Set<String> bookedSeats(Long trainId, LocalDate journeyDate) {
        return passengerRepo.findSeatNumbersByTrainAndDate(trainId, journeyDate).stream()
                .map(s -> s.trim().toUpperCase())
                .collect(Collectors.toSet());
    }

    /**
     * Returns a page of bookings belonging to the authenticated user, newest
     * first. Single OR query (rather than unioning two separate lookups) so
     * pagination (total count, page boundaries) is correct.
     */
    @Transactional
    public Page<MyBookingDTO> getUserBookings(Authentication authentication, Pageable pageable) {
        Long userId = resolveUserId(authentication);
        String email = authentication.getName();

        Page<BookingEntity> bookings = bookingRepo.findByUserIdOrUserEmail(userId, email, pageable);

        return bookings.map(b -> MyBookingDTO.fromEntity(b, passengerRepo.findByBookingId(b.getId())));
    }

    /**
     * Fetches a booking owned by the authenticated user.
     * Used by the Payment Service to validate bookings for payment initiation.
     */
    @Transactional
    public BookingEntity getOwnedBooking(Long bookingId, Authentication authentication) {
        BookingEntity booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + bookingId));

        Long callerId = resolveUserId(authentication);
        boolean isOwner = booking.getUserEmail().equalsIgnoreCase(authentication.getName())
                || (booking.getUserId() != null && booking.getUserId().equals(callerId) && callerId != null && callerId > 0);

        if (!isOwner) {
            throw new UnauthorizedBookingException("You do not have access to this booking");
        }

        return booking;
    }

    /**
     * Invoked by the payment-events Kafka consumer when payment-service reports
     * a failed payment: releases the held seats and cancels the booking.
     */
    @Transactional
    public void releaseBookingAfterPaymentFailure(Long bookingId) {
        releaseHold(bookingId, BookingStatus.CANCELLED, "Payment failed");
    }

    /**
     * Lets the owning user cancel their own CONFIRMED or still-PENDING_PAYMENT
     * booking, releasing the seats back to the schedule either way.
     */
    @Transactional
    public void cancelBooking(Long bookingId, Authentication authentication) {
        BookingEntity booking = getOwnedBooking(bookingId, authentication);

        if (booking.getBookingStatus() == BookingStatus.CANCELLED
                || booking.getBookingStatus() == BookingStatus.EXPIRED) {
            return;
        }

        releaseHold(bookingId, BookingStatus.CANCELLED, "Cancelled by user");
    }

    /**
     * Scheduled sweep: the durable enforcement for the 5-minute seat hold
     * window. Redis's TTL on the hold marker is a fast-path convenience, not
     * the authority - it can't invoke code on expiry, and the key can vanish
     * on a Redis restart without releasing anything. This sweep is what
     * actually guarantees an unpaid hold's seats come back, driven by the
     * durable holdExpiresAt column.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedRateString = "${app.booking.hold-sweep-interval-ms:30000}")
    public void expireUnpaidHolds() {
        List<BookingEntity> expired = bookingRepo.findByBookingStatusAndHoldExpiresAtBefore(
                BookingStatus.PENDING_PAYMENT, java.time.LocalDateTime.now());

        for (BookingEntity booking : expired) {
            try {
                releaseHold(booking.getId(), BookingStatus.EXPIRED, "Hold expired before payment");
            } catch (Exception ex) {
                log.error("Failed to expire hold for booking {}: {}", booking.getId(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * Shared release path for payment failure, user cancellation, and hold
     * expiry: releases the held seats back to the schedule, moves the booking
     * to a terminal status, clears the Redis hold marker, and publishes a
     * failure event. Idempotent - re-processing a booking already in a
     * terminal status is a no-op, so redelivered Kafka messages or overlapping
     * sweep runs can't double-release seats.
     */
    @Transactional
    public void releaseHold(Long bookingId, BookingStatus terminalStatus, String reason) {
        BookingEntity booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + bookingId));

        if (booking.getBookingStatus() == BookingStatus.CANCELLED
                || booking.getBookingStatus() == BookingStatus.EXPIRED) {
            log.info("Booking {} already {}, ignoring duplicate release ({})",
                    bookingId, booking.getBookingStatus(), reason);
            return;
        }

        int passengerCount = passengerRepo.findByBookingId(bookingId).size();

        TrainSchedule schedule = trainScheduleRepo.findForUpdate(booking.getTrainId(), booking.getJourneyDate())
                .orElseThrow(() -> new IllegalStateException(
                        "No schedule row for train " + booking.getTrainId() + " on " + booking.getJourneyDate()));
        schedule.setAvailableSeats(schedule.getAvailableSeats() + passengerCount);
        trainScheduleRepo.save(schedule);

        booking.setBookingStatus(terminalStatus);
        booking.setHoldExpiresAt(null);
        bookingRepo.save(booking);
        seatHoldService.clearHold(bookingId);

        eventPublisher.publishBookingFailed(
                booking.getUserId(), booking.getUserEmail(), booking.getTrainId(), booking.getTrainName(), reason);

        log.info("Released {} seat(s) for booking {} -> {} ({})", passengerCount, bookingId, terminalStatus, reason);
    }

    private Long resolveUserId(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (principal instanceof com.example.booking_service.security.BookingPrincipal bookingPrincipal) {
            return bookingPrincipal.getId();
        }

        return 0L;
    }
}
