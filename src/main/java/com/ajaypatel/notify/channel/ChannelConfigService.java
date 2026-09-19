package com.ajaypatel.notify.channel;

import com.ajaypatel.notify.channel.ChannelConfigDtos.UpsertChannelConfigRequest;
import com.ajaypatel.notify.common.error.NotFoundException;
import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.provider.ChannelProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChannelConfigService {
    private final ChannelConfigRepository repository;
    private final ChannelProviderRegistry providers;
    private final Clock clock;

    public ChannelConfigService(ChannelConfigRepository repository, ChannelProviderRegistry providers, Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.clock = clock;
    }

    @Transactional
    public ChannelConfig upsert(UUID tenantId, Channel channel, UpsertChannelConfigRequest req) {
        if (!providers.supports(channel, req.provider())) {
            throw new ValidationException("Unknown provider '" + req.provider() + "' for channel " + channel
                    + ". Available: " + providers.providersFor(channel));
        }
        Instant now = clock.instant();
        ChannelConfig c = repository.findByTenantIdAndChannel(tenantId, channel).orElseGet(() -> {
            ChannelConfig n = new ChannelConfig();
            n.setId(UUID.randomUUID());
            n.setTenantId(tenantId);
            n.setChannel(channel);
            n.setCreatedAt(now);
            return n;
        });
        c.setEnabled(req.enabled());
        c.setProvider(req.provider());
        c.setSettings(req.settings() == null ? Map.of() : req.settings());
        c.setMaxAttempts(req.maxAttempts());
        c.setUpdatedAt(now);
        return repository.save(c);
    }

    @Transactional(readOnly = true)
    public List<ChannelConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByChannel(tenantId);
    }

    @Transactional(readOnly = true)
    public ChannelConfig get(UUID tenantId, Channel channel) {
        return repository.findByTenantIdAndChannel(tenantId, channel)
                .orElseThrow(() -> NotFoundException.of("Channel config", channel));
    }

    @Transactional(readOnly = true)
    public Optional<ChannelConfig> find(UUID tenantId, Channel channel) {
        return repository.findByTenantIdAndChannel(tenantId, channel);
    }
}
