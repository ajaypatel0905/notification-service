package com.ajaypatel.notify.security;

import com.ajaypatel.notify.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keys are shown to the caller exactly once and stored only as a SHA-256 hash.
 * Format: {@code nk_<role-tag>_<random>} so keys are recognisable in logs by prefix only.
 */
@Service
public class ApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record IssuedKey(ApiKey key, String rawKey) {}

    @Transactional
    public IssuedKey issue(UUID tenantId, ApiRole role, String label) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String raw = "nk_" + (role == ApiRole.PLATFORM_ADMIN ? "pa" : "ta") + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedKey(store(tenantId, role, label, raw), raw);
    }

    /** Stores a caller-chosen key (bootstrap admin from configuration). Idempotent per raw key. */
    @Transactional
    public ApiKey storeFixed(UUID tenantId, ApiRole role, String label, String raw) {
        return repository.findByKeyHash(hash(raw)).orElseGet(() -> store(tenantId, role, label, raw));
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticate(String raw) {
        return repository.findByKeyHash(hash(raw)).filter(k -> !k.isRevoked());
    }

    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        ApiKey k = repository.findById(keyId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> NotFoundException.of("API key", keyId));
        if (!k.isRevoked()) {
            k.setRevokedAt(clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listForTenant(UUID tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public boolean hasActivePlatformAdmin() {
        return repository.existsByRoleAndRevokedAtIsNull(ApiRole.PLATFORM_ADMIN);
    }

    private ApiKey store(UUID tenantId, ApiRole role, String label, String raw) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setTenantId(tenantId);
        k.setRole(role);
        k.setKeyHash(hash(raw));
        k.setKeyPrefix(raw.substring(0, Math.min(10, raw.length())));
        k.setLabel(label);
        k.setCreatedAt(clock.instant());
        return repository.save(k);
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
