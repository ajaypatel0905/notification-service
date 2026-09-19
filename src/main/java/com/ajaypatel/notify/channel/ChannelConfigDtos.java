package com.ajaypatel.notify.channel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public final class ChannelConfigDtos {
    private ChannelConfigDtos() {}

    public record UpsertChannelConfigRequest(
            @NotNull Boolean enabled,
            @NotBlank @Size(max = 64) String provider,
            Map<String, Object> settings,
            @Min(1) @Max(50) Integer maxAttempts
    ) {}

    public record ChannelConfigResponse(Channel channel, boolean enabled, String provider,
                                        Map<String, Object> settings, Integer maxAttempts,
                                        Instant createdAt, Instant updatedAt) {
        public static ChannelConfigResponse from(ChannelConfig c) {
            return new ChannelConfigResponse(c.getChannel(), c.isEnabled(), c.getProvider(),
                    c.getSettings() == null ? Map.of() : c.getSettings(), c.getMaxAttempts(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
