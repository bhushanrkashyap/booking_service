package com.example.booking_service.Controller;

import com.example.booking_service.DTO.StationSuggestionDTO;
import com.example.booking_service.Service.CachedStationSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stations")
public class StationController {
    private final CachedStationSearchService cachedStationSearchService;

    public StationController(CachedStationSearchService cachedStationSearchService) {
        this.cachedStationSearchService = cachedStationSearchService;
    }

    /**
     * Get autocomplete suggestions for stations.
     * Query parameter 'q' should contain the partial station name/code.
     * Returns up to 10 matching stations.
     * Case-insensitive and supports partial matching.
     *
     * @param q Query string (minimum 1 character, can be empty)
     * @return List of station suggestions
     */
    @GetMapping("/suggestions")
    public ResponseEntity<List<StationSuggestionDTO>> suggestStations(
            @RequestParam(value = "q", required = false, defaultValue = "") String q) {
        return ResponseEntity.ok(cachedStationSearchService.suggestStations(q));
    }
}



