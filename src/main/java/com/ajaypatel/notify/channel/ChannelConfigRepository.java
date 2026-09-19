package com.ajaypatel.notify.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, UUID> {
    Optional<ChannelConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);

    List<ChannelConfig> findByTenantIdOrderByChannel(UUID tenantId);
}
