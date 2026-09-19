package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityIT extends AbstractIntegrationTest {

    @Test
    void missingKeyIs401WithProblemDetail() {
        ResponseEntity<String> r = api.get("/api/v1/templates", null);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(json(r).get("title").asText()).isEqualTo("Unauthorized");
    }

    @Test
    void unknownKeyIs401() {
        assertThat(api.get("/api/v1/templates", "nk_ta_doesnotexist").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tenantKeyCannotReachAdminApis() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.get("/api/v1/admin/tenants", t.apiKey());
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(json(r).get("title").asText()).isEqualTo("Forbidden");
    }

    @Test
    void adminKeyCannotReachTenantApis() {
        assertThat(api.get("/api/v1/templates", api.adminKey()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void tenantsCannotSeeEachOthersNotifications() {
        TenantHandle a = api.newTenant();
        TenantHandle b = api.newTenant();
        UUID id = api.send(a, "EMAIL", "a@example.com");
        assertThat(api.get("/api/v1/notifications/" + id, b.apiKey()).getStatusCode().value()).isEqualTo(404);
        assertThat(api.post("/api/v1/notifications/" + id + "/cancel", b.apiKey(), null).getStatusCode().value()).isEqualTo(404);
        assertThat(json(api.get("/api/v1/notifications", b.apiKey())).get("totalItems").asLong()).isZero();
    }

    @Test
    void revokedKeyStopsWorkingImmediately() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> second = api.post("/api/v1/admin/tenants/" + t.id() + "/api-keys", api.adminKey(), obj().put("label", "second"));
        assertThat(second.getStatusCode().value()).isEqualTo(201);

        JsonNode keys = json(api.get("/api/v1/admin/tenants/" + t.id() + "/api-keys", api.adminKey()));
        String prefix = t.apiKey().substring(0, 10);
        String keyId = null;
        for (JsonNode k : keys) {
            if (k.get("prefix").asText().equals(prefix)) {
                keyId = k.get("id").asText();
            }
        }
        assertThat(keyId).isNotNull();

        assertThat(api.get("/api/v1/templates", t.apiKey()).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> del = api.call(HttpMethod.DELETE, "/api/v1/admin/tenants/" + t.id() + "/api-keys/" + keyId, api.adminKey(), null, null);
        assertThat(del.getStatusCode().value()).isEqualTo(204);
        assertThat(api.get("/api/v1/templates", t.apiKey()).getStatusCode().value()).isEqualTo(401);
        assertThat(api.get("/api/v1/templates", json(second).get("apiKey").asText()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void apiKeysAreOnlyReturnedOnceAndStoredHashed() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> r = api.post("/api/v1/admin/tenants/" + t.id() + "/api-keys", api.adminKey(), obj().put("label", "once"));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        String raw = json(r).get("apiKey").asText();
        assertThat(raw).startsWith("nk_ta_");
        UUID keyId = UUID.fromString(json(r).get("id").asText());
        String hash = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id = ?", String.class, keyId);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(raw);

        JsonNode list = json(api.get("/api/v1/admin/tenants/" + t.id() + "/api-keys", api.adminKey()));
        assertThat(list.isArray()).isTrue();
        for (JsonNode k : list) {
            assertThat(k.has("apiKey")).isFalse();
        }
    }

    @Test
    void publicEndpointsNeedNoKey() {
        assertThat(api.get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> docs = api.get("/v3/api-docs", null);
        assertThat(docs.getStatusCode().value()).isEqualTo(200);
        assertThat(docs.getBody()).contains("Notification Service");
    }
}
