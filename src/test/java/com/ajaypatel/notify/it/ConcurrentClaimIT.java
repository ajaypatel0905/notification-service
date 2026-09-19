package com.ajaypatel.notify.it;

import com.ajaypatel.notify.dispatch.LeaseReaper;
import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentClaimIT extends AbstractIntegrationTest {

    @Autowired
    LeaseReaper reaper;

    @Test
    void concurrentPollersNeverProcessTheSameNotificationTwice() throws Exception {
        TenantHandle t = api.newTenant(1000, 1000);
        poller.pause();
        try {
            sendBatch(t, "EMAIL", 150);

            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    for (int k = 0; k < 40; k++) {
                        poller.pollOnce();
                        Thread.sleep(10);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();

            // Six claimers racing in one process over-claim against the shared pool capacity check, so a few
            // submissions are rejected and sit leased until the reaper returns them. Drive the reaper here
            // (its scheduled interval is 60s in the test profile) and let the normal poller finish the tail.
            poller.resume();
            Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        jdbc.update("UPDATE notifications SET lease_expires_at = now() - interval '1 second' "
                                + "WHERE tenant_id = ? AND status = 'PROCESSING' AND lease_expires_at > now()", t.id());
                        reaper.reapOnce();
                        return count("SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND status = 'SENT'", t.id()) == 150L;
                    });

            assertThat(count("SELECT COUNT(*) FROM delivery_attempts a JOIN notifications n ON n.id = a.notification_id WHERE n.tenant_id = ?", t.id()))
                    .isEqualTo(150L);
            assertThat(count("SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND attempt_count <> 1", t.id())).isZero();
            assertThat(count("SELECT COUNT(*) FROM notification_events e JOIN notifications n ON n.id = e.notification_id WHERE n.tenant_id = ? AND e.event_type = 'SENT'", t.id()))
                    .isEqualTo(150L);
        } finally {
            poller.resume();
        }
    }

    @Test
    void claimRespectsPerTenantBatchAndReturnsWorkOnce() {
        TenantHandle t = api.newTenant(1000, 1000);
        poller.pause();
        try {
            sendBatch(t, "SMS", 45);

            assertThat(poller.pollOnce()).isEqualTo(20);
            assertThat(poller.pollOnce()).isEqualTo(20);
            assertThat(poller.pollOnce()).isEqualTo(5);
            assertThat(poller.pollOnce()).isZero();
        } finally {
            poller.resume();
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> count("SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND status = 'SENT'", t.id()) == 45L);
    }

    private void sendBatch(TenantHandle t, String channel, int n) {
        ObjectNode body = obj();
        ArrayNode items = body.putArray("notifications");
        for (int i = 0; i < n; i++) {
            String recipient = channel.equals("SMS") ? "+9190000" + String.format("%05d", i) : "u" + i + "@example.com";
            items.add(api.sendBody(channel, recipient, channel.equals("SMS") ? null : "Subject " + i, "Body " + i));
        }
        ResponseEntity<String> r = api.post("/api/v1/notifications/batch", t.apiKey(), body);
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(202);
        assertThat(json(r).get("accepted").asInt()).isEqualTo(n);
    }

    private long count(String sql, UUID tenantId) {
        Long v = jdbc.queryForObject(sql, Long.class, tenantId);
        return v == null ? 0 : v;
    }
}
