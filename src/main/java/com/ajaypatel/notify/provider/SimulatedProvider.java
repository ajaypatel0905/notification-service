package com.ajaypatel.notify.provider;

import com.ajaypatel.notify.channel.Channel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for a real email/SMS/push vendor. Behaviour is steerable per recipient so tests and demos
 * are deterministic:
 * <ul>
 *   <li>{@code +transient<N>} in the recipient fails N times with a retryable error, then succeeds</li>
 *   <li>{@code +bounce} fails permanently</li>
 *   <li>{@code +slow<ms>} adds latency</li>
 * </ul>
 * Random failure rates from configuration apply to everything else. The provider remembers idempotency
 * keys it accepted, so a retried request after a crash returns the original message id instead of
 * sending twice, exactly like a well-behaved vendor API.
 */
public class SimulatedProvider implements ChannelProvider {
    private static final Pattern TRANSIENT = Pattern.compile("\\+transient(\\d+)");
    private static final Pattern SLOW = Pattern.compile("\\+slow(\\d+)");

    private final Channel channel;
    private final String name;
    private final SimulatedProviderProperties props;
    private final ProviderCallbackSink callbacks;
    private final Map<String, String> acceptedByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> transientCounters = new ConcurrentHashMap<>();
    private final AtomicInteger sendCount = new AtomicInteger();

    public SimulatedProvider(Channel channel, String name, SimulatedProviderProperties props, ProviderCallbackSink callbacks) {
        this.channel = channel;
        this.name = name;
        this.props = props;
        this.callbacks = callbacks;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DeliveryResult send(DeliveryRequest req) {
        String existing = acceptedByIdempotencyKey.get(req.idempotencyKey());
        if (existing != null) {
            return DeliveryResult.accepted(existing);
        }
        sendCount.incrementAndGet();
        sleep(latencyFor(req.recipient()));

        if (req.recipient().contains("+bounce")) {
            throw new PermanentDeliveryException("HARD_BOUNCE", "Recipient rejected by provider");
        }
        Matcher m = TRANSIENT.matcher(req.recipient());
        if (m.find()) {
            int failuresWanted = Integer.parseInt(m.group(1));
            int soFar = transientCounters.computeIfAbsent(req.idempotencyKey(), k -> new AtomicInteger()).incrementAndGet();
            if (soFar <= failuresWanted) {
                throw new TransientDeliveryException("PROVIDER_TIMEOUT", "Simulated timeout (" + soFar + "/" + failuresWanted + ")");
            }
        } else {
            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll < props.permanentFailureRate()) {
                throw new PermanentDeliveryException("INVALID_RECIPIENT", "Simulated permanent failure");
            }
            if (roll < props.permanentFailureRate() + props.transientFailureRate()) {
                throw new TransientDeliveryException("PROVIDER_5XX", "Simulated transient failure");
            }
        }

        String messageId = name + "-" + UUID.randomUUID();
        acceptedByIdempotencyKey.put(req.idempotencyKey(), messageId);
        if (props.autoCallback()) {
            callbacks.scheduleDelivered(name, messageId, props.autoCallbackDelayMs());
        }
        return DeliveryResult.accepted(messageId);
    }

    public int sendCount() {
        return sendCount.get();
    }

    private long latencyFor(String recipient) {
        Matcher m = SLOW.matcher(recipient);
        return m.find() ? Long.parseLong(m.group(1)) : props.latencyMs();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientDeliveryException("INTERRUPTED", "Worker interrupted while sending");
        }
    }
}
