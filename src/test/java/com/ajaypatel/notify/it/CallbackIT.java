package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class CallbackIT extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/callbacks/simulated-email";

    @Test
    void deliveredReceiptMovesSentToDelivered() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "EMAIL", "d1@example.com");
        String msgId = api.awaitStatus(t, id, "SENT").get("notification").get("providerMessageId").asText();

        ResponseEntity<String> r = receipt(msgId, "DELIVERED", "ok", CALLBACK_SECRET);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(json(r).get("result").asText()).isEqualTo("APPLIED");

        JsonNode d = api.detail(t, id);
        assertThat(d.get("notification").get("status").asText()).isEqualTo("DELIVERED");
        JsonNode cb = null;
        for (JsonNode e : d.get("events")) {
            if (e.get("type").asText().equals("PROVIDER_CALLBACK")) {
                cb = e;
            }
        }
        assertThat(cb).isNotNull();
        assertThat(cb.get("actor").asText()).isEqualTo("provider:simulated-email");
        assertThat(cb.get("toStatus").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void failedReceiptMovesSentToFailed() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "EMAIL", "d2@example.com");
        String msgId = api.awaitStatus(t, id, "SENT").get("notification").get("providerMessageId").asText();

        ResponseEntity<String> r = receipt(msgId, "FAILED", "mailbox full", CALLBACK_SECRET);
        assertThat(json(r).get("result").asText()).isEqualTo("APPLIED");
        JsonNode n = api.detail(t, id).get("notification");
        assertThat(n.get("status").asText()).isEqualTo("FAILED");
        assertThat(n.get("lastError").asText()).contains("mailbox full");
        assertThat(n.get("failedAt")).isNotNull();
    }

    @Test
    void receiptsAreIdempotent() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "EMAIL", "d3@example.com");
        String msgId = api.awaitStatus(t, id, "SENT").get("notification").get("providerMessageId").asText();

        assertThat(json(receipt(msgId, "DELIVERED", null, CALLBACK_SECRET)).get("result").asText()).isEqualTo("APPLIED");
        ResponseEntity<String> second = receipt(msgId, "DELIVERED", null, CALLBACK_SECRET);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(json(second).get("result").asText()).isEqualTo("ALREADY_FINAL");

        JsonNode d = api.detail(t, id);
        assertThat(d.get("notification").get("status").asText()).isEqualTo("DELIVERED");
        long callbacks = 0;
        for (JsonNode e : d.get("events")) {
            if (e.get("type").asText().equals("PROVIDER_CALLBACK")) {
                callbacks++;
            }
        }
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void unknownMessageIdIsAcknowledgedNotErrored() {
        ResponseEntity<String> r = receipt("simulated-email-" + UUID.randomUUID(), "DELIVERED", null, CALLBACK_SECRET);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(json(r).get("result").asText()).isEqualTo("UNKNOWN_MESSAGE");
    }

    @Test
    void wrongOrMissingSecretIsRejected() {
        assertThat(receipt("x", "DELIVERED", null, "not-the-secret").getStatusCode().value()).isEqualTo(401);
        assertThat(receipt("x", "DELIVERED", null, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void malformedReceiptIs400() {
        ResponseEntity<String> r = api.call(HttpMethod.POST, PATH, null, obj().put("status", "DELIVERED"),
                Map.of("X-Callback-Secret", CALLBACK_SECRET));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    private ResponseEntity<String> receipt(String providerMessageId, String status, String reason, String secret) {
        var body = obj().put("providerMessageId", providerMessageId).put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        Map<String, String> headers = secret == null ? Map.of() : Map.of("X-Callback-Secret", secret);
        return api.call(HttpMethod.POST, PATH, null, body, headers);
    }
}
