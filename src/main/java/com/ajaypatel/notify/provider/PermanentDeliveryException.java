package com.ajaypatel.notify.provider;

/** Not retryable: invalid recipient, hard bounce, rejected content. */
public class PermanentDeliveryException extends RuntimeException {
    private final String code;

    public PermanentDeliveryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
