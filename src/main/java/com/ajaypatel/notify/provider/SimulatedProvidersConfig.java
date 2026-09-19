package com.ajaypatel.notify.provider;

import com.ajaypatel.notify.channel.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatedProvidersConfig {
    @Bean
    SimulatedProvider simulatedEmail(SimulatedProviderProperties p, ProviderCallbackSink s) {
        return new SimulatedProvider(Channel.EMAIL, "simulated-email", p, s);
    }

    @Bean
    SimulatedProvider simulatedSms(SimulatedProviderProperties p, ProviderCallbackSink s) {
        return new SimulatedProvider(Channel.SMS, "simulated-sms", p, s);
    }

    @Bean
    SimulatedProvider simulatedPush(SimulatedProviderProperties p, ProviderCallbackSink s) {
        return new SimulatedProvider(Channel.PUSH, "simulated-push", p, s);
    }
}
