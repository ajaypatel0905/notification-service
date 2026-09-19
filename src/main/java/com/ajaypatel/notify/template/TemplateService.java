package com.ajaypatel.notify.template;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.common.error.ConflictException;
import com.ajaypatel.notify.common.error.NotFoundException;
import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.template.TemplateDtos.CreateTemplateRequest;
import com.ajaypatel.notify.template.TemplateDtos.RenderResponse;
import com.ajaypatel.notify.template.TemplateDtos.UpdateTemplateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TemplateService {
    private final TemplateRepository templates;
    private final TemplateVersionRepository versions;
    private final TemplateRenderer renderer;
    private final Clock clock;

    public TemplateService(TemplateRepository templates, TemplateVersionRepository versions,
                           TemplateRenderer renderer, Clock clock) {
        this.templates = templates;
        this.versions = versions;
        this.renderer = renderer;
        this.clock = clock;
    }

    @Transactional
    public Template create(UUID tenantId, CreateTemplateRequest req) {
        templates.findByTenantIdAndCodeAndChannel(tenantId, req.code(), req.channel()).ifPresent(t -> {
            throw new ConflictException("Template already exists: " + req.code() + "/" + req.channel());
        });
        validateSubject(req.channel(), req.subject());
        Instant now = clock.instant();
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setCode(req.code());
        t.setChannel(req.channel());
        t.setName(req.name());
        t.setSubject(req.subject());
        t.setBody(req.body());
        t.setVersion(1);
        t.setActive(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        templates.save(t);
        snapshot(t, now);
        return t;
    }

    /** Every content change is a new immutable version; notifications record which version rendered them. */
    @Transactional
    public Template update(UUID tenantId, String code, Channel channel, UpdateTemplateRequest req) {
        Template t = get(tenantId, code, channel);
        validateSubject(channel, req.subject());
        Instant now = clock.instant();
        boolean contentChanged = !req.body().equals(t.getBody())
                || (req.subject() == null ? t.getSubject() != null : !req.subject().equals(t.getSubject()));
        if (req.name() != null) {
            t.setName(req.name());
        }
        if (contentChanged) {
            t.setSubject(req.subject());
            t.setBody(req.body());
            t.setVersion(t.getVersion() + 1);
            snapshot(t, now);
        }
        t.setActive(true);
        t.setUpdatedAt(now);
        return t;
    }

    @Transactional
    public void deactivate(UUID tenantId, String code, Channel channel) {
        Template t = get(tenantId, code, channel);
        t.setActive(false);
        t.setUpdatedAt(clock.instant());
    }

    @Transactional(readOnly = true)
    public Template get(UUID tenantId, String code, Channel channel) {
        return templates.findByTenantIdAndCodeAndChannel(tenantId, code, channel)
                .orElseThrow(() -> NotFoundException.of("Template", code + "/" + channel));
    }

    @Transactional(readOnly = true)
    public Template getActive(UUID tenantId, String code, Channel channel) {
        Template t = get(tenantId, code, channel);
        if (!t.isActive()) {
            throw new ValidationException("Template is inactive: " + code + "/" + channel);
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<Template> list(UUID tenantId) {
        return templates.findByTenantIdOrderByCodeAscChannelAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TemplateVersion> history(UUID tenantId, String code, Channel channel) {
        return versions.findByTemplateIdOrderByVersionDesc(get(tenantId, code, channel).getId());
    }

    public RenderResponse render(Template t, Map<String, Object> variables) {
        return new RenderResponse(renderer.render(t.getSubject(), variables), renderer.render(t.getBody(), variables));
    }

    public java.util.Set<String> placeholders(Template t) {
        var names = renderer.placeholders(t.getSubject());
        names.addAll(renderer.placeholders(t.getBody()));
        return names;
    }

    private void snapshot(Template t, Instant now) {
        TemplateVersion v = new TemplateVersion();
        v.setId(UUID.randomUUID());
        v.setTemplateId(t.getId());
        v.setVersion(t.getVersion());
        v.setSubject(t.getSubject());
        v.setBody(t.getBody());
        v.setCreatedAt(now);
        versions.save(v);
    }

    private static void validateSubject(Channel channel, String subject) {
        if (subject != null && !subject.isBlank() && !channel.supportsSubject()) {
            throw new ValidationException("Channel " + channel + " does not support a subject");
        }
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw new ValidationException("EMAIL templates require a subject");
        }
    }
}
