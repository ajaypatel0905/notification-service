package com.ajaypatel.notify.common.error;

public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterMillis;

    public RateLimitExceededException(long retryAfterMillis) {
        super("Rate limit exceeded");
        this.retryAfterMillis = retryAfterMillis;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
