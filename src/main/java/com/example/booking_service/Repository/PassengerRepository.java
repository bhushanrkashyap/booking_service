package com.example.booking_service.Repository;

import com.example.booking_service.model.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {
    List<Passenger> findByBookingId(Long bookingId);

    /**
     * Single-query fetch of every seat number already taken for a train+date,
     * replacing the previous per-booking N+1 lookup loop.
     */
    @Query("select p.seatNumber from Passenger p " +
            "where p.seatNumber is not null and p.bookingId in (" +
            "  select b.id from BookingEntity b where b.trainId = :trainId and b.journeyDate = :journeyDate" +
            ")")
    List<String> findSeatNumbersByTrainAndDate(@Param("trainId") Long trainId, @Param("journeyDate") LocalDate journeyDate);
}
