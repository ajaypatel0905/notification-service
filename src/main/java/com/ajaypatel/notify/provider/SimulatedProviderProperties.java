package com.ajaypatel.notify.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notify.providers.simulated")
public record SimulatedProviderProperties(long latencyMs, double transientFailureRate, double permanentFailureRate,
                                          boolean autoCallback, long autoCallbackDelayMs) {
}
