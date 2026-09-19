package com.ajaypatel.notify.dispatch;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with full jitter: delay = random(0, min(max, initial * multiplier^(attempt-1))).
 * Full jitter spreads retries of a burst that failed together so they do not hit the provider together again.
 */
public final class ExponentialBackoffPolicy {
    private final Duration initial;
    private final double multiplier;
    private final Duration max;
    private final DoubleSupplier random;

    public ExponentialBackoffPolicy(Duration initial, double multiplier, Duration max) {
        this(initial, multiplier, max, () -> ThreadLocalRandom.current().nextDouble());
    }

    public ExponentialBackoffPolicy(Duration initial, double multiplier, Duration max, DoubleSupplier random) {
        if (initial.isNegative() || initial.isZero() || multiplier < 1.0 || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("invalid backoff configuration");
        }
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
        this.random = random;
    }

    /** Upper bound of the delay window for the given attempt number (1-based, the attempt that just failed). */
    public Duration ceiling(int attemptNo) {
        double factor = Math.pow(multiplier, Math.max(0, attemptNo - 1));
        double millis = Math.min((double) max.toMillis(), initial.toMillis() * factor);
        return Duration.ofMillis((long) millis);
    }

    public Duration nextDelay(int attemptNo) {
        long cap = ceiling(attemptNo).toMillis();
        long jittered = (long) (random.getAsDouble() * cap);
        // never retry instantly: keep at least a tenth of the window so a flapping provider gets breathing room
        return Duration.ofMillis(Math.max(cap / 10, jittered));
    }
}
