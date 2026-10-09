package com.example.booking_service.Service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates 10-digit PNR candidates. The counter is seeded with a random
 * per-JVM salt so that two instances restarting at the same time don't both
 * start the sequence at the same value. This alone does not guarantee global
 * uniqueness across instances - callers must still handle a unique-constraint
 * violation on save and retry with a freshly generated PNR (see
 * BookingService.bookTicket), which is the actual correctness guarantee.
 */
@Service
public class PnrGeneratorService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicLong counter = new AtomicLong(RANDOM.nextInt(100_000));

    public String generatePnr() {
        long timestampPart = (System.currentTimeMillis() / 1000) % 100_000L;
        long sequencePart = counter.incrementAndGet() % 100_000L;
        return String.format("%05d%05d", timestampPart, sequencePart);
    }
}
