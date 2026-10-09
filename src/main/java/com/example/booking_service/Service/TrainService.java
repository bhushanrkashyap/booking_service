package com.example.booking_service.Service;

import com.example.booking_service.DTO.SeatAvailabilityResponseDTO;
import com.example.booking_service.DTO.TrainResponseDTO;
import com.example.booking_service.DTO.TrainSearchResultDTO;
import com.example.booking_service.Repository.TrainRepository;
import com.example.booking_service.Repository.TrainScheduleRepository;
import com.example.booking_service.model.TrainDetails;
import com.example.booking_service.model.TrainSchedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class TrainService {
    private final TrainRepository trainRepository;
    private final TrainScheduleRepository trainScheduleRepository;
    private final ElasticsearchTrainSearchService elasticsearchTrainSearchService;

    public TrainService(TrainRepository trainRepository,
                         TrainScheduleRepository trainScheduleRepository,
                         ElasticsearchTrainSearchService elasticsearchTrainSearchService) {
        this.trainRepository = trainRepository;
        this.trainScheduleRepository = trainScheduleRepository;
        this.elasticsearchTrainSearchService = elasticsearchTrainSearchService;
    }

    public Page<TrainSearchResultDTO> searchTrains(String source, String destination, LocalDate journeyDate,
                                                     Integer trainNumber, Pageable pageable) {
        return elasticsearchTrainSearchService.search(source, destination, journeyDate, trainNumber, pageable);
    }

    public TrainResponseDTO getTrainById(Long trainId) {
        TrainDetails train = trainRepository.findById(trainId).orElseThrow(
                () -> new com.example.booking_service.exception.TrainNotFoundException("Train not found with id: " + trainId));

        TrainResponseDTO dto = new TrainResponseDTO();
        dto.setId(train.getId());
        dto.setTrainNumber(train.getTrainNumber());
        dto.setTrainName(train.getTrainName());
        dto.setSource(train.getSource());
        dto.setDestination(train.getDestination());
        dto.setDepartureTime(train.getDepartureTime());
        dto.setArrivalTime(train.getArrivalTime());
        dto.setDurationInMinutes(train.getDurationInMinutes());
        dto.setDistanceKm(train.getDistanceKm());
        dto.setAvailableSeats(train.getAvailableSeats());
        dto.setTotalSeats(train.getTotalSeats());
        dto.setFare(train.getFare());
        dto.setTrainType(train.getTrainType());
        dto.setTrainStatus(train.getStatus());
        return dto;
    }

    /**
     * When journeyDate is supplied, returns the live per-date count from
     * TrainSchedule (the row booking actually decrements). Without a date,
     * falls back to the train's overall configured capacity for backward
     * compatibility with existing callers - that figure is NOT live
     * availability for any particular date and should not be used to decide
     * bookability.
     */
    public SeatAvailabilityResponseDTO getSeatAvailability(Long trainId, LocalDate journeyDate) {
        TrainDetails train = trainRepository.findById(trainId).orElseThrow(
                () -> new com.example.booking_service.exception.TrainNotFoundException("Train not found with id: " + trainId));

        if (journeyDate != null) {
            Integer available = trainScheduleRepository.findByTrainIdAndJourneyDate(trainId, journeyDate)
                    .map(TrainSchedule::getAvailableSeats)
                    .orElse(train.getTotalSeats());
            return new SeatAvailabilityResponseDTO(
                    train.getTrainNumber(), train.getTrainName(), train.getTotalSeats(), available);
        }

        return new SeatAvailabilityResponseDTO(
                train.getTrainNumber(),
                train.getTrainName(),
                train.getTotalSeats(),
                train.getAvailableSeats()
        );
    }
}
