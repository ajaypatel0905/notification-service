package com.ajaypatel.notify.dispatch;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

@Component
public class WorkerIdentity {
    private final String id;

    public WorkerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        this.id = (host.length() > 20 ? host.substring(0, 20) : host) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id() {
        return id;
    }
}
