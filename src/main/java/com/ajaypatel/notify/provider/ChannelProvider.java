package com.ajaypatel.notify.provider;

import com.ajaypatel.notify.channel.Channel;

public interface ChannelProvider {
    Channel channel();

    /** Stable identifier tenants reference in channel configuration, e.g. {@code simulated-email}. */
    String name();

    DeliveryResult send(DeliveryRequest request);

    /** True when the provider only touches the local database and must run inside the completion transaction. */
    default boolean isTransactional() {
        return false;
    }
}
