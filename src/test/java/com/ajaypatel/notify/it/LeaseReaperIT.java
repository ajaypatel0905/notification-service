package com.ajaypatel.notify.it;

import com.ajaypatel.notify.dispatch.LeaseReaper;
import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseReaperIT extends AbstractIntegrationTest {

    @Autowired
    LeaseReaper reaper;

    @Test
    void expiredLeaseIsReturnedToQueueWithoutCountingAnAttempt() {
        TenantHandle t = api.newTenant();
        poller.pause();
        try {
            UUID id = api.send(t, "EMAIL", "dead@example.com");
            assertThat(api.status(t, id)).isEqualTo("QUEUED");
            jdbc.update("UPDATE notifications SET status='PROCESSING', leased_by='dead-worker', "
                    + "lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);

            assertThat(reaper.reapOnce()).isEqualTo(1);

            JsonNode d = api.detail(t, id);
            assertThat(d.get("notification").get("status").asText()).isEqualTo("QUEUED");
            assertThat(d.get("notification").get("attemptCount").asInt()).isZero();
            boolean sawExpiry = false;
            for (JsonNode e : d.get("events")) {
                if (e.get("type").asText().equals("LEASE_EXPIRED")) {
                    sawExpiry = true;
                    assertThat(e.get("detail").asText()).contains("dead-worker");
                }
            }
            assertThat(sawExpiry).isTrue();

            poller.resume();
            JsonNode sent = api.awaitStatus(t, id, "SENT");
            assertThat(sent.get("notification").get("attemptCount").asInt()).isEqualTo(1);
        } finally {
            poller.resume();
        }
    }

    @Test
    void liveLeaseIsNotReaped() {
        TenantHandle t = api.newTenant();
        poller.pause();
        UUID id = null;
        try {
            id = api.send(t, "EMAIL", "busy@example.com");
            jdbc.update("UPDATE notifications SET status='PROCESSING', leased_by='busy-worker', "
                    + "lease_expires_at = now() + interval '1 hour' WHERE id = ?", id);

            // other tests share the database, so the reaper may return rows that are not ours; only ours matters
            reaper.reapOnce();
            assertThat(api.status(t, id)).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT leased_by FROM notifications WHERE id = ?", String.class, id))
                    .isEqualTo("busy-worker");
        } finally {
            if (id != null) {
                jdbc.update("UPDATE notifications SET status='QUEUED', leased_by=NULL, lease_expires_at=NULL WHERE id = ?", id);
            }
            poller.resume();
        }
        api.awaitStatus(t, id, "SENT");
    }
}
