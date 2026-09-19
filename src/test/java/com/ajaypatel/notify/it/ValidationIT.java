package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationIT extends AbstractIntegrationTest {

    @Test
    void missingRequiredFieldsReturnFieldMap() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(), obj());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        JsonNode pd = json(r);
        assertThat(pd.get("title").asText()).isEqualTo("Validation failed");
        assertThat(pd.get("fields").has("channel")).isTrue();
        assertThat(pd.get("fields").has("recipient")).isTrue();
    }

    @Test
    void exactlyOneOfTemplateOrBody() {
        TenantHandle t = api.newTenant();
        ObjectNode both = api.sendBody("SMS", "+911", null, "b").put("templateCode", "x");
        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(), both);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(json(r).get("detail").asText()).contains("exactly one");

        ObjectNode neither = obj().put("channel", "SMS").put("recipient", "+911");
        assertThat(api.post("/api/v1/notifications", t.apiKey(), neither).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void emailNeedsSubjectSmsRejectsSubject() {
        TenantHandle t = api.newTenant();
        assertThat(api.post("/api/v1/notifications", t.apiKey(), api.sendBody("EMAIL", "a@b.c", null, "body"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(api.post("/api/v1/notifications", t.apiKey(), api.sendBody("SMS", "+911", "subj", "body"))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unconfiguredOrDisabledChannelIsRejected() {
        String slug = "bare-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> created = api.post("/api/v1/admin/tenants", api.adminKey(), obj().put("slug", slug).put("name", "Bare"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String id = json(created).get("id").asText();
        String key = json(api.post("/api/v1/admin/tenants/" + id + "/api-keys", api.adminKey(), obj())).get("apiKey").asText();

        ResponseEntity<String> notConfigured = api.post("/api/v1/notifications", key, api.sendBody("EMAIL", "a@b.c", "s", "b"));
        assertThat(notConfigured.getStatusCode().value()).isEqualTo(400);
        assertThat(json(notConfigured).get("detail").asText()).contains("not configured");

        assertThat(api.put("/api/v1/channels/EMAIL", key, obj().put("enabled", false).put("provider", "simulated-email"))
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> disabled = api.post("/api/v1/notifications", key, api.sendBody("EMAIL", "a@b.c", "s", "b"));
        assertThat(disabled.getStatusCode().value()).isEqualTo(400);
        assertThat(json(disabled).get("detail").asText()).contains("disabled");

        ResponseEntity<String> unknown = api.put("/api/v1/channels/EMAIL", key, obj().put("enabled", true).put("provider", "acme-mail"));
        assertThat(unknown.getStatusCode().value()).isEqualTo(400);
        assertThat(json(unknown).get("detail").asText()).contains("Unknown provider");

        ResponseEntity<String> providers = api.get("/api/v1/channels/providers", key);
        assertThat(providers.getStatusCode().value()).isEqualTo(200);
        assertThat(json(providers).get("EMAIL").toString()).contains("simulated-email");
        assertThat(json(providers).get("IN_APP").toString()).contains("inbox");
    }

    @Test
    void invalidEnumAndMalformedJsonAre400() {
        TenantHandle t = api.newTenant();
        assertThat(api.post("/api/v1/notifications", t.apiKey(), api.sendBody("FAX", "x", "s", "b")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.post("/api/v1/notifications", t.apiKey(), "{not json").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownNotificationIdIs404AndNonUuidIs400() {
        TenantHandle t = api.newTenant();
        assertThat(api.get("/api/v1/notifications/" + UUID.randomUUID(), t.apiKey()).getStatusCode().value()).isEqualTo(404);
        assertThat(api.get("/api/v1/notifications/not-a-uuid", t.apiKey()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void batchAcceptsIndependentlyAndReportsDuplicates() {
        TenantHandle t = api.newTenant();
        ObjectNode batch = obj();
        ArrayNode items = batch.putArray("notifications");
        items.add(api.sendBody("SMS", "+911", null, "one").put("idempotencyKey", "dup-1"));
        items.add(api.sendBody("SMS", "+912", null, "two").put("idempotencyKey", "dup-1"));
        items.add(api.sendBody("SMS", "+913", null, "three"));
        ResponseEntity<String> r = api.post("/api/v1/notifications/batch", t.apiKey(), batch);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        JsonNode body = json(r);
        assertThat(body.get("accepted").asInt()).isEqualTo(2);
        assertThat(body.get("duplicates").asInt()).isEqualTo(1);
        assertThat(body.get("notifications")).hasSize(3);
        assertThat(body.get("notifications").get(0).get("id").asText())
                .isEqualTo(body.get("notifications").get(1).get("id").asText());

        ObjectNode empty = obj();
        empty.putArray("notifications");
        assertThat(api.post("/api/v1/notifications/batch", t.apiKey(), empty).getStatusCode().value()).isEqualTo(400);

        ObjectNode tooMany = obj();
        ArrayNode many = tooMany.putArray("notifications");
        for (int i = 0; i < 1001; i++) {
            many.add(api.sendBody("SMS", "+91" + i, null, "b"));
        }
        assertThat(api.post("/api/v1/notifications/batch", t.apiKey(), tooMany).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void pagingParametersAreValidated() {
        TenantHandle t = api.newTenant();
        assertThat(api.get("/api/v1/notifications?size=500", t.apiKey()).getStatusCode().value()).isEqualTo(400);
        assertThat(api.get("/api/v1/notifications?page=-1", t.apiKey()).getStatusCode().value()).isEqualTo(400);
        assertThat(api.get("/api/v1/notifications?size=200", t.apiKey()).getStatusCode().value()).isEqualTo(200);
    }
}
