package com.example.booking_service.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Fast-path marker for an active seat hold, keyed by booking id with the same
 * TTL as the hold itself. This is NOT the source of truth for whether a hold
 * is still valid - Redis can be flushed/restarted and the key would simply
 * vanish without releasing anything. The durable enforcement is the
 * holdExpiresAt column on BookingEntity plus the scheduled sweep in
 * BookingService.expireUnpaidHolds(); this key only lets other services
 * (e.g. payment-service) or callers do a cheap "is this still held" check
 * without hitting Postgres.
 */
@Service
public class SeatHoldService {
    private static final Logger log = LoggerFactory.getLogger(SeatHoldService.class);
    private static final String KEY_PREFIX = "seat-hold:booking:";

    private final StringRedisTemplate redisTemplate;
    private final long holdTtlMinutes;

    public SeatHoldService(StringRedisTemplate redisTemplate,
                            @Value("${app.booking.hold-ttl-minutes:5}") long holdTtlMinutes) {
        this.redisTemplate = redisTemplate;
        this.holdTtlMinutes = holdTtlMinutes;
    }

    public Duration holdTtl() {
        return Duration.ofMinutes(holdTtlMinutes);
    }

    public void placeHold(Long bookingId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + bookingId, "HELD", holdTtl());
        } catch (Exception ex) {
            log.warn("Failed to write seat-hold marker for booking {} (non-fatal, DB holdExpiresAt is authoritative): {}",
                    bookingId, ex.getMessage());
        }
    }

    public boolean isHeld(Long bookingId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + bookingId));
        } catch (Exception ex) {
            log.warn("Failed to check seat-hold marker for booking {}: {}", bookingId, ex.getMessage());
            return false;
        }
    }

    public void clearHold(Long bookingId) {
        try {
            redisTemplate.delete(KEY_PREFIX + bookingId);
        } catch (Exception ex) {
            log.warn("Failed to clear seat-hold marker for booking {}: {}", bookingId, ex.getMessage());
        }
    }
}
