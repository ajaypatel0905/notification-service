package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

class SchedulingIT extends AbstractIntegrationTest {

    @Test
    void scheduledNotificationStaysScheduledUntilDueThenSends() throws InterruptedException {
        TenantHandle t = api.newTenant();
        Instant when = Instant.now().plusSeconds(2);
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(), scheduled(t, "EMAIL", "s@example.com", when));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        JsonNode accepted = json(r);
        assertThat(accepted.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(Instant.parse(accepted.get("scheduledAt").asText())).isEqualTo(when);
        UUID id = UUID.fromString(accepted.get("id").asText());

        Thread.sleep(500);
        assertThat(api.status(t, id)).isEqualTo("SCHEDULED");

        JsonNode d = api.awaitStatus(t, id, Duration.ofSeconds(10), "SENT");
        assertThat(eventTypes(d)).containsExactly("SCHEDULED", "PROMOTED", "SENT");
        Instant sentAt = Instant.parse(d.get("notification").get("sentAt").asText());
        assertThat(sentAt).isAfterOrEqualTo(when);
    }

    @Test
    void scheduledNotificationCanBeCancelledAndIsNeverSent() throws InterruptedException {
        TenantHandle t = api.newTenant();
        UUID id = UUID.fromString(json(api.post("/api/v1/notifications", t.apiKey(),
                scheduled(t, "EMAIL", "c@example.com", Instant.now().plusSeconds(2)))).get("id").asText());

        ResponseEntity<String> cancel = api.post("/api/v1/notifications/" + id + "/cancel", t.apiKey(), null);
        assertThat(cancel.getStatusCode().value()).isEqualTo(200);
        assertThat(json(cancel).get("status").asText()).isEqualTo("CANCELLED");

        Thread.sleep(3500);
        JsonNode d = api.detail(t, id);
        assertThat(d.get("notification").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("notification").get("attemptCount").asInt()).isZero();
        assertThat(eventTypes(d)).containsExactly("SCHEDULED", "CANCELLED");
    }

    @Test
    void cancelIsIdempotentAndRejectedAfterSend() {
        TenantHandle t = api.newTenant();
        UUID scheduledId = UUID.fromString(json(api.post("/api/v1/notifications", t.apiKey(),
                scheduled(t, "EMAIL", "i@example.com", Instant.now().plusSeconds(60)))).get("id").asText());
        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> c = api.post("/api/v1/notifications/" + scheduledId + "/cancel", t.apiKey(), null);
            assertThat(c.getStatusCode().value()).isEqualTo(200);
            assertThat(json(c).get("status").asText()).isEqualTo("CANCELLED");
        }

        UUID sentId = api.send(t, "SMS", "+910000000009");
        api.awaitStatus(t, sentId, "SENT");
        ResponseEntity<String> late = api.post("/api/v1/notifications/" + sentId + "/cancel", t.apiKey(), null);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(json(late).get("title").asText()).isEqualTo("Conflict");
    }

    @Test
    void scheduledAtInThePastSendsImmediately() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(),
                scheduled(t, "EMAIL", "p@example.com", Instant.now().minusSeconds(60)));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        JsonNode accepted = json(r);
        assertThat(accepted.get("status").asText()).isEqualTo("QUEUED");
        assertThat(accepted.get("scheduledAt")).isNull();
        api.awaitStatus(t, UUID.fromString(accepted.get("id").asText()), "SENT");
    }

    @Test
    void scheduledTooFarAheadIsRejected() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(),
                scheduled(t, "EMAIL", "f@example.com", Instant.now().plus(Duration.ofDays(31))));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    private ObjectNode scheduled(TenantHandle t, String channel, String recipient, Instant when) {
        return api.sendBody(channel, recipient, "Subject", "Body").put("scheduledAt", when.toString());
    }

    private static List<String> eventTypes(JsonNode detail) {
        List<String> out = new ArrayList<>();
        detail.get("events").forEach(e -> out.add(e.get("type").asText()));
        return out;
    }
}
