package com.example.booking_service.Repository;

import com.example.booking_service.model.BookingEntity;
import com.example.booking_service.model.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<BookingEntity , Long> {
    List<BookingEntity> findByUserEmail(String userEmail);
    List<BookingEntity> findByUserId(Long userId);
    /**
     * Single paged query covering both identity keys, so a user's bookings page
     * correctly regardless of whether historical rows have userId populated.
     */
    Page<BookingEntity> findByUserIdOrUserEmail(Long userId, String userEmail, Pageable pageable);
    List<BookingEntity> findByTrainIdAndJourneyDate(Long trainId, LocalDate journeyDate);
    Optional<BookingEntity> findByPnr(String pnr);
    List<BookingEntity> findByJourneyDateAndTicketPdfSentAtIsNull(LocalDate journeyDate);
    List<BookingEntity> findByBookingStatusAndHoldExpiresAtBefore(BookingStatus status, LocalDateTime cutoff);
}

