package com.ajaypatel.notify.unit;

import com.ajaypatel.notify.ratelimit.TokenBucket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void startsFullAndDrainsToZero() {
        AtomicLong clock = new AtomicLong();
        TokenBucket b = new TokenBucket(10, 5, clock::get);
        assertThat(b.tryAcquireUpTo(3)).isEqualTo(3);
        assertThat(b.tryAcquireUpTo(10)).isEqualTo(2);
        assertThat(b.tryAcquire()).isFalse();
        assertThat(b.millisUntilNextToken()).isEqualTo(100);
    }

    @Test
    void refillsProportionallyToElapsedTimeAndCapsAtBurst() {
        AtomicLong clock = new AtomicLong();
        TokenBucket b = new TokenBucket(10, 5, clock::get);
        assertThat(b.tryAcquireUpTo(5)).isEqualTo(5);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        assertThat(b.availableTokens()).isCloseTo(2.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.tryAcquireUpTo(5)).isEqualTo(2);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        assertThat(b.availableTokens()).isEqualTo(5.0);
    }

    @Test
    void sustainedRateMatchesConfiguration() {
        AtomicLong clock = new AtomicLong();
        TokenBucket b = new TokenBucket(7, 7, clock::get);
        int granted = 0;
        for (int tick = 0; tick < 100; tick++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            granted += b.tryAcquireUpTo(100);
        }
        // 10 seconds at 7/s plus the initial burst of 7
        assertThat(granted).isBetween(76, 77);
    }
}
