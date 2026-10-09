package com.example.booking_service.Service;

import com.example.booking_service.DTO.StationSuggestionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper around StationSearchService that adds Redis caching.
 * Cache key: station:suggestions:{normalized_query}
 * Cache TTL: configurable, default 24 hours
 * Fallback: if Redis is unavailable, delegates to StationSearchService without caching.
 */
@Service
public class CachedStationSearchService {
    private static final Logger log = LoggerFactory.getLogger(CachedStationSearchService.class);
    private static final String CACHE_KEY_PREFIX = "station:suggestions:";

    private final StationSearchService stationSearchService;
    private final RedisTemplate<String, List> redisTemplate;
    private final long cacheTtlHours;

    public CachedStationSearchService(
            StationSearchService stationSearchService,
            RedisTemplate<String, List> redisTemplate,
            @Value("${app.cache.station-suggestions-ttl-hours:24}") long cacheTtlHours) {
        this.stationSearchService = stationSearchService;
        this.redisTemplate = redisTemplate;
        this.cacheTtlHours = cacheTtlHours;
    }

    /**
     * Get station suggestions with Redis caching.
     * If the result is cached, return the cached value.
     * Otherwise, query Elasticsearch and cache the result.
     */
    public List<StationSuggestionDTO> suggestStations(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String cacheKey = CACHE_KEY_PREFIX + normalizedQuery;

        try {
            // Try to get from cache
            @SuppressWarnings("unchecked")
            List<StationSuggestionDTO> cached = (List<StationSuggestionDTO>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Cache hit for station suggestions: '{}'", normalizedQuery);
                return cached;
            }
        } catch (Exception ex) {
            log.warn("Redis cache error during read: {}", ex.getMessage());
            // Fall through to Elasticsearch
        }

        // Query Elasticsearch
        List<StationSuggestionDTO> suggestions = stationSearchService.suggestStations(query);

        // Cache the result (only if non-empty to avoid caching "no results" forever)
        if (!suggestions.isEmpty()) {
            try {
                redisTemplate.opsForValue().set(cacheKey, suggestions, cacheTtlHours, TimeUnit.HOURS);
                log.debug("Cached {} station suggestions for query: '{}'", suggestions.size(), normalizedQuery);
            } catch (Exception ex) {
                log.warn("Redis cache error during write: {}", ex.getMessage());
                // Continue without caching
            }
        }

        return suggestions;
    }
}

