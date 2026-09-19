package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class AdminIT extends AbstractIntegrationTest {

    @Test
    void createTenantValidatesSlugAndUniqueness() {
        ResponseEntity<String> bad = api.post("/api/v1/admin/tenants", api.adminKey(), obj().put("slug", "Bad Slug").put("name", "x"));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(json(bad).get("fields").has("slug")).isTrue();

        String slug = "uniq-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> ok = api.post("/api/v1/admin/tenants", api.adminKey(), obj().put("slug", slug).put("name", "Uniq"));
        assertThat(ok.getStatusCode().value()).isEqualTo(201);
        assertThat(json(ok).get("status").asText()).isEqualTo("ACTIVE");

        assertThat(api.post("/api/v1/admin/tenants", api.adminKey(), obj().put("slug", slug).put("name", "Again"))
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void updateSettingsAndEnforceCaps() {
        ResponseEntity<String> before = api.get("/api/v1/admin/settings", api.adminKey());
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        JsonNode original = json(before);
        for (String f : new String[]{"defaultRateLimitPerSecond", "defaultRateLimitBurst", "defaultMaxAttempts", "maxRateLimitPerSecond"}) {
            assertThat(original.has(f)).as(f).isTrue();
        }
        try {
            ResponseEntity<String> tooHigh = api.put("/api/v1/admin/settings", api.adminKey(), obj()
                    .put("defaultRateLimitPerSecond", 5000).put("defaultRateLimitBurst", 10)
                    .put("defaultMaxAttempts", 3).put("maxRateLimitPerSecond", 1000));
            assertThat(tooHigh.getStatusCode().value()).isEqualTo(400);

            ResponseEntity<String> ok = api.put("/api/v1/admin/settings", api.adminKey(), obj()
                    .put("defaultRateLimitPerSecond", 60).put("defaultRateLimitBurst", 120)
                    .put("defaultMaxAttempts", 5).put("maxRateLimitPerSecond", 1000));
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            JsonNode after = json(api.get("/api/v1/admin/settings", api.adminKey()));
            assertThat(after.get("defaultRateLimitPerSecond").asInt()).isEqualTo(60);
            assertThat(after.get("defaultRateLimitBurst").asInt()).isEqualTo(120);
        } finally {
            ResponseEntity<String> restore = api.put("/api/v1/admin/settings", api.adminKey(), original);
            assertThat(restore.getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void tenantRateAboveMaxIsRejected() {
        ResponseEntity<String> r = api.post("/api/v1/admin/tenants", api.adminKey(),
                obj().put("slug", "fast-" + UUID.randomUUID().toString().substring(0, 8)).put("name", "Fast").put("rateLimitPerSecond", 99999));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(json(r).get("detail").asText()).contains("exceeds platform maximum");
    }

    @Test
    void suspendActivateRoundTrip() {
        TenantHandle t = api.newTenant();
        JsonNode suspended = json(api.post("/api/v1/admin/tenants/" + t.id() + "/suspend", api.adminKey(), null));
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        JsonNode active = json(api.post("/api/v1/admin/tenants/" + t.id() + "/activate", api.adminKey(), null));
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode list = json(api.get("/api/v1/admin/tenants", api.adminKey()));
        boolean found = false;
        for (JsonNode n : list) {
            found |= n.get("id").asText().equals(t.id().toString());
        }
        assertThat(found).isTrue();
        assertThat(api.get("/api/v1/admin/tenants/" + UUID.randomUUID(), api.adminKey()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void dispatchStatsAndPauseResume() {
        try {
            ResponseEntity<String> stats = api.get("/api/v1/admin/dispatch/stats", api.adminKey());
            assertThat(stats.getStatusCode().value()).isEqualTo(200);
            assertThat(json(stats).get("pools").get("EMAIL").get("threads").asInt()).isEqualTo(8);
            assertThat(json(stats).get("poller").get("enabled").asBoolean()).isTrue();

            assertThat(json(api.post("/api/v1/admin/dispatch/pause", api.adminKey(), null)).get("paused").asBoolean()).isTrue();
            assertThat(json(api.get("/api/v1/admin/dispatch/stats", api.adminKey())).get("poller").get("paused").asBoolean()).isTrue();
            assertThat(json(api.post("/api/v1/admin/dispatch/resume", api.adminKey(), null)).get("paused").asBoolean()).isFalse();
        } finally {
            poller.resume();
        }
    }

    @Test
    void maxAttemptsDefaultComesFromSettingsWhenChannelHasNone() {
        TenantHandle t = api.newTenant();
        int defaultMax = json(api.get("/api/v1/admin/settings", api.adminKey())).get("defaultMaxAttempts").asInt();
        ResponseEntity<String> first = api.post("/api/v1/notifications", t.apiKey(), api.sendBody("EMAIL", "a@b.c", "s", "b"));
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(json(first).get("maxAttempts").asInt()).isEqualTo(defaultMax);

        api.configureChannel(t, "EMAIL", "simulated-email", 2);
        ResponseEntity<String> second = api.post("/api/v1/notifications", t.apiKey(), api.sendBody("EMAIL", "c@b.c", "s", "b"));
        assertThat(json(second).get("maxAttempts").asInt()).isEqualTo(2);
    }
}
