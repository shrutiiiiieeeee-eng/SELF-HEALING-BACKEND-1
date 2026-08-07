package com.selfhealing.monitor_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@EnableScheduling
public class FailureDetector {

    private static final Logger log =
            LoggerFactory.getLogger(FailureDetector.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${monitored.services}")
    private String[] serviceUrls;

    @Autowired
    private HealingEngine healingEngine;

    @Scheduled(fixedDelay = 10000)
    public void detect() {

        for (String url : serviceUrls) {

            String name = extractName(url);

            try {

                // -----------------------------
                // 1. HEALTH CHECK
                // -----------------------------

                String health = restTemplate.getForObject(
                        url + "/actuator/health",
                        String.class
                );

                if (health == null || !health.contains("UP")) {

                    log.warn(
                            "DETECTED: {} is DOWN",
                            name
                    );

                    healingEngine.heal(
                            url,
                            name,
                            "SERVICE_DOWN"
                    );

                    continue;
                }


                // -----------------------------
                // 2. CPU CHECK
                // -----------------------------

                double cpu =
                        getMetricValue(url, "system.cpu.usage");

                if (cpu > 0.80) {

                    log.warn(
                            "DETECTED: {} CPU spike = {}%",
                            name,
                            (int) (cpu * 100)
                    );

                    healingEngine.heal(
                            url,
                            name,
                            "CPU_SPIKE"
                    );

                    continue;
                }


                // -----------------------------
                // 3. MEMORY CHECK
                // -----------------------------

                double memUsed =
                        getMetricValue(url, "jvm.memory.used");

                double memMax =
                        getMetricValue(url, "jvm.memory.max");

                if (memMax > 0 &&
                        (memUsed / memMax) > 0.85) {

                    log.warn(
                            "DETECTED: {} memory usage high = {}%",
                            name,
                            (int) ((memUsed / memMax) * 100)
                    );

                    healingEngine.heal(
                            url,
                            name,
                            "MEMORY_LEAK"
                    );

                    continue;
                }


                // -----------------------------
// 4. API LATENCY CHECK
// -----------------------------

long latency = measureLatency(url);

if (latency > 3000) {

    log.warn(
            "DETECTED: {} slow API latency = {} ms",
            name,
            latency
    );

    healingEngine.heal(
            url,
            name,
            "SLOW_API"
    );

    continue;
}

log.info(
        "OK: {} is healthy | latency={} ms",
        name,
        latency
);

            } catch (Exception e) {

                log.warn(
                        "DETECTED: {} unreachable — {}",
                        name,
                        e.getMessage()
                );

                healingEngine.heal(
                        url,
                        name,
                        "SERVICE_DOWN"
                );
            }
        }
    }


    // ==========================================
    // MEASURE API LATENCY
    // ==========================================

   private long measureLatency(String url) {

    String endpoint = url + "/simulate/slow";

    try {

        long startTime =
                System.currentTimeMillis();

        restTemplate.getForObject(
                endpoint,
                String.class
        );

        long endTime =
                System.currentTimeMillis();

        return endTime - startTime;

    } catch (Exception e) {

        return Long.MAX_VALUE;
    }
}


    // ==========================================
    // GET ACTUATOR METRIC
    // ==========================================

    private double getMetricValue(
            String url,
            String metricName) {

        try {

            String endpoint =
                    url + "/actuator/metrics/" + metricName;

            Map response =
                    restTemplate.getForObject(
                            endpoint,
                            Map.class
                    );

            if (response != null) {

                List<?> measurements =
                        (List<?>) response.get("measurements");

                if (measurements != null &&
                        !measurements.isEmpty()) {

                    Map<?, ?> first =
                            (Map<?, ?>) measurements.get(0);

                    return ((Number)
                            first.get("value"))
                            .doubleValue();
                }
            }

        } catch (Exception ignored) {
        }

        return 0;
    }


    // ==========================================
    // EXTRACT SERVICE NAME
    // ==========================================

    private String extractName(String url) {

        return url
                .replace("http://", "")
                .split(":")[0];
    }
}