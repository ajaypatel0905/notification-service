package com.ajaypatel.notify.inbox;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.provider.ChannelProvider;
import com.ajaypatel.notify.provider.DeliveryRequest;
import com.ajaypatel.notify.provider.DeliveryResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** The one channel this service delivers itself: a row in the recipient's inbox, written atomically with the state change. */
@Component
public class InAppProvider implements ChannelProvider {
    public static final String NAME = "inbox";

    private final InboxMessageRepository inbox;
    private final Clock clock;

    public InAppProvider(InboxMessageRepository inbox, Clock clock) {
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public DeliveryResult send(DeliveryRequest req) {
        InboxMessage existing = inbox.findByNotificationId(req.notificationId()).orElse(null);
        if (existing != null) {
            return DeliveryResult.delivered(existing.getId().toString());
        }
        InboxMessage m = new InboxMessage();
        m.setId(UUID.randomUUID());
        m.setTenantId(req.tenantId());
        m.setNotificationId(req.notificationId());
        m.setRecipient(req.recipient());
        m.setSubject(req.subject());
        m.setBody(req.body());
        m.setCreatedAt(clock.instant());
        inbox.save(m);
        return DeliveryResult.delivered(m.getId().toString());
    }
}
