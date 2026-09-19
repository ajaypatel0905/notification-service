package com.ajaypatel.notify.tenant;

import com.ajaypatel.notify.common.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Settings are read on every dispatch cycle, so they are cached in memory and refreshed on write.
 * A single process owns the cache, which matches the non-distributed scope.
 */
@Service
public class PlatformSettingsService {
    private final PlatformSettingRepository repository;
    private final Clock clock;
    private final AtomicReference<PlatformSettings> cache = new AtomicReference<>();

    public PlatformSettingsService(PlatformSettingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PlatformSettings get() {
        PlatformSettings s = cache.get();
        if (s == null) {
            s = load();
            cache.set(s);
        }
        return s;
    }

    @Transactional
    public PlatformSettings update(PlatformSettings s) {
        if (s.defaultRateLimitPerSecond() > s.maxRateLimitPerSecond()) {
            throw new ValidationException("defaultRateLimitPerSecond cannot exceed maxRateLimitPerSecond");
        }
        put(PlatformSettings.K_RATE, s.defaultRateLimitPerSecond());
        put(PlatformSettings.K_BURST, s.defaultRateLimitBurst());
        put(PlatformSettings.K_ATTEMPTS, s.defaultMaxAttempts());
        put(PlatformSettings.K_MAX_RATE, s.maxRateLimitPerSecond());
        cache.set(s);
        return s;
    }

    public void evict() {
        cache.set(null);
    }

    private PlatformSettings load() {
        Map<String, Integer> m = repository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(PlatformSetting::getKey,
                        ps -> Integer.parseInt(ps.getValue())));
        Function<String, Integer> req = k -> {
            Integer v = m.get(k);
            if (v == null) {
                throw new IllegalStateException("Missing platform setting " + k);
            }
            return v;
        };
        return new PlatformSettings(req.apply(PlatformSettings.K_RATE), req.apply(PlatformSettings.K_BURST),
                req.apply(PlatformSettings.K_ATTEMPTS), req.apply(PlatformSettings.K_MAX_RATE));
    }

    private void put(String key, int value) {
        PlatformSetting ps = repository.findById(key).orElseGet(() -> {
            PlatformSetting n = new PlatformSetting();
            n.setKey(key);
            return n;
        });
        ps.setValue(String.valueOf(value));
        ps.setUpdatedAt(clock.instant());
        repository.save(ps);
    }
}
