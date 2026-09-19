package com.ajaypatel.notify.report;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.notification.DeliveryAttempt;
import com.ajaypatel.notify.notification.DeliveryAttemptRepository;
import com.ajaypatel.notify.notification.NotificationStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportService {
    private final DeliveryAttemptRepository attempts;
    private final EntityManager em;

    public ReportService(DeliveryAttemptRepository attempts, EntityManager em) {
        this.attempts = attempts;
        this.em = em;
    }

    public record LatencyStats(long count, long p50Ms, long p95Ms, long maxMs, double avgMs) {}

    public record DeliverySummary(Instant from, Instant to, Channel channel,
                                  Map<NotificationStatus, Long> byStatus,
                                  Map<DeliveryAttempt.Outcome, Long> attemptOutcomes,
                                  long total, long finalised, double successRate, double avgAttemptsPerSuccess,
                                  LatencyStats acceptToSent, Map<String, Long> topErrors) {}

    @Transactional(readOnly = true)
    public DeliverySummary summary(UUID tenantId, Instant from, Instant to, Channel channel) {
        Map<NotificationStatus, Long> byStatus = new EnumMap<>(NotificationStatus.class);
        String channelClause = channel == null ? "" : " AND channel = :channel";

        var statusQuery = em.createNativeQuery("""
                SELECT status, COUNT(*) FROM notifications
                WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
                """ + channelClause + " GROUP BY status")
                .setParameter("tenantId", tenantId).setParameter("from", from).setParameter("to", to);
        if (channel != null) {
            statusQuery.setParameter("channel", channel.name());
        }
        for (Object row : statusQuery.getResultList()) {
            Object[] r = (Object[]) row;
            byStatus.put(NotificationStatus.valueOf((String) r[0]), ((Number) r[1]).longValue());
        }

        Map<DeliveryAttempt.Outcome, Long> outcomes = new EnumMap<>(DeliveryAttempt.Outcome.class);
        var outcomeQuery = em.createNativeQuery("""
                SELECT a.outcome, COUNT(*) FROM delivery_attempts a JOIN notifications n ON n.id = a.notification_id
                WHERE a.tenant_id = :tenantId AND a.started_at >= :from AND a.started_at < :to
                """ + (channel == null ? "" : " AND n.channel = :channel") + " GROUP BY a.outcome")
                .setParameter("tenantId", tenantId).setParameter("from", from).setParameter("to", to);
        if (channel != null) {
            outcomeQuery.setParameter("channel", channel.name());
        }
        for (Object row : outcomeQuery.getResultList()) {
            Object[] r = (Object[]) row;
            outcomes.put(DeliveryAttempt.Outcome.valueOf((String) r[0]), ((Number) r[1]).longValue());
        }

        var latencyQuery = em.createNativeQuery("""
                SELECT EXTRACT(EPOCH FROM (sent_at - created_at)) * 1000, attempt_count FROM notifications
                WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to AND sent_at IS NOT NULL
                """ + channelClause + " ORDER BY created_at DESC LIMIT 10000")
                .setParameter("tenantId", tenantId).setParameter("from", from).setParameter("to", to);
        if (channel != null) {
            latencyQuery.setParameter("channel", channel.name());
        }
        List<Long> latencies = new ArrayList<>();
        long attemptsSum = 0;
        for (Object row : latencyQuery.getResultList()) {
            Object[] r = (Object[]) row;
            latencies.add(((Number) r[0]).longValue());
            attemptsSum += ((Number) r[1]).longValue();
        }

        var errorQuery = em.createNativeQuery("""
                SELECT COALESCE(error_code, 'UNKNOWN'), COUNT(*) FROM delivery_attempts
                WHERE tenant_id = :tenantId AND started_at >= :from AND started_at < :to AND outcome <> 'SUCCESS'
                GROUP BY error_code ORDER BY COUNT(*) DESC LIMIT 10
                """).setParameter("tenantId", tenantId).setParameter("from", from).setParameter("to", to);
        Map<String, Long> topErrors = new LinkedHashMap<>();
        for (Object row : errorQuery.getResultList()) {
            Object[] r = (Object[]) row;
            topErrors.put((String) r[0], ((Number) r[1]).longValue());
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long ok = byStatus.getOrDefault(NotificationStatus.SENT, 0L) + byStatus.getOrDefault(NotificationStatus.DELIVERED, 0L);
        long failed = byStatus.getOrDefault(NotificationStatus.FAILED, 0L);
        long finalised = ok + failed;
        double successRate = finalised == 0 ? 0 : (double) ok / finalised;
        double avgAttempts = latencies.isEmpty() ? 0 : (double) attemptsSum / latencies.size();

        return new DeliverySummary(from, to, channel, byStatus, outcomes, total, finalised, round(successRate),
                round(avgAttempts), latency(latencies), topErrors);
    }

    private static LatencyStats latency(List<Long> values) {
        if (values.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0, 0);
        }
        Collections.sort(values);
        long p50 = values.get(nearestRank(0.50, values.size()));
        long p95 = values.get(nearestRank(0.95, values.size()));
        double avg = values.stream().mapToLong(Long::longValue).average().orElse(0);
        return new LatencyStats(values.size(), p50, p95, values.get(values.size() - 1), round(avg));
    }

    private static int nearestRank(double p, int n) {
        return Math.min(n - 1, Math.max(0, (int) Math.ceil(p * n) - 1));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
