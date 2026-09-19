package com.ajaypatel.notify.provider;

/** Retryable: timeouts, 5xx, throttling by the provider. */
public class TransientDeliveryException extends RuntimeException {
    private final String code;

    public TransientDeliveryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
