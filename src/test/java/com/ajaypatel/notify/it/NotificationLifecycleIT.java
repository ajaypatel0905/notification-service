package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationLifecycleIT extends AbstractIntegrationTest {

    @Test
    void templatedEmailIsRenderedAtAcceptSentByWorkerAndDeliveredOnVendorReceipt() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> tpl = api.post("/api/v1/templates", t.apiKey(), obj()
                .put("code", "welcome").put("channel", "EMAIL").put("name", "Welcome")
                .put("subject", "Welcome {{user.name}}").put("body", "Hi {{user.name}}, plan={{plan}}"));
        assertThat(tpl.getStatusCode().value()).isEqualTo(201);

        ObjectNode body = obj().put("channel", "EMAIL").put("recipient", "jane@example.com").put("templateCode", "welcome");
        body.putObject("variables").put("plan", "Pro").putObject("user").put("name", "Jane");
        ResponseEntity<String> r = api.call(org.springframework.http.HttpMethod.POST, "/api/v1/notifications",
                t.apiKey(), body, Map.of("Idempotency-Key", "welcome-jane-1"));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        JsonNode accepted = json(r);
        assertThat(accepted.get("status").asText()).isEqualTo("QUEUED");
        assertThat(accepted.get("subject").asText()).isEqualTo("Welcome Jane");
        assertThat(accepted.get("body").asText()).isEqualTo("Hi Jane, plan=Pro");
        assertThat(accepted.get("templateCode").asText()).isEqualTo("welcome");
        assertThat(accepted.get("templateVersion").asInt()).isEqualTo(1);
        assertThat(accepted.get("idempotencyKey").asText()).isEqualTo("welcome-jane-1");
        UUID id = UUID.fromString(accepted.get("id").asText());

        JsonNode sent = api.awaitStatus(t, id, "SENT");
        JsonNode n = sent.get("notification");
        assertThat(n.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(n.get("providerMessageId").asText()).startsWith("simulated-email-");
        assertThat(n.get("sentAt")).isNotNull();
        assertThat(sent.get("attempts")).hasSize(1);
        assertThat(sent.get("attempts").get(0).get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(eventTypes(sent)).containsExactly("ACCEPTED", "SENT");

        ResponseEntity<String> cb = api.call(org.springframework.http.HttpMethod.POST, "/api/v1/callbacks/simulated-email", null,
                obj().put("providerMessageId", n.get("providerMessageId").asText()).put("status", "DELIVERED"),
                Map.of("X-Callback-Secret", CALLBACK_SECRET));
        assertThat(cb.getStatusCode().value()).isEqualTo(200);
        assertThat(json(cb).get("result").asText()).isEqualTo("APPLIED");

        JsonNode delivered = api.detail(t, id);
        assertThat(delivered.get("notification").get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.get("notification").get("deliveredAt")).isNotNull();
        assertThat(eventTypes(delivered)).containsExactly("ACCEPTED", "SENT", "PROVIDER_CALLBACK");
    }

    @Test
    void rawBodySmsIsSentWithoutTemplate() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "SMS", "+911234567890");
        JsonNode d = api.awaitStatus(t, id, "SENT");
        assertThat(d.get("notification").get("templateCode")).isNull();
        assertThat(d.get("notification").get("body").asText()).isEqualTo("Body for +911234567890");
    }

    @Test
    void inAppNotificationIsDeliveredIntoInboxAtomically() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(),
                api.sendBody("IN_APP", "user-7", "Heads up", "Dark mode shipped"));
        UUID id = UUID.fromString(json(r).get("id").asText());
        JsonNode d = api.awaitStatus(t, id, "DELIVERED");
        assertThat(eventTypes(d)).containsExactly("ACCEPTED", "DELIVERED");

        JsonNode inbox = json(api.get("/api/v1/inbox/user-7", t.apiKey()));
        assertThat(inbox.get("totalItems").asLong()).isEqualTo(1);
        JsonNode msg = inbox.get("items").get(0);
        assertThat(msg.get("notificationId").asText()).isEqualTo(id.toString());
        assertThat(msg.get("subject").asText()).isEqualTo("Heads up");
        assertThat(msg.get("readAt")).isNull();

        assertThat(json(api.get("/api/v1/inbox/user-7/unread-count", t.apiKey())).get("unread").asLong()).isEqualTo(1);
        JsonNode read = json(api.post("/api/v1/inbox/messages/" + msg.get("id").asText() + "/read", t.apiKey(), null));
        assertThat(read.get("readAt")).isNotNull();
        assertThat(json(api.get("/api/v1/inbox/user-7?unreadOnly=true", t.apiKey())).get("totalItems").asLong()).isZero();
    }

    @Test
    void listAndStatusCountsAreScopedAndFilterable() {
        TenantHandle t = api.newTenant();
        UUID a = api.send(t, "EMAIL", "a@example.com");
        UUID b = api.send(t, "SMS", "+910000000001");
        api.awaitStatus(t, a, "SENT");
        api.awaitStatus(t, b, "SENT");

        JsonNode all = json(api.get("/api/v1/notifications", t.apiKey()));
        assertThat(all.get("totalItems").asLong()).isEqualTo(2);
        JsonNode sms = json(api.get("/api/v1/notifications?channel=SMS", t.apiKey()));
        assertThat(sms.get("totalItems").asLong()).isEqualTo(1);
        assertThat(sms.get("items").get(0).get("id").asText()).isEqualTo(b.toString());
        JsonNode byRecipient = json(api.get("/api/v1/notifications?recipient=a@example.com", t.apiKey()));
        assertThat(byRecipient.get("totalItems").asLong()).isEqualTo(1);
        JsonNode counts = json(api.get("/api/v1/notifications/status-counts", t.apiKey()));
        assertThat(counts.get("SENT").asLong()).isEqualTo(2);
    }

    static List<String> eventTypes(JsonNode detail) {
        List<String> out = new ArrayList<>();
        detail.get("events").forEach(e -> out.add(e.get("type").asText()));
        return out;
    }
}
