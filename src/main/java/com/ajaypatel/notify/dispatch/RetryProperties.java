package com.ajaypatel.notify.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notify.retry")
public record RetryProperties(Duration initialBackoff, double multiplier, Duration maxBackoff) {
}
