package com.ajaypatel.notify.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Seeds the platform-admin key from configuration so a fresh database is immediately usable. */
@Component
@Order(1)
public class BootstrapAdminKeyInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminKeyInitializer.class);

    private final SecurityProperties props;
    private final ApiKeyService apiKeys;

    public BootstrapAdminKeyInitializer(SecurityProperties props, ApiKeyService apiKeys) {
        this.props = props;
        this.apiKeys = apiKeys;
    }

    @Override
    public void run(ApplicationArguments args) {
        String key = props.bootstrapAdminKey();
        if (key == null || key.isBlank()) {
            if (!apiKeys.hasActivePlatformAdmin()) {
                log.warn("No platform admin key exists and notify.security.bootstrap-admin-key is unset; admin APIs are unreachable");
            }
            return;
        }
        if (key.length() < 12) {
            throw new IllegalStateException("notify.security.bootstrap-admin-key must be at least 12 characters");
        }
        apiKeys.storeFixed(null, ApiRole.PLATFORM_ADMIN, "bootstrap", key);
        log.info("Platform admin bootstrap key registered (prefix {})", key.substring(0, Math.min(6, key.length())));
    }
}
