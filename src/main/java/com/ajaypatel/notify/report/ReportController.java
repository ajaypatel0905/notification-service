package com.ajaypatel.notify.report;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.report.ReportService.DeliverySummary;
import com.ajaypatel.notify.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Tenant admin: delivery outcomes, latency and top errors")
public class ReportController {
    private final ReportService service;
    private final Clock clock;

    public ReportController(ReportService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/deliveries")
    public DeliverySummary deliveries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Channel channel) {
        Instant end = to == null ? clock.instant().plusSeconds(1) : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (!start.isBefore(end)) {
            throw new ValidationException("from must be before to");
        }
        if (Duration.between(start, end).toDays() > 92) {
            throw new ValidationException("Report window cannot exceed 92 days");
        }
        return service.summary(CurrentPrincipal.get().requireTenantId(), start, end, channel);
    }
}
