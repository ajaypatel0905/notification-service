package com.ajaypatel.notify.unit;

import com.ajaypatel.notify.dispatch.ExponentialBackoffPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffPolicyTest {

    @Test
    void ceilingDoublesAndIsCappedAtMax() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));
        assertThat(p.ceiling(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.ceiling(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.ceiling(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(p.ceiling(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.ceiling(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.ceiling(50)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterStaysInsideWindowAndNeverGoesBelowFloor() {
        ExponentialBackoffPolicy zero = new ExponentialBackoffPolicy(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), () -> 0.0);
        ExponentialBackoffPolicy one = new ExponentialBackoffPolicy(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), () -> 0.999);
        assertThat(zero.nextDelay(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(one.nextDelay(3)).isBetween(Duration.ofMillis(3990), Duration.ofMillis(4000));
    }

    @Test
    void randomJitterIsAlwaysWithinBounds() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(Duration.ofMillis(100), 2.0, Duration.ofSeconds(2));
        for (int i = 0; i < 1000; i++) {
            Duration d = p.nextDelay(4);
            assertThat(d).isBetween(Duration.ofMillis(80), Duration.ofMillis(800));
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffPolicy(Duration.ZERO, 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffPolicy(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffPolicy(Duration.ofSeconds(5), 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
