package com.ajaypatel.notify.provider;

import com.ajaypatel.notify.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
public class ChannelProviderRegistry {
    private final Map<Channel, Map<String, ChannelProvider>> byChannel = new EnumMap<>(Channel.class);

    public ChannelProviderRegistry(List<ChannelProvider> providers) {
        for (ChannelProvider p : providers) {
            ChannelProvider prev = byChannel.computeIfAbsent(p.channel(), c -> new TreeMap<>()).put(p.name(), p);
            if (prev != null) {
                throw new IllegalStateException("Duplicate provider " + p.name() + " for " + p.channel());
            }
        }
    }

    public Optional<ChannelProvider> find(Channel channel, String name) {
        return Optional.ofNullable(byChannel.getOrDefault(channel, Map.of()).get(name));
    }

    public boolean supports(Channel channel, String name) {
        return find(channel, name).isPresent();
    }

    public List<String> providersFor(Channel channel) {
        return List.copyOf(byChannel.getOrDefault(channel, Map.of()).keySet());
    }

    public Optional<String> defaultProviderFor(Channel channel) {
        return providersFor(channel).stream().findFirst();
    }

    public Map<Channel, List<String>> catalogue() {
        Map<Channel, List<String>> out = new EnumMap<>(Channel.class);
        for (Channel c : Channel.values()) {
            out.put(c, providersFor(c));
        }
        return out;
    }
}
