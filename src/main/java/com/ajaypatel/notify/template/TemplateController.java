package com.ajaypatel.notify.template;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.security.CurrentPrincipal;
import com.ajaypatel.notify.template.TemplateDtos.CreateTemplateRequest;
import com.ajaypatel.notify.template.TemplateDtos.RenderRequest;
import com.ajaypatel.notify.template.TemplateDtos.RenderResponse;
import com.ajaypatel.notify.template.TemplateDtos.TemplateResponse;
import com.ajaypatel.notify.template.TemplateDtos.TemplateVersionResponse;
import com.ajaypatel.notify.template.TemplateDtos.UpdateTemplateRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "Templates", description = "Tenant admin: versioned templates with {{variable}} placeholders")
public class TemplateController {
    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest req) {
        Template t = service.create(tenant(), req);
        return TemplateResponse.from(t, service.placeholders(t));
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return service.list(tenant()).stream().map(t -> TemplateResponse.from(t, service.placeholders(t))).toList();
    }

    @GetMapping("/{code}/{channel}")
    public TemplateResponse get(@PathVariable String code, @PathVariable Channel channel) {
        Template t = service.get(tenant(), code, channel);
        return TemplateResponse.from(t, service.placeholders(t));
    }

    @PutMapping("/{code}/{channel}")
    public TemplateResponse update(@PathVariable String code, @PathVariable Channel channel,
                                   @Valid @RequestBody UpdateTemplateRequest req) {
        Template t = service.update(tenant(), code, channel, req);
        return TemplateResponse.from(t, service.placeholders(t));
    }

    @DeleteMapping("/{code}/{channel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String code, @PathVariable Channel channel) {
        service.deactivate(tenant(), code, channel);
    }

    @GetMapping("/{code}/{channel}/versions")
    public List<TemplateVersionResponse> versions(@PathVariable String code, @PathVariable Channel channel) {
        return service.history(tenant(), code, channel).stream().map(TemplateVersionResponse::from).toList();
    }

    @PostMapping("/{code}/{channel}/render")
    public RenderResponse render(@PathVariable String code, @PathVariable Channel channel,
                                 @RequestBody(required = false) RenderRequest req) {
        return service.render(service.get(tenant(), code, channel), req == null ? null : req.variables());
    }

    private static UUID tenant() {
        return CurrentPrincipal.get().requireTenantId();
    }
}
