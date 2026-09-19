package com.ajaypatel.notify.channel;

public enum Channel {
    EMAIL(true), SMS(false), PUSH(true), IN_APP(true);

    private final boolean supportsSubject;

    Channel(boolean supportsSubject) {
        this.supportsSubject = supportsSubject;
    }

    public boolean supportsSubject() {
        return supportsSubject;
    }
}
