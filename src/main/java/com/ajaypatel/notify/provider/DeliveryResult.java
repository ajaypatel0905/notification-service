package com.ajaypatel.notify.provider;

/** {@code delivered} is true when the provider confirms final delivery synchronously (in-app). */
public record DeliveryResult(String providerMessageId, boolean delivered) {
    public static DeliveryResult accepted(String providerMessageId) {
        return new DeliveryResult(providerMessageId, false);
    }

    public static DeliveryResult delivered(String providerMessageId) {
        return new DeliveryResult(providerMessageId, true);
    }
}
