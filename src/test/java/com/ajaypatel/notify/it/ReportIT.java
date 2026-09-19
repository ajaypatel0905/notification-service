package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReportIT extends AbstractIntegrationTest {

    @Test
    void deliverySummaryReflectsOutcomes() {
        TenantHandle t = api.newTenant();
        UUID e1 = api.send(t, "EMAIL", "ok1@example.com");
        UUID e2 = api.send(t, "EMAIL", "ok2@example.com");
        UUID e3 = api.send(t, "EMAIL", "ok3@example.com");
        UUID sms = api.send(t, "SMS", "+91+transient1");
        UUID push = api.send(t, "PUSH", "d+bounce");
        for (UUID id : new UUID[]{e1, e2, e3, sms}) {
            api.awaitStatus(t, id, "SENT");
        }
        api.awaitStatus(t, push, "FAILED");

        JsonNode s = json(api.get("/api/v1/reports/deliveries", t.apiKey()));
        assertThat(s.get("byStatus").get("SENT").asLong()).isEqualTo(4);
        assertThat(s.get("byStatus").get("FAILED").asLong()).isEqualTo(1);
        assertThat(s.get("attemptOutcomes").get("SUCCESS").asLong()).isEqualTo(4);
        assertThat(s.get("attemptOutcomes").get("TRANSIENT_FAILURE").asLong()).isEqualTo(1);
        assertThat(s.get("attemptOutcomes").get("PERMANENT_FAILURE").asLong()).isEqualTo(1);
        assertThat(s.get("total").asLong()).isEqualTo(5);
        assertThat(s.get("finalised").asLong()).isEqualTo(5);
        assertThat(s.get("successRate").asDouble()).isCloseTo(0.8, within(1e-9));
        assertThat(s.get("acceptToSent").get("count").asLong()).isEqualTo(4);
        assertThat(s.get("acceptToSent").get("p95Ms").asLong()).isGreaterThanOrEqualTo(s.get("acceptToSent").get("p50Ms").asLong());
        assertThat(s.get("topErrors").get("PROVIDER_TIMEOUT").asLong()).isEqualTo(1);
        assertThat(s.get("topErrors").get("HARD_BOUNCE").asLong()).isEqualTo(1);

        JsonNode pushOnly = json(api.get("/api/v1/reports/deliveries?channel=PUSH", t.apiKey()));
        assertThat(pushOnly.get("byStatus").get("FAILED").asLong()).isEqualTo(1);
        assertThat(pushOnly.get("byStatus").has("SENT")).isFalse();
        assertThat(pushOnly.get("channel").asText()).isEqualTo("PUSH");
    }

    @Test
    void reportWindowValidation() {
        TenantHandle t = api.newTenant();
        Instant now = Instant.now();
        ResponseEntity<String> inverted = api.get("/api/v1/reports/deliveries?from=" + now + "&to=" + now.minusSeconds(60), t.apiKey());
        assertThat(inverted.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<String> tooWide = api.get("/api/v1/reports/deliveries?from=" + now.minusSeconds(100L * 86400) + "&to=" + now, t.apiKey());
        assertThat(tooWide.getStatusCode().value()).isEqualTo(400);
        assertThat(api.get("/api/v1/reports/deliveries", t.apiKey()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void reportIsTenantScoped() {
        TenantHandle a = api.newTenant();
        TenantHandle b = api.newTenant();
        UUID a1 = api.send(a, "EMAIL", "a1@example.com");
        UUID a2 = api.send(a, "EMAIL", "a2@example.com");
        api.awaitStatus(a, a1, "SENT");
        api.awaitStatus(a, a2, "SENT");

        assertThat(json(api.get("/api/v1/reports/deliveries", a.apiKey())).get("total").asLong()).isEqualTo(2);
        JsonNode bReport = json(api.get("/api/v1/reports/deliveries", b.apiKey()));
        assertThat(bReport.get("total").asLong()).isZero();
        assertThat(bReport.get("byStatus").size()).isZero();
    }
}
