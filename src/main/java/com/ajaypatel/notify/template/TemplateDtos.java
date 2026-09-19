package com.ajaypatel.notify.template;

import com.ajaypatel.notify.channel.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TemplateDtos {
    private TemplateDtos() {}

    public record CreateTemplateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_-]*") String code,
            @NotNull Channel channel,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 998) String subject,
            @NotBlank @Size(max = 100_000) String body
    ) {}

    public record UpdateTemplateRequest(
            @Size(max = 200) String name,
            @Size(max = 998) String subject,
            @NotBlank @Size(max = 100_000) String body
    ) {}

    public record RenderRequest(Map<String, Object> variables) {}

    public record RenderResponse(String subject, String body) {}

    public record TemplateResponse(UUID id, String code, Channel channel, String name, String subject, String body,
                                   int version, boolean active, Set<String> placeholders,
                                   Instant createdAt, Instant updatedAt) {
        public static TemplateResponse from(Template t, Set<String> placeholders) {
            return new TemplateResponse(t.getId(), t.getCode(), t.getChannel(), t.getName(), t.getSubject(),
                    t.getBody(), t.getVersion(), t.isActive(), placeholders, t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record TemplateVersionResponse(int version, String subject, String body, Instant createdAt) {
        public static TemplateVersionResponse from(TemplateVersion v) {
            return new TemplateVersionResponse(v.getVersion(), v.getSubject(), v.getBody(), v.getCreatedAt());
        }
    }
}
