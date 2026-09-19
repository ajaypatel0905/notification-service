package com.ajaypatel.notify.channel;

import com.ajaypatel.notify.channel.ChannelConfigDtos.ChannelConfigResponse;
import com.ajaypatel.notify.channel.ChannelConfigDtos.UpsertChannelConfigRequest;
import com.ajaypatel.notify.provider.ChannelProviderRegistry;
import com.ajaypatel.notify.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/channels")
@Tag(name = "Channel configuration", description = "Tenant admin: enable channels and pick providers")
public class ChannelConfigController {
    private final ChannelConfigService service;
    private final ChannelProviderRegistry providers;

    public ChannelConfigController(ChannelConfigService service, ChannelProviderRegistry providers) {
        this.service = service;
        this.providers = providers;
    }

    @GetMapping
    public List<ChannelConfigResponse> list() {
        return service.list(CurrentPrincipal.get().requireTenantId()).stream()
                .map(ChannelConfigResponse::from).toList();
    }

    @GetMapping("/{channel}")
    public ChannelConfigResponse get(@PathVariable Channel channel) {
        return ChannelConfigResponse.from(service.get(CurrentPrincipal.get().requireTenantId(), channel));
    }

    @PutMapping("/{channel}")
    public ChannelConfigResponse upsert(@PathVariable Channel channel, @Valid @RequestBody UpsertChannelConfigRequest req) {
        return ChannelConfigResponse.from(service.upsert(CurrentPrincipal.get().requireTenantId(), channel, req));
    }

    @GetMapping("/providers")
    public Map<Channel, List<String>> providers() {
        return providers.catalogue();
    }
}
