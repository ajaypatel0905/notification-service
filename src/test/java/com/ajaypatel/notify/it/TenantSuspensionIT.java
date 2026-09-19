package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

class TenantSuspensionIT extends AbstractIntegrationTest {

    @Test
    void suspendedTenantIsNotDispatchedUntilReactivated() throws InterruptedException {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> suspended = api.post("/api/v1/admin/tenants/" + t.id() + "/suspend", api.adminKey(), null);
        assertThat(suspended.getStatusCode().value()).isEqualTo(200);
        assertThat(json(suspended).get("status").asText()).isEqualTo("SUSPENDED");

        ResponseEntity<String> r = api.post("/api/v1/notifications", t.apiKey(),
                api.sendBody("EMAIL", "paused@example.com", "S", "B"));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        UUID id = UUID.fromString(json(r).get("id").asText());
        assertThat(json(r).get("status").asText()).isEqualTo("QUEUED");

        Thread.sleep(1500);
        JsonNode still = api.detail(t, id).get("notification");
        assertThat(still.get("status").asText()).isEqualTo("QUEUED");
        assertThat(still.get("attemptCount").asInt()).isZero();

        ResponseEntity<String> activated = api.post("/api/v1/admin/tenants/" + t.id() + "/activate", api.adminKey(), null);
        assertThat(json(activated).get("status").asText()).isEqualTo("ACTIVE");
        api.awaitStatus(t, id, "SENT");
    }

    @Test
    void suspendingMidQueueRequeuesAlreadyClaimedWork() throws InterruptedException {
        poller.pause();
        TenantHandle t = api.newTenant();
        UUID id;
        try {
            id = api.send(t, "EMAIL", "mid@example.com");
            assertThat(api.post("/api/v1/admin/tenants/" + t.id() + "/suspend", api.adminKey(), null)
                    .getStatusCode().value()).isEqualTo(200);
        } finally {
            poller.resume();
        }
        Thread.sleep(1000);
        JsonNode n = api.detail(t, id).get("notification");
        assertThat(n.get("status").asText()).isEqualTo("QUEUED");
        assertThat(n.get("attemptCount").asInt()).isZero();

        api.post("/api/v1/admin/tenants/" + t.id() + "/activate", api.adminKey(), null);
        api.awaitStatus(t, id, "SENT");
    }

    @Test
    void suspendedTenantCanStillReadReportsAndNotifications() {
        TenantHandle t = api.newTenant();
        api.post("/api/v1/admin/tenants/" + t.id() + "/suspend", api.adminKey(), null);
        assertThat(api.get("/api/v1/notifications", t.apiKey()).getStatusCode().value()).isEqualTo(200);
        assertThat(api.get("/api/v1/reports/deliveries", t.apiKey()).getStatusCode().value()).isEqualTo(200);
    }
}
