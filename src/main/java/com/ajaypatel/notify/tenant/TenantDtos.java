package com.ajaypatel.notify.tenant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class TenantDtos {
    private TenantDtos() {}

    public record CreateTenantRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "lowercase letters, digits and dashes") String slug,
            @NotBlank @Size(max = 200) String name,
            @Min(1) @Max(100_000) Integer rateLimitPerSecond,
            @Min(1) @Max(1_000_000) Integer rateLimitBurst
    ) {}

    public record UpdateTenantRequest(
            @Size(max = 200) String name,
            @Min(1) @Max(100_000) Integer rateLimitPerSecond,
            @Min(1) @Max(1_000_000) Integer rateLimitBurst
    ) {}

    public record TenantResponse(UUID id, String slug, String name, TenantStatus status,
                                 Integer rateLimitPerSecond, Integer rateLimitBurst,
                                 int effectiveRateLimitPerSecond, int effectiveRateLimitBurst,
                                 Instant createdAt, Instant updatedAt) {
        public static TenantResponse from(Tenant t, PlatformSettings s) {
            return new TenantResponse(t.getId(), t.getSlug(), t.getName(), t.getStatus(),
                    t.getRateLimitPerSecond(), t.getRateLimitBurst(),
                    t.getRateLimitPerSecond() != null ? t.getRateLimitPerSecond() : s.defaultRateLimitPerSecond(),
                    t.getRateLimitBurst() != null ? t.getRateLimitBurst() : s.defaultRateLimitBurst(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
