package com.example.booking_service.filter;

import tools.jackson.databind.ObjectMapper;
import com.example.booking_service.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Fixed-window request-rate limit for ticket booking, backed by Redis so the
 * limit is shared across all booking-service instances (an in-memory counter
 * would reset per-instance and be trivially bypassed by load-balanced
 * requests). Keyed by authenticated user id when available, falling back to
 * client IP for unauthenticated callers (who will be rejected downstream by
 * Spring Security anyway, but this still caps anonymous request volume).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String KEY_PREFIX = "ratelimit:booking:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final int maxRequests;
    private final long windowSeconds;

    public RateLimitFilter(StringRedisTemplate redisTemplate,
                            @Value("${app.ratelimit.booking.enabled:true}") boolean enabled,
                            @Value("${app.ratelimit.booking.max-requests:5}") int maxRequests,
                            @Value("${app.ratelimit.booking.window-seconds:10}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
                || !HttpMethod.POST.matches(request.getMethod())
                || !"/booking".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = KEY_PREFIX + resolveIdentity(request);

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }
            if (count != null && count > maxRequests) {
                respondTooManyRequests(response);
                return;
            }
        } catch (Exception ex) {
            // Redis unavailable: fail open rather than blocking all bookings on a cache outage.
            log.warn("Rate limit check failed, allowing request through: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof com.example.booking_service.security.BookingPrincipal principal) {
            return "user:" + principal.getId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void respondTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = new ErrorResponse(
                "Too many requests", "RATE_LIMITED",
                "Booking rate limit exceeded (" + maxRequests + " per " + windowSeconds + "s). Please try again shortly.");
        objectMapper.writeValue(response.getWriter(), error);
    }
}
