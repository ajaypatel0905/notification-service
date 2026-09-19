package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.channel.Channel;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One bounded pool per channel so a slow SMS vendor cannot starve email. Capacity is threads + queue;
 * the poller only claims what fits, so {@link RejectedExecutionException} is a bug signal, not flow control.
 */
@Component
public class WorkerPools {
    private final Map<Channel, ThreadPoolExecutor> pools = new EnumMap<>(Channel.class);
    private final int queueCapacity;

    public WorkerPools(DispatchProperties props) {
        this.queueCapacity = props.queueCapacityPerPool();
        for (Channel c : Channel.values()) {
            int size = props.poolSize(c);
            AtomicInteger seq = new AtomicInteger();
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "dispatch-" + c.name().toLowerCase() + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
            pools.put(c, new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity), tf, new ThreadPoolExecutor.AbortPolicy()));
        }
    }

    public int freeCapacity(Channel c) {
        ThreadPoolExecutor p = pools.get(c);
        int idleThreads = Math.max(0, p.getMaximumPoolSize() - p.getActiveCount());
        return idleThreads + p.getQueue().remainingCapacity();
    }

    public int totalFreeCapacity() {
        int sum = 0;
        for (Channel c : Channel.values()) {
            sum += freeCapacity(c);
        }
        return sum;
    }

    public void submit(Channel c, Runnable task) {
        pools.get(c).execute(task);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        pools.forEach((c, p) -> out.put(c.name(), Map.of(
                "threads", p.getMaximumPoolSize(),
                "active", p.getActiveCount(),
                "queued", p.getQueue().size(),
                "queueCapacity", queueCapacity,
                "completed", p.getCompletedTaskCount())));
        return out;
    }

    @PreDestroy
    void shutdown() {
        pools.values().forEach(ThreadPoolExecutor::shutdown);
        pools.values().forEach(p -> {
            try {
                p.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
