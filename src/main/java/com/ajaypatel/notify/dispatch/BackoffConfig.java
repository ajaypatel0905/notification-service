package com.ajaypatel.notify.dispatch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BackoffConfig {
    @Bean
    ExponentialBackoffPolicy backoffPolicy(RetryProperties p) {
        return new ExponentialBackoffPolicy(p.initialBackoff(), p.multiplier(), p.maxBackoff());
    }
}
