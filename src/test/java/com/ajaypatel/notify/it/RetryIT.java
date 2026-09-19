package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class RetryIT extends AbstractIntegrationTest {

    @Test
    void transientFailuresAreRetriedWithBackoffThenSucceed() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "SMS", "+91999+transient2");
        JsonNode d = api.awaitStatus(t, id, Duration.ofSeconds(20), "SENT");

        assertThat(d.get("notification").get("attemptCount").asInt()).isEqualTo(3);
        JsonNode attempts = d.get("attempts");
        assertThat(attempts).hasSize(3);
        assertThat(attempts.get(0).get("outcome").asText()).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempts.get(0).get("errorCode").asText()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempts.get(1).get("outcome").asText()).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempts.get(1).get("errorCode").asText()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempts.get(2).get("outcome").asText()).isEqualTo("SUCCESS");

        Instant a1 = Instant.parse(attempts.get(0).get("startedAt").asText());
        Instant a2 = Instant.parse(attempts.get(1).get("startedAt").asText());
        Instant a3 = Instant.parse(attempts.get(2).get("startedAt").asText());
        assertThat(a2).isAfter(a1);
        assertThat(a3).isAfter(a2);

        assertThat(eventTypes(d)).containsExactly("ACCEPTED", "RETRY_SCHEDULED", "RETRY_SCHEDULED", "SENT");
        d.get("events").forEach(e -> {
            if (e.get("type").asText().equals("RETRY_SCHEDULED")) {
                assertThat(e.get("detail").asText()).contains("retry in");
            }
        });
    }

    @Test
    void permanentFailureFailsOnFirstAttempt() {
        TenantHandle t = api.newTenant();
        UUID id = api.send(t, "PUSH", "device+bounce");
        JsonNode d = api.awaitStatus(t, id, "FAILED");

        JsonNode n = d.get("notification");
        assertThat(n.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(n.get("lastError").asText()).contains("HARD_BOUNCE");
        assertThat(n.get("failedAt")).isNotNull();
        assertThat(d.get("attempts")).hasSize(1);
        assertThat(d.get("attempts").get(0).get("outcome").asText()).isEqualTo("PERMANENT_FAILURE");
        assertThat(d.get("attempts").get(0).get("errorCode").asText()).isEqualTo("HARD_BOUNCE");
        assertThat(eventTypes(d)).containsExactly("ACCEPTED", "FAILED");
    }

    @Test
    void exhaustedAttemptsEndInFailed() {
        TenantHandle t = api.newTenant();
        api.configureChannel(t, "SMS", "simulated-sms", 2);
        UUID id = api.send(t, "SMS", "+91999+transient9");
        JsonNode d = api.awaitStatus(t, id, Duration.ofSeconds(20), "FAILED");

        JsonNode n = d.get("notification");
        assertThat(n.get("attemptCount").asInt()).isEqualTo(2);
        assertThat(n.get("maxAttempts").asInt()).isEqualTo(2);
        assertThat(n.get("lastError").asText()).contains("Exhausted 2 attempts");
        assertThat(d.get("attempts")).hasSize(2);
        d.get("attempts").forEach(a -> assertThat(a.get("outcome").asText()).isEqualTo("TRANSIENT_FAILURE"));
    }

    @Test
    void channelDisabledAfterAcceptFailsAtDispatch() {
        TenantHandle t = api.newTenant();
        poller.pause();
        try {
            UUID id = api.send(t, "EMAIL", "later@example.com");
            ResponseEntity<String> r = api.put("/api/v1/channels/EMAIL", t.apiKey(),
                    obj().put("enabled", false).put("provider", "simulated-email"));
            assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
            poller.resume();
            JsonNode d = api.awaitStatus(t, id, "FAILED");
            assertThat(d.get("notification").get("lastError").asText()).contains("CHANNEL_DISABLED");
            assertThat(d.get("notification").get("attemptCount").asInt()).isZero();
        } finally {
            poller.resume();
        }
    }

    private static List<String> eventTypes(JsonNode detail) {
        List<String> out = new ArrayList<>();
        detail.get("events").forEach(e -> out.add(e.get("type").asText()));
        return out;
    }
}
