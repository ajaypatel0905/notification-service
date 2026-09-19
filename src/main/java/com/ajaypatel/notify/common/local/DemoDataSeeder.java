package com.ajaypatel.notify.common.local;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.channel.ChannelConfigDtos.UpsertChannelConfigRequest;
import com.ajaypatel.notify.channel.ChannelConfigService;
import com.ajaypatel.notify.inbox.InAppProvider;
import com.ajaypatel.notify.security.ApiKeyService;
import com.ajaypatel.notify.security.ApiRole;
import com.ajaypatel.notify.template.TemplateDtos.CreateTemplateRequest;
import com.ajaypatel.notify.template.TemplateRepository;
import com.ajaypatel.notify.template.TemplateService;
import com.ajaypatel.notify.tenant.Tenant;
import com.ajaypatel.notify.tenant.TenantDtos.CreateTenantRequest;
import com.ajaypatel.notify.tenant.TenantRepository;
import com.ajaypatel.notify.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Two tenants with fixed API keys so the demo script and Swagger UI work immediately. */
@Component
@Order(2)
@ConditionalOnProperty(prefix = "notify.local", name = "seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final TenantRepository tenantRepo;
    private final TenantService tenants;
    private final ApiKeyService apiKeys;
    private final ChannelConfigService channels;
    private final TemplateService templates;
    private final TemplateRepository templateRepo;

    public DemoDataSeeder(TenantRepository tenantRepo, TenantService tenants, ApiKeyService apiKeys,
                          ChannelConfigService channels, TemplateService templates, TemplateRepository templateRepo) {
        this.tenantRepo = tenantRepo;
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.channels = channels;
        this.templates = templates;
        this.templateRepo = templateRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedTenant("acme", "Acme Corp", "acme-tenant-key-000000", 50, 100);
        seedTenant("globex", "Globex Inc", "globex-tenant-key-0000", 5, 5);
        log.info("Demo tenants ready. Keys: acme -> acme-tenant-key-000000 (50/s), globex -> globex-tenant-key-0000 (5/s)");
    }

    private void seedTenant(String slug, String name, String key, int rate, int burst) {
        Tenant t = tenantRepo.findBySlug(slug).orElseGet(() -> tenants.create(new CreateTenantRequest(slug, name, rate, burst)));
        apiKeys.storeFixed(t.getId(), ApiRole.TENANT_ADMIN, "demo", key);
        channels.upsert(t.getId(), Channel.EMAIL, new UpsertChannelConfigRequest(true, "simulated-email", Map.of("from", "noreply@" + slug + ".example"), 5));
        channels.upsert(t.getId(), Channel.SMS, new UpsertChannelConfigRequest(true, "simulated-sms", Map.of("senderId", slug.toUpperCase()), 3));
        channels.upsert(t.getId(), Channel.PUSH, new UpsertChannelConfigRequest(true, "simulated-push", Map.of(), 3));
        channels.upsert(t.getId(), Channel.IN_APP, new UpsertChannelConfigRequest(true, InAppProvider.NAME, Map.of(), 1));
        if (templateRepo.findByTenantIdAndCodeAndChannel(t.getId(), "welcome", Channel.EMAIL).isEmpty()) {
            templates.create(t.getId(), new CreateTemplateRequest("welcome", Channel.EMAIL, "Welcome email",
                    "Welcome to {{company}}, {{user.name}}!",
                    "Hi {{user.name}},\n\nYour account at {{company}} is ready. Your plan: {{plan}}.\n\nThanks,\nThe {{company}} team"));
            templates.create(t.getId(), new CreateTemplateRequest("otp", Channel.SMS, "One-time password", null,
                    "{{code}} is your {{company}} verification code. Valid for {{minutes}} minutes."));
            templates.create(t.getId(), new CreateTemplateRequest("order-update", Channel.PUSH, "Order update",
                    "Order {{orderId}} {{status}}", "Your order {{orderId}} is now {{status}}."));
            templates.create(t.getId(), new CreateTemplateRequest("announcement", Channel.IN_APP, "Product announcement",
                    "{{title}}", "{{body}}"));
        }
    }
}
