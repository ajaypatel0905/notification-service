package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.ajaypatel.notify.tenant.TenantDtos;
import com.ajaypatel.notify.tenant.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Test
    void tenantCannotExceedItsTokenBucketRate() {
        TenantHandle t = api.newTenant(5, 5);
        ObjectNode batch = obj();
        ArrayNode items = batch.putArray("notifications");
        for (int i = 0; i < 20; i++) {
            items.add(api.sendBody("SMS", "+9100000000" + i, null, "otp " + i));
        }
        ResponseEntity<String> r = api.post("/api/v1/notifications/batch", t.apiKey(), batch);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        assertThat(json(r).get("accepted").asInt()).isEqualTo(20);

        awaitSentCount(t.id(), 20, Duration.ofSeconds(30));

        List<Instant> starts = jdbc.query(
                "SELECT started_at FROM delivery_attempts WHERE tenant_id = ? ORDER BY started_at",
                (rs, i) -> rs.getTimestamp(1).toInstant(), t.id());
        assertThat(starts).hasSize(20);
        long spanMs = Duration.between(starts.get(0), starts.get(starts.size() - 1)).toMillis();
        assertThat(spanMs).as("burst of 5 then 15 more at 5/s").isGreaterThanOrEqualTo(2500);
        // A token bucket admits at most burst + rate*window in any window; once the burst is spent, only rate*window.
        for (int i = 0; i < starts.size(); i++) {
            Instant windowStart = starts.get(i);
            Instant windowEnd = windowStart.plusMillis(1000);
            long inWindow = starts.stream().filter(s -> !s.isBefore(windowStart) && s.isBefore(windowEnd)).count();
            int bound = i < 5 ? 5 + 5 + 1 : 5 + 1;
            assertThat(inWindow).as("sends within 1s window starting at attempt " + i).isLessThanOrEqualTo(bound);
        }

        JsonNode stats = json(api.get("/api/v1/admin/dispatch/stats", api.adminKey()));
        JsonNode limiter = stats.get("rateLimiters").get(t.slug());
        assertThat(limiter).isNotNull();
        assertThat(limiter.get("rateLimitedCycles").asLong()).isGreaterThan(0);
    }

    @Test
    void rateLimitChangeTakesEffectWithoutRestart() {
        TenantHandle t = api.newTenant(2, 2);
        ObjectNode batch = obj();
        ArrayNode items = batch.putArray("notifications");
        for (int i = 0; i < 6; i++) {
            items.add(api.sendBody("EMAIL", "r" + i + "@example.com", "S", "B"));
        }
        assertThat(api.post("/api/v1/notifications/batch", t.apiKey(), batch).getStatusCode().value()).isEqualTo(202);

        tenantService.update(t.id(), new TenantDtos.UpdateTenantRequest(null, 1000, 1000));

        awaitSentCount(t.id(), 6, Duration.ofSeconds(5));
        JsonNode tenant = json(api.get("/api/v1/admin/tenants/" + t.id(), api.adminKey()));
        assertThat(tenant.get("effectiveRateLimitPerSecond").asInt()).isEqualTo(1000);
        assertThat(tenant.get("effectiveRateLimitBurst").asInt()).isEqualTo(1000);
    }

    @Test
    void platformDefaultAppliesWhenTenantHasNoOverride() {
        TenantHandle t = api.newTenant();
        JsonNode tenant = json(api.get("/api/v1/admin/tenants/" + t.id(), api.adminKey()));
        JsonNode settings = json(api.get("/api/v1/admin/settings", api.adminKey()));
        assertThat(tenant.get("rateLimitPerSecond")).isNull();
        assertThat(tenant.get("effectiveRateLimitPerSecond").asInt())
                .isEqualTo(settings.get("defaultRateLimitPerSecond").asInt());
        assertThat(tenant.get("effectiveRateLimitBurst").asInt())
                .isEqualTo(settings.get("defaultRateLimitBurst").asInt());
    }

    private void awaitSentCount(UUID tenantId, int expected, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            Integer sent = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE tenant_id = ? AND status = 'SENT'", Integer.class, tenantId);
            assertThat(sent).isEqualTo(expected);
        });
    }
}
