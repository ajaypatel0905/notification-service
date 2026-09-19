package com.ajaypatel.notify.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Thin JSON client over TestRestTemplate with helpers for the setup every IT needs. */
public class ApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final TestRestTemplate rest;
    private final String adminKey;

    public ApiClient(TestRestTemplate rest, String adminKey) {
        this.rest = rest;
        this.adminKey = adminKey;
    }

    public record TenantHandle(UUID id, String slug, String apiKey) {}

    public ResponseEntity<String> call(HttpMethod method, String path, String apiKey, Object body, Map<String, String> extraHeaders) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            h.set("X-API-Key", apiKey);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(h::set);
        }
        String json;
        try {
            json = body == null ? null : (body instanceof String s ? s : MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rest.exchange(path, method, new HttpEntity<>(json, h), String.class);
    }

    public ResponseEntity<String> get(String path, String apiKey) {
        return call(HttpMethod.GET, path, apiKey, null, null);
    }

    public ResponseEntity<String> post(String path, String apiKey, Object body) {
        return call(HttpMethod.POST, path, apiKey, body, null);
    }

    public ResponseEntity<String> put(String path, String apiKey, Object body) {
        return call(HttpMethod.PUT, path, apiKey, body, null);
    }

    public static JsonNode json(ResponseEntity<String> r) {
        try {
            return r.getBody() == null ? MAPPER.nullNode() : MAPPER.readTree(r.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    /** Creates a tenant with all four channels enabled and one key. Each test gets its own tenant for isolation. */
    public TenantHandle newTenant(Integer ratePerSecond, Integer burst) {
        String slug = "t" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
        ObjectNode body = obj().put("slug", slug).put("name", "Tenant " + slug);
        if (ratePerSecond != null) {
            body.put("rateLimitPerSecond", ratePerSecond);
        }
        if (burst != null) {
            body.put("rateLimitBurst", burst);
        }
        ResponseEntity<String> created = post("/api/v1/admin/tenants", adminKey, body);
        if (created.getStatusCode().value() != 201) {
            throw new IllegalStateException("tenant create failed: " + created.getBody());
        }
        UUID id = UUID.fromString(json(created).get("id").asText());
        ResponseEntity<String> key = post("/api/v1/admin/tenants/" + id + "/api-keys", adminKey, obj().put("label", "test"));
        String apiKey = json(key).get("apiKey").asText();
        TenantHandle t = new TenantHandle(id, slug, apiKey);
        configureChannel(t, "EMAIL", "simulated-email", null);
        configureChannel(t, "SMS", "simulated-sms", null);
        configureChannel(t, "PUSH", "simulated-push", null);
        configureChannel(t, "IN_APP", "inbox", null);
        return t;
    }

    public TenantHandle newTenant() {
        return newTenant(null, null);
    }

    public void configureChannel(TenantHandle t, String channel, String provider, Integer maxAttempts) {
        ObjectNode body = obj().put("enabled", true).put("provider", provider);
        if (maxAttempts != null) {
            body.put("maxAttempts", maxAttempts);
        }
        ResponseEntity<String> r = put("/api/v1/channels/" + channel, t.apiKey(), body);
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("channel config failed: " + r.getBody());
        }
    }

    public ObjectNode sendBody(String channel, String recipient, String subject, String body) {
        ObjectNode n = obj().put("channel", channel).put("recipient", recipient).put("body", body);
        if (subject != null) {
            n.put("subject", subject);
        }
        return n;
    }

    public UUID send(TenantHandle t, String channel, String recipient) {
        ResponseEntity<String> r = post("/api/v1/notifications", t.apiKey(),
                sendBody(channel, recipient, channel.equals("SMS") ? null : "Subject", "Body for " + recipient));
        if (r.getStatusCode().value() != 202) {
            throw new IllegalStateException("send failed: " + r.getStatusCode() + " " + r.getBody());
        }
        return UUID.fromString(json(r).get("id").asText());
    }

    public JsonNode detail(TenantHandle t, UUID id) {
        return json(get("/api/v1/notifications/" + id, t.apiKey()));
    }

    public String status(TenantHandle t, UUID id) {
        return detail(t, id).get("notification").get("status").asText();
    }

    public JsonNode awaitStatus(TenantHandle t, UUID id, Duration timeout, String... anyOf) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            String s = status(t, id);
            for (String want : anyOf) {
                if (want.equals(s)) {
                    return;
                }
            }
            throw new AssertionError("status " + s + " not in " + String.join("/", anyOf));
        });
        return detail(t, id);
    }

    public JsonNode awaitStatus(TenantHandle t, UUID id, String... anyOf) {
        return awaitStatus(t, id, Duration.ofSeconds(15), anyOf);
    }

    public String adminKey() {
        return adminKey;
    }
}
