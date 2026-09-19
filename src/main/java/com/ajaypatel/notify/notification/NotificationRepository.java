package com.ajaypatel.notify.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Notification> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<Notification> findByProviderMessageId(String providerMessageId);

    /** Tenants that have work due right now, so the poller can walk them round-robin. */
    @Query(value = """
            SELECT DISTINCT tenant_id FROM notifications
            WHERE status = 'QUEUED' AND next_attempt_at <= :now
            """, nativeQuery = true)
    List<UUID> findTenantsWithDueWork(@Param("now") Instant now);

    /**
     * Atomically leases up to {@code limit} due rows for one tenant and channel. SKIP LOCKED means
     * concurrent pollers never block on or double-claim the same row.
     */
    @Query(value = """
            UPDATE notifications SET status = 'PROCESSING', leased_by = :workerId, lease_expires_at = :leaseUntil,
                                     updated_at = :now, version = version + 1
            WHERE id IN (
                SELECT id FROM notifications
                WHERE tenant_id = :tenantId AND channel = :channel AND status = 'QUEUED' AND next_attempt_at <= :now
                ORDER BY priority DESC, next_attempt_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id
            """, nativeQuery = true)
    List<UUID> claimForTenantAndChannel(@Param("tenantId") UUID tenantId, @Param("channel") String channel,
                                        @Param("workerId") String workerId, @Param("leaseUntil") Instant leaseUntil,
                                        @Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE notifications SET status = 'QUEUED', updated_at = :now, version = version + 1
            WHERE status = 'SCHEDULED' AND scheduled_at <= :now
            """, nativeQuery = true)
    int promoteDueScheduled(@Param("now") Instant now);

    @Query(value = "SELECT id FROM notifications WHERE status = 'SCHEDULED' AND scheduled_at <= :now", nativeQuery = true)
    List<UUID> findDueScheduledIds(@Param("now") Instant now);

    @Query(value = "SELECT id FROM notifications WHERE status = 'PROCESSING' AND lease_expires_at < :now", nativeQuery = true)
    List<UUID> findExpiredLeases(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> lockById(@Param("id") UUID id);

    @Query(value = """
            SELECT status, COUNT(*) FROM notifications WHERE tenant_id = :tenantId GROUP BY status
            """, nativeQuery = true)
    List<Object[]> countByStatus(@Param("tenantId") UUID tenantId);

    @Query(value = """
            SELECT t.slug, n.channel, n.status, COUNT(*) FROM notifications n JOIN tenants t ON t.id = n.tenant_id
            WHERE n.status IN ('SCHEDULED','QUEUED','PROCESSING') GROUP BY t.slug, n.channel, n.status
            """, nativeQuery = true)
    List<Object[]> backlogByTenantChannel();
}
