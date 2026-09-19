package com.ajaypatel.notify.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notify.security")
public record SecurityProperties(String bootstrapAdminKey, String callbackSecret) {
}
