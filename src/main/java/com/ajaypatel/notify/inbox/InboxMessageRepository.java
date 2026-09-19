package com.ajaypatel.notify.inbox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {
    Page<InboxMessage> findByTenantIdAndRecipientOrderByCreatedAtDesc(UUID tenantId, String recipient, Pageable pageable);

    Page<InboxMessage> findByTenantIdAndRecipientAndReadAtIsNullOrderByCreatedAtDesc(UUID tenantId, String recipient, Pageable pageable);

    Optional<InboxMessage> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<InboxMessage> findByNotificationId(UUID notificationId);

    long countByTenantIdAndRecipientAndReadAtIsNull(UUID tenantId, String recipient);
}
