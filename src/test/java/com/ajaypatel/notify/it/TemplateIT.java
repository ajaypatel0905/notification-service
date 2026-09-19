package com.ajaypatel.notify.it;

import com.ajaypatel.notify.support.AbstractIntegrationTest;
import com.ajaypatel.notify.support.ApiClient.TenantHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ajaypatel.notify.support.ApiClient.json;
import static com.ajaypatel.notify.support.ApiClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

class TemplateIT extends AbstractIntegrationTest {

    private ResponseEntity<String> create(TenantHandle t, String code, String channel, String subject, String body) {
        ObjectNode n = obj().put("code", code).put("channel", channel).put("name", "Template " + code).put("body", body);
        if (subject != null) {
            n.put("subject", subject);
        }
        return api.post("/api/v1/templates", t.apiKey(), n);
    }

    private static List<String> placeholders(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.get("placeholders").forEach(p -> out.add(p.asText()));
        return out;
    }

    @Test
    void createUpdateCreatesNewVersionAndHistory() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> c = create(t, "tpl-a", "EMAIL", "S {{x}}", "B {{y}}");
        assertThat(c.getStatusCode().value()).isEqualTo(201);
        assertThat(json(c).get("version").asInt()).isEqualTo(1);
        assertThat(placeholders(json(c))).containsExactly("x", "y");

        ResponseEntity<String> u = api.put("/api/v1/templates/tpl-a/EMAIL", t.apiKey(),
                obj().put("name", "renamed").put("subject", "S {{x}}").put("body", "B2 {{y}} {{z}}"));
        assertThat(u.getStatusCode().value()).isEqualTo(200);
        assertThat(json(u).get("version").asInt()).isEqualTo(2);
        assertThat(json(u).get("name").asText()).isEqualTo("renamed");
        assertThat(placeholders(json(u))).containsExactly("x", "y", "z");

        ResponseEntity<String> u2 = api.put("/api/v1/templates/tpl-a/EMAIL", t.apiKey(),
                obj().put("name", "renamed-again").put("subject", "S {{x}}").put("body", "B2 {{y}} {{z}}"));
        assertThat(json(u2).get("version").asInt()).isEqualTo(2);
        assertThat(json(u2).get("name").asText()).isEqualTo("renamed-again");

        JsonNode versions = json(api.get("/api/v1/templates/tpl-a/EMAIL/versions", t.apiKey()));
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("body").asText()).isEqualTo("B {{y}}");
    }

    @Test
    void notificationRecordsTheTemplateVersionItWasRenderedWith() {
        TenantHandle t = api.newTenant();
        assertThat(create(t, "ver", "SMS", null, "v1 {{n}}").getStatusCode().value()).isEqualTo(201);

        ObjectNode send = obj().put("channel", "SMS").put("recipient", "+911111111111").put("templateCode", "ver");
        send.putObject("variables").put("n", 1);
        ResponseEntity<String> first = api.post("/api/v1/notifications", t.apiKey(), send);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(json(first).get("body").asText()).isEqualTo("v1 1");
        assertThat(json(first).get("templateVersion").asInt()).isEqualTo(1);
        UUID firstId = UUID.fromString(json(first).get("id").asText());

        assertThat(api.put("/api/v1/templates/ver/SMS", t.apiKey(), obj().put("body", "v2 {{n}}")).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = api.post("/api/v1/notifications", t.apiKey(), send);
        assertThat(json(second).get("body").asText()).isEqualTo("v2 1");
        assertThat(json(second).get("templateVersion").asInt()).isEqualTo(2);

        JsonNode firstDetail = api.detail(t, firstId).get("notification");
        assertThat(firstDetail.get("templateVersion").asInt()).isEqualTo(1);
        assertThat(firstDetail.get("body").asText()).isEqualTo("v1 1");
    }

    @Test
    void renderPreviewAndMissingVariables() {
        TenantHandle t = api.newTenant();
        create(t, "prev", "EMAIL", "Hello {{name}}", "Plan {{plan}}");

        ObjectNode vars = obj();
        vars.putObject("variables").put("name", "Ann").put("plan", "Pro");
        ResponseEntity<String> ok = api.post("/api/v1/templates/prev/EMAIL/render", t.apiKey(), vars);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(json(ok).get("subject").asText()).isEqualTo("Hello Ann");
        assertThat(json(ok).get("body").asText()).isEqualTo("Plan Pro");

        ObjectNode partial = obj();
        partial.putObject("variables").put("name", "Ann");
        ResponseEntity<String> missing = api.post("/api/v1/templates/prev/EMAIL/render", t.apiKey(), partial);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(json(missing).get("detail").asText()).contains("Missing template variables");

        assertThat(api.post("/api/v1/templates/prev/EMAIL/render", t.apiKey(), null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deactivatedTemplateCannotBeUsedButStillReadable() {
        TenantHandle t = api.newTenant();
        create(t, "off", "PUSH", "Hi", "Body");
        ResponseEntity<String> del = api.call(HttpMethod.DELETE, "/api/v1/templates/off/PUSH", t.apiKey(), null, null);
        assertThat(del.getStatusCode().value()).isEqualTo(204);

        JsonNode got = json(api.get("/api/v1/templates/off/PUSH", t.apiKey()));
        assertThat(got.get("active").asBoolean()).isFalse();

        ObjectNode send = obj().put("channel", "PUSH").put("recipient", "dev-1").put("templateCode", "off");
        ResponseEntity<String> rejected = api.post("/api/v1/notifications", t.apiKey(), send);
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(json(rejected).get("detail").asText()).contains("inactive");

        ResponseEntity<String> re = api.put("/api/v1/templates/off/PUSH", t.apiKey(), obj().put("subject", "Hi").put("body", "Body"));
        assertThat(json(re).get("active").asBoolean()).isTrue();
        assertThat(api.post("/api/v1/notifications", t.apiKey(), send).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void channelSubjectRules() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> noSubject = create(t, "e1", "EMAIL", null, "Body");
        assertThat(noSubject.getStatusCode().value()).isEqualTo(400);
        assertThat(json(noSubject).get("detail").asText()).contains("require a subject");

        ResponseEntity<String> smsSubject = create(t, "s1", "SMS", "Subj", "Body");
        assertThat(smsSubject.getStatusCode().value()).isEqualTo(400);
        assertThat(json(smsSubject).get("detail").asText()).contains("does not support a subject");

        assertThat(create(t, "s1", "SMS", null, "Body").getStatusCode().value()).isEqualTo(201);
        assertThat(create(t, "s1", "SMS", null, "Body again").getStatusCode().value()).isEqualTo(409);
        assertThat(create(t, "s1", "PUSH", "Subj", "Body").getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void templateCodeValidation() {
        TenantHandle t = api.newTenant();
        ResponseEntity<String> bad = create(t, "bad code!", "SMS", null, "Body");
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(json(bad).get("fields").has("code")).isTrue();

        ResponseEntity<String> empty = create(t, "ok-code", "SMS", null, "");
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void templatesAreTenantScoped() {
        TenantHandle a = api.newTenant();
        TenantHandle b = api.newTenant();
        assertThat(create(a, "shared", "EMAIL", "S", "B").getStatusCode().value()).isEqualTo(201);
        assertThat(api.get("/api/v1/templates/shared/EMAIL", b.apiKey()).getStatusCode().value()).isEqualTo(404);
        assertThat(json(api.get("/api/v1/templates", b.apiKey()))).isEmpty();
    }
}
