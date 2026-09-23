package com.selfhealing.monitor_service;

import com.selfhealing.monitor_service.ai.PredictiveAnomalyEngine;
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

    private static final Logger log = LoggerFactory.getLogger(FailureDetector.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${monitored.services}")
    private String[] serviceUrls;

    @Autowired
    private HealingEngine healingEngine;

    @Autowired
    private PredictiveAnomalyEngine predictiveEngine;

    @Scheduled(fixedDelay = 10000)
    public void detect() {
        for (String configuredUrl : serviceUrls) {
            String name = extractName(configuredUrl);
            String targetUrl = resolveUrl(configuredUrl, name);

            if (healingEngine.isHealing(name)) {
                log.info("Service {} is actively healing; skipping detection cycle.", name);
                continue;
            }

            try {
                // -----------------------------
                // 1. HEALTH CHECK & LATENCY
                // -----------------------------
                long startTime = System.currentTimeMillis();
                String health = null;
                try {
                    health = restTemplate.getForObject(targetUrl + "/actuator/health", String.class);
                } catch (Exception e) {
                    health = null;
                }
                long latency = System.currentTimeMillis() - startTime;

                if (health == null || !health.contains("UP")) {
                    log.warn("DETECTED: {} is DOWN (URL: {})", name, targetUrl);
                    predictiveEngine.recordSnapshot(name, 0.0, 0.0, latency, "DOWN");
                    healingEngine.heal(targetUrl, name, "SERVICE_DOWN");
                    continue;
                }

                // -----------------------------
                // 2. CPU CHECK
                // -----------------------------
                double cpu = getMetricValue(targetUrl, "system.cpu.usage");
                if (cpu > 0.80) {
                    log.warn("DETECTED: {} CPU spike = {}%", name, (int) (cpu * 100));
                    predictiveEngine.recordSnapshot(name, cpu, 0.5, latency, "CPU_SPIKE");
                    healingEngine.heal(targetUrl, name, "CPU_SPIKE");
                    continue;
                }

                // -----------------------------
                // 3. MEMORY CHECK
                // -----------------------------
                double memUsed = getMetricValue(targetUrl, "jvm.memory.used");
                double memMax = getMetricValue(targetUrl, "jvm.memory.max");
                double memRatio = memMax > 0 ? (memUsed / memMax) : 0;

                if (memRatio > 0.85) {
                    log.warn("DETECTED: {} memory usage high = {}%", name, (int) (memRatio * 100));
                    predictiveEngine.recordSnapshot(name, cpu, memRatio, latency, "MEMORY_HIGH");
                    healingEngine.heal(targetUrl, name, "MEMORY_LEAK");
                    continue;
                }

                // -----------------------------
                // 4. API LATENCY CHECK
                // -----------------------------
                // Check simulate/slow latency if available, otherwise probe latency
                long apiLatency = checkEndpointLatency(targetUrl, latency);
                if (apiLatency > 3000) {
                    log.warn("DETECTED: {} slow API latency = {} ms", name, apiLatency);
                    predictiveEngine.recordSnapshot(name, cpu, memRatio, apiLatency, "SLOW_API");
                    healingEngine.heal(targetUrl, name, "SLOW_API");
                    continue;
                }

                // Normal healthy state -> Stream to AI predictive buffer
                predictiveEngine.recordSnapshot(name, cpu, memRatio, apiLatency, "UP");
                log.info("OK: {} is healthy | CPU={}% | Mem={}% | Latency={} ms",
                    name, (int)(cpu * 100), (int)(memRatio * 100), apiLatency);

                // Proactive healing check from AI predictions
                PredictiveAnomalyEngine.ServicePrediction pred = predictiveEngine.getPrediction(name);
                if (pred != null && "CRITICAL".equalsIgnoreCase(pred.severity()) && pred.confidenceScore() > 0.88) {
                    log.warn("PROACTIVE HEALING TRIGGERED by AI for {}: {} (Confidence: {}%)",
                        name, pred.predictedAnomaly(), (int)(pred.confidenceScore() * 100));
                    if ("SCALE_MEMORY".equalsIgnoreCase(pred.oneClickAction())) {
                        healingEngine.scaleUpMemory(targetUrl, name);
                    } else if ("ENABLE_RATE_LIMIT".equalsIgnoreCase(pred.oneClickAction())) {
                        healingEngine.applyRateLimit(targetUrl, name);
                    }
                }

            } catch (Exception e) {
                log.warn("DETECTED: {} unreachable — {}", name, e.getMessage());
                predictiveEngine.recordSnapshot(name, 0.0, 0.0, 9999, "UNREACHABLE");
                healingEngine.heal(targetUrl, name, "SERVICE_DOWN");
            }
        }
    }

    private long checkEndpointLatency(String url, long fallbackLatency) {
        try {
            long start = System.currentTimeMillis();
            restTemplate.getForObject(url + "/simulate/slow", String.class);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            // Service may not have /simulate/slow (e.g. standard endpoints), use probe latency
            return fallbackLatency;
        }
    }

    private String resolveUrl(String configuredUrl, String serviceName) {
        // Test connectivity to configured URL; if fails, check localhost fallback
        try {
            restTemplate.getForObject(configuredUrl + "/actuator/health", String.class);
            return configuredUrl;
        } catch (Exception e) {
            String localFallback = switch (serviceName) {
                case "service-a" -> "http://localhost:8081";
                case "service-b" -> "http://localhost:8082";
                case "service-c" -> "http://localhost:8083";
                default -> configuredUrl;
            };
            try {
                restTemplate.getForObject(localFallback + "/actuator/health", String.class);
                return localFallback;
            } catch (Exception ex) {
                return configuredUrl;
            }
        }
    }

    private double getMetricValue(String url, String metricName) {
        try {
            String endpoint = url + "/actuator/metrics/" + metricName;
            Map response = restTemplate.getForObject(endpoint, Map.class);
            if (response != null) {
                List<?> measurements = (List<?>) response.get("measurements");
                if (measurements != null && !measurements.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) measurements.get(0);
                    return ((Number) first.get("value")).doubleValue();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String extractName(String url) {
        return url
                .replace("http://", "")
                .replace("https://", "")
                .split(":")[0];
    }
}