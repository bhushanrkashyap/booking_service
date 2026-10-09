package com.example.booking_service.Service;

import com.example.booking_service.DTO.StationSuggestionDTO;
import com.example.booking_service.model.TrainSearchDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StationSearchService {
    private static final Logger log = LoggerFactory.getLogger(StationSearchService.class);
    private static final int MAX_SUGGESTIONS = 10;

    private final ElasticsearchOperations elasticsearchOperations;

    public StationSearchService(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    /**
     * Search for station suggestions by prefix.
     * Case-insensitive, supports partial matching.
     * Deduplicates stations and limits results.
     */
    public List<StationSuggestionDTO> suggestStations(String query) {
        // Return empty for blank/null input
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);

        try {
            // Query for both source and destination stations that match the prefix
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(boolBuilder -> boolBuilder
                            .should(s -> s.wildcard(w -> w
                                    .field("sourceStationNormalized")
                                    .value(normalizedQuery + "*")))
                            .should(s -> s.wildcard(w -> w
                                    .field("destinationStationNormalized")
                                    .value(normalizedQuery + "*")))))
                    .build();

            SearchHits<TrainSearchDocument> hits = elasticsearchOperations.search(nativeQuery, TrainSearchDocument.class);

            // Collect unique stations (by name)
            Set<String> seenStations = new HashSet<>();
            List<StationSuggestionDTO> suggestions = new ArrayList<>();

            for (var hit : hits) {
                TrainSearchDocument doc = hit.getContent();
                if (doc == null) continue;

                // Check source station
                if (doc.getSourceStation() != null && !doc.getSourceStation().isBlank()) {
                    String stationName = doc.getSourceStation().trim();
                    if (!seenStations.contains(stationName) && matchesPrefixCaseInsensitive(stationName, normalizedQuery)) {
                        seenStations.add(stationName);
                        suggestions.add(new StationSuggestionDTO(stationName, stationName));
                        if (suggestions.size() >= MAX_SUGGESTIONS) {
                            break;
                        }
                    }
                }

                // Check destination station if we haven't hit the limit
                if (suggestions.size() < MAX_SUGGESTIONS && doc.getDestinationStation() != null && !doc.getDestinationStation().isBlank()) {
                    String stationName = doc.getDestinationStation().trim();
                    if (!seenStations.contains(stationName) && matchesPrefixCaseInsensitive(stationName, normalizedQuery)) {
                        seenStations.add(stationName);
                        suggestions.add(new StationSuggestionDTO(stationName, stationName));
                    }
                }

                if (suggestions.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }

            return suggestions;
        } catch (Exception ex) {
            log.warn("Error querying Elasticsearch for station suggestions: {}", ex.getMessage());
            // Return empty list on error instead of crashing
            return new ArrayList<>();
        }
    }

    /**
     * Check if station name matches the query prefix (case-insensitive).
     */
    private boolean matchesPrefixCaseInsensitive(String stationName, String queryPrefix) {
        if (stationName == null || queryPrefix == null) {
            return false;
        }
        return stationName.toLowerCase(Locale.ROOT).startsWith(queryPrefix);
    }
}


