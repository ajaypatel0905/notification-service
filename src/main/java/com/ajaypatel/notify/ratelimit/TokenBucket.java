package com.ajaypatel.notify.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Classic token bucket: refills at {@code ratePerSecond}, holds at most {@code burst}.
 * Thread-safe via synchronisation; contention is per tenant and calls are cheap.
 */
public final class TokenBucket {
    private final double ratePerSecond;
    private final double burst;
    private final LongSupplier nanoTime;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int ratePerSecond, int burst, LongSupplier nanoTime) {
        if (ratePerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("rate and burst must be positive");
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.nanoTime = nanoTime;
        this.tokens = burst;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    /** Takes up to {@code wanted} tokens and returns how many were granted (possibly zero). */
    public synchronized int tryAcquireUpTo(int wanted) {
        refill();
        int granted = (int) Math.min(wanted, Math.floor(tokens));
        tokens -= granted;
        return granted;
    }

    public synchronized boolean tryAcquire() {
        return tryAcquireUpTo(1) == 1;
    }

    /** Milliseconds until at least one token is available. */
    public synchronized long millisUntilNextToken() {
        refill();
        if (tokens >= 1) {
            return 0;
        }
        double deficit = 1 - tokens;
        return (long) Math.ceil(deficit / ratePerSecond * 1000);
    }

    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(burst, tokens + elapsed / (double) TimeUnit.SECONDS.toNanos(1) * ratePerSecond);
        lastRefillNanos = now;
    }
}
