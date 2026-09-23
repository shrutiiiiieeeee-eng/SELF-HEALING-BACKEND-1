package com.selfhealing.monitor_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TrafficSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulatorService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong fallbackRequests = new AtomicLong(0);

    private ScheduledExecutorService executorService;
    private int virtualUsers = 10;
    private long simulationStartTime = 0;

    private static final String[] GATEWAY_ENDPOINTS = {
        "http://localhost:8085/gateway/service-a/health-check",
        "http://localhost:8085/gateway/service-b/process",
        "http://localhost:8085/gateway/service-c/process"
    };

    public synchronized void start(int users) {
        if (running.get()) {
            stop();
        }
        this.virtualUsers = Math.max(1, Math.min(users, 100));
        this.running.set(true);
        this.simulationStartTime = System.currentTimeMillis();
        this.totalRequests.set(0);
        this.successRequests.set(0);
        this.fallbackRequests.set(0);

        this.executorService = Executors.newScheduledThreadPool(Math.min(virtualUsers, 20));

        // Schedule user request loops
        for (int i = 0; i < virtualUsers; i++) {
            int delay = ThreadLocalRandom.current().nextInt(100, 800);
            executorService.scheduleWithFixedDelay(() -> {
                if (!running.get()) return;
                try {
                    String url = GATEWAY_ENDPOINTS[ThreadLocalRandom.current().nextInt(GATEWAY_ENDPOINTS.length)];
                    totalRequests.incrementAndGet();
                    var response = restTemplate.getForObject(url, Map.class);
                    if (response != null && "FALLBACK".equalsIgnoreCase(String.valueOf(response.get("status")))) {
                        fallbackRequests.incrementAndGet();
                    } else {
                        successRequests.incrementAndGet();
                    }
                } catch (Exception e) {
                    fallbackRequests.incrementAndGet();
                }
            }, delay, 500, TimeUnit.MILLISECONDS);
        }

        log.info("TRAFFIC SIMULATOR: Started with {} virtual users.", virtualUsers);
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        log.info("TRAFFIC SIMULATOR: Stopped. Total requests: {}", totalRequests.get());
    }

    public Map<String, Object> getStatus() {
        long elapsedSec = simulationStartTime > 0 ? Math.max(1, (System.currentTimeMillis() - simulationStartTime) / 1000) : 1;
        long total = totalRequests.get();
        double rps = running.get() ? (double) total / elapsedSec : 0.0;

        return Map.of(
            "running", running.get(),
            "virtualUsers", virtualUsers,
            "totalRequests", total,
            "successRequests", successRequests.get(),
            "fallbackRequests", fallbackRequests.get(),
            "requestsPerSecond", Math.round(rps * 10.0) / 10.0,
            "uptimeSeconds", running.get() ? elapsedSec : 0
        );
    }
}
