package com.ajaypatel.notify.tenant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Global limits every tenant inherits unless overridden. Managed by the platform admin. */
public record PlatformSettings(
        @NotNull @Min(1) @Max(100_000) Integer defaultRateLimitPerSecond,
        @NotNull @Min(1) @Max(1_000_000) Integer defaultRateLimitBurst,
        @NotNull @Min(1) @Max(50) Integer defaultMaxAttempts,
        @NotNull @Min(1) @Max(100_000) Integer maxRateLimitPerSecond
) {
    public static final String K_RATE = "default_rate_limit_per_second";
    public static final String K_BURST = "default_rate_limit_burst";
    public static final String K_ATTEMPTS = "default_max_attempts";
    public static final String K_MAX_RATE = "max_rate_limit_per_second";
}
