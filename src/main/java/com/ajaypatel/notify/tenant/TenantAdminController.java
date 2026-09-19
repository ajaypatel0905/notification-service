package com.ajaypatel.notify.tenant;

import com.ajaypatel.notify.security.ApiKey;
import com.ajaypatel.notify.security.ApiKeyService;
import com.ajaypatel.notify.security.ApiRole;
import com.ajaypatel.notify.tenant.TenantDtos.CreateTenantRequest;
import com.ajaypatel.notify.tenant.TenantDtos.TenantResponse;
import com.ajaypatel.notify.tenant.TenantDtos.UpdateTenantRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Platform admin", description = "Tenants, tenant API keys and global limits")
public class TenantAdminController {
    private final TenantService tenants;
    private final ApiKeyService apiKeys;
    private final PlatformSettingsService settings;

    public TenantAdminController(TenantService tenants, ApiKeyService apiKeys, PlatformSettingsService settings) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.settings = settings;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest req) {
        return TenantResponse.from(tenants.create(req), settings.get());
    }

    @GetMapping("/tenants")
    public List<TenantResponse> list() {
        PlatformSettings s = settings.get();
        return tenants.list().stream().map(t -> TenantResponse.from(t, s)).toList();
    }

    @GetMapping("/tenants/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.from(tenants.get(id), settings.get());
    }

    @PatchMapping("/tenants/{id}")
    public TenantResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest req) {
        return TenantResponse.from(tenants.update(id, req), settings.get());
    }

    @PostMapping("/tenants/{id}/suspend")
    public TenantResponse suspend(@PathVariable UUID id) {
        return TenantResponse.from(tenants.setStatus(id, TenantStatus.SUSPENDED), settings.get());
    }

    @PostMapping("/tenants/{id}/activate")
    public TenantResponse activate(@PathVariable UUID id) {
        return TenantResponse.from(tenants.setStatus(id, TenantStatus.ACTIVE), settings.get());
    }

    public record IssueKeyRequest(@Size(max = 200) String label) {}

    public record IssuedKeyResponse(UUID id, String apiKey, String prefix, String label, Instant createdAt) {}

    public record KeyResponse(UUID id, String prefix, String label, Instant createdAt, Instant revokedAt) {
        static KeyResponse from(ApiKey k) {
            return new KeyResponse(k.getId(), k.getKeyPrefix(), k.getLabel(), k.getCreatedAt(), k.getRevokedAt());
        }
    }

    @PostMapping("/tenants/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyResponse issueKey(@PathVariable UUID id, @Valid @RequestBody(required = false) IssueKeyRequest req) {
        Tenant t = tenants.get(id);
        var issued = apiKeys.issue(t.getId(), ApiRole.TENANT_ADMIN, req == null ? null : req.label());
        return new IssuedKeyResponse(issued.key().getId(), issued.rawKey(), issued.key().getKeyPrefix(),
                issued.key().getLabel(), issued.key().getCreatedAt());
    }

    @GetMapping("/tenants/{id}/api-keys")
    public List<KeyResponse> listKeys(@PathVariable UUID id) {
        return apiKeys.listForTenant(tenants.get(id).getId()).stream().map(KeyResponse::from).toList();
    }

    @DeleteMapping("/tenants/{id}/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable UUID id, @PathVariable UUID keyId) {
        apiKeys.revoke(tenants.get(id).getId(), keyId);
    }

    @GetMapping("/settings")
    public PlatformSettings getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public PlatformSettings updateSettings(@Valid @RequestBody PlatformSettings req) {
        return settings.update(req);
    }
}
