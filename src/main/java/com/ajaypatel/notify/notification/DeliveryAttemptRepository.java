package com.ajaypatel.notify.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    List<DeliveryAttempt> findByNotificationIdOrderByAttemptNo(UUID notificationId);

    List<DeliveryAttempt> findByTenantIdAndStartedAtBetweenOrderByStartedAt(UUID tenantId, Instant from, Instant to);

    @Query(value = """
            SELECT outcome, COUNT(*) FROM delivery_attempts
            WHERE tenant_id = :tenantId AND started_at >= :from AND started_at < :to GROUP BY outcome
            """, nativeQuery = true)
    List<Object[]> countOutcomes(@Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to);
}
