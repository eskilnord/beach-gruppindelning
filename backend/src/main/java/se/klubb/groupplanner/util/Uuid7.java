package se.klubb.groupplanner.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 values per RFC 9562 §5.7, used as the {@code TEXT} primary key for
 * every table (CLAUDE.md "SQLite rules": "PKs are TEXT UUIDv7").
 *
 * <p>Layout of the 128 bits (big-endian, matching {@link UUID#UUID(long, long)}'s {@code
 * mostSigBits}/{@code leastSigBits}):
 *
 * <pre>
 * mostSigBits:  unix_ts_ms (48 bits) | version "0111" (4 bits) | rand_a (12 bits)
 * leastSigBits: variant "10" (2 bits) | rand_b (62 bits)
 * </pre>
 *
 * <p>Within the same millisecond, {@code rand_a} is used as a monotonically incrementing counter
 * (RFC 9562's "Monotonic Random" method) rather than fresh random bits every call, so that IDs
 * generated back-to-back sort strictly increasing — important both for the append-only locality of
 * SQLite's PK index and for tests asserting ordering.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MASK = (1 << COUNTER_BITS) - 1;

    private static long lastTimestampMs = -1L;
    private static int counter = 0;

    private Uuid7() {
    }

    /** Generates a new UUIDv7 as a {@link UUID}. */
    public static synchronized UUID generateUuid() {
        long timestampMs = System.currentTimeMillis();

        if (timestampMs <= lastTimestampMs) {
            // Same millisecond as the previous call (or the wall clock moved backwards, e.g. NTP
            // adjustment): reuse the previous timestamp and bump the counter so ordering never
            // goes backwards.
            timestampMs = lastTimestampMs;
            counter = (counter + 1) & COUNTER_MASK;
            if (counter == 0) {
                // Exhausted the 12-bit counter within a single millisecond (4096 calls) - force the
                // timestamp forward by one virtual tick so the value still strictly increases.
                timestampMs++;
            }
        } else {
            // New millisecond: reseed the counter, starting well below the overflow boundary so
            // there is headroom before the next forced tick.
            counter = RANDOM.nextInt(1 << (COUNTER_BITS - 1));
        }
        lastTimestampMs = timestampMs;

        long mostSigBits = ((timestampMs & 0xFFFFFFFFFFFFL) << 16) | (0x7L << 12) | (counter & COUNTER_MASK);

        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL; // 62 random bits
        long leastSigBits = 0x8000000000000000L | randB; // variant "10"

        return new UUID(mostSigBits, leastSigBits);
    }

    /** Generates a new UUIDv7 and returns its canonical string form, ready for a TEXT PK column. */
    public static String generate() {
        return generateUuid().toString();
    }
}
