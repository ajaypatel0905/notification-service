package com.ajaypatel.notify.template;

import com.ajaypatel.notify.channel.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {
    Optional<Template> findByTenantIdAndCodeAndChannel(UUID tenantId, String code, Channel channel);

    List<Template> findByTenantIdOrderByCodeAscChannelAsc(UUID tenantId);
}
