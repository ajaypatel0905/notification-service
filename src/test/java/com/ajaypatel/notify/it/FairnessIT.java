package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class FairnessIT extends AbstractIntegrationTest {

    @Test
    void smallTenantIsNotStarvedByLargeTenantBacklog() {
        poller.pause();
        try {
            TenantHandle a = api.newTenant(1000, 1000);
            TenantHandle b = api.newTenant(1000, 1000);
            sendBatch(a, "EMAIL", 300, i -> "a" + i + "+slow20@example.com");
            sendBatch(b, "EMAIL", 10, i -> "b" + i + "+slow20@example.com");
            poller.resume();

            awaitSentCount(b.id(), 10, Duration.ofSeconds(60));
            awaitSentCount(a.id(), 300, Duration.ofSeconds(60));

            List<Instant> aSent = sentAt(a.id());
            List<Instant> bSent = sentAt(b.id());
            assertThat(aSent).hasSize(300);
            assertThat(bSent).hasSize(10);
            Instant bLast = bSent.get(bSent.size() - 1);
            assertThat(bLast).as("small tenant finishes before large tenant's median").isBefore(aSent.get(150));
            assertThat(bLast).as("small tenant served in the first cycles").isBeforeOrEqualTo(aSent.get(60));

            Integer wrongAttempts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND attempt_count <> 1", Integer.class, a.id());
            assertThat(wrongAttempts).isZero();
        } finally {
            poller.resume();
        }
    }

    @Test
    void roundRobinAlternatesBetweenTenantsAcrossCycles() {
        poller.pause();
        try {
            TenantHandle x = api.newTenant(1000, 1000);
            TenantHandle y = api.newTenant(1000, 1000);
            TenantHandle z = api.newTenant(1000, 1000);
            for (TenantHandle t : List.of(x, y, z)) {
                sendBatch(t, "SMS", 30, i -> "+9" + i + "+slow5");
            }

            int claimed = poller.pollOnce();
            assertThat(claimed).as("several tenants share one cycle").isGreaterThan(20);

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT tenant_id, COUNT(*) AS c FROM notifications
                    WHERE tenant_id IN (?, ?, ?) AND status IN ('PROCESSING', 'SENT') GROUP BY tenant_id
                    """, x.id(), y.id(), z.id());
            assertThat(rows).hasSize(3);
            for (Map<String, Object> row : rows) {
                assertThat(((Number) row.get("c")).intValue())
                        .as("tenant " + row.get("tenant_id") + " claimed per-tenant batch").isEqualTo(20);
            }

            poller.resume();
            for (TenantHandle t : List.of(x, y, z)) {
                awaitSentCount(t.id(), 30, Duration.ofSeconds(30));
            }
        } finally {
            poller.resume();
        }
    }

    private void sendBatch(TenantHandle t, String channel, int count, java.util.function.IntFunction<String> recipient) {
        ObjectNode batch = obj();
        ArrayNode items = batch.putArray("notifications");
        for (int i = 0; i < count; i++) {
            items.add(api.sendBody(channel, recipient.apply(i), channel.equals("SMS") ? null : "S", "B" + i));
        }
        ResponseEntity<String> r = api.post("/api/v1/notifications/batch", t.apiKey(), batch);
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(202);
        assertThat(json(r).get("accepted").asInt()).isEqualTo(count);
    }

    private List<Instant> sentAt(UUID tenantId) {
        return jdbc.query("SELECT sent_at FROM notifications WHERE tenant_id = ? AND sent_at IS NOT NULL ORDER BY sent_at",
                (rs, i) -> rs.getTimestamp(1).toInstant(), tenantId);
    }

    private void awaitSentCount(UUID tenantId, int expected, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            Integer sent = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND status = 'SENT'", Integer.class, tenantId);
            assertThat(sent).isEqualTo(expected);
        });
    }
}
