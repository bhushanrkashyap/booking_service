package com.example.booking_service.Repository;

import com.example.booking_service.model.TrainSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

public interface TrainScheduleRepository extends JpaRepository<TrainSchedule, Long> {
    Optional<TrainSchedule> findByTrainIdAndJourneyDate(Long trainId, LocalDate journeyDate);
    List<TrainSchedule> findByTrainId(Long trainId);
    List<TrainSchedule> findByJourneyDate(LocalDate journeyDate);

    /**
     * Takes a row-level lock (SELECT ... FOR UPDATE) on the train+date schedule
     * row so that concurrent booking requests for the same train/date serialize
     * on the database instead of racing on a check-then-act seat decrement.
     * Must be called inside an active @Transactional method.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ts from TrainSchedule ts where ts.trainId = :trainId and ts.journeyDate = :journeyDate")
    Optional<TrainSchedule> findForUpdate(@Param("trainId") Long trainId, @Param("journeyDate") LocalDate journeyDate);
}
