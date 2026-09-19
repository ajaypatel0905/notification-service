package com.ajaypatel.notify.provider;

/** Lets a simulated vendor emit the asynchronous "delivered" webhook a real vendor would send. */
public interface ProviderCallbackSink {
    void scheduleDelivered(String provider, String providerMessageId, long delayMs);
}
