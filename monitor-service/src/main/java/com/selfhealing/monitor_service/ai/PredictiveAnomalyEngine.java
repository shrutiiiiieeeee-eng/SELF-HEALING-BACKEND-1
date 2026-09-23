package com.selfhealing.monitor_service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class PredictiveAnomalyEngine {

    private static final Logger log = LoggerFactory.getLogger(PredictiveAnomalyEngine.class);

    @Autowired
    private GeminiClient geminiClient;

    public record MetricSnapshot(
        long timestamp,
        double cpuUsage,
        double memoryRatio,
        long latencyMs,
        String status
    ) {}

    public record ServicePrediction(
        String serviceName,
        String predictedAnomaly,
        double confidenceScore,
        int estimatedTimeToFailureSeconds,
        String severity, // LOW, MEDIUM, HIGH, CRITICAL
        String plainDiagnosis,
        String rootCause,
        String immediateAction,
        String preventionAdvice,
        List<String> telemetryTrace,
        String oneClickAction, // SCALE_MEMORY, RESTART, ENABLE_RATE_LIMIT, TRIP_CIRCUIT, NONE
        LocalDateTime predictedAt
    ) {}

    // Rolling metric history per service (max 25 samples = ~4 mins)
    private final Map<String, Deque<MetricSnapshot>> history = new ConcurrentHashMap<>();
    private final Map<String, ServicePrediction> latestPredictions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAiPredictionTime = new ConcurrentHashMap<>();

    public void recordSnapshot(String serviceName, double cpu, double memoryRatio, long latency, String status) {
        history.computeIfAbsent(serviceName, k -> new ConcurrentLinkedDeque<>());
        Deque<MetricSnapshot> deque = history.get(serviceName);
        deque.addLast(new MetricSnapshot(System.currentTimeMillis(), cpu, memoryRatio, latency, status));
        while (deque.size() > 25) {
            deque.removeFirst();
        }

        // Evaluate trajectory
        evaluateTrajectory(serviceName);
    }

    private void evaluateTrajectory(String serviceName) {
        Deque<MetricSnapshot> deque = history.get(serviceName);
        if (deque == null || deque.size() < 3) return;

        List<MetricSnapshot> list = new ArrayList<>(deque);
        int n = list.size();
        MetricSnapshot oldest = list.get(Math.max(0, n - 4));
        MetricSnapshot newest = list.get(n - 1);

        double deltaMem = newest.memoryRatio() - oldest.memoryRatio();
        double deltaCpu = newest.cpuUsage() - oldest.cpuUsage();

        boolean memoryRisk = (deltaMem > 0.12 && newest.memoryRatio() > 0.45) || newest.memoryRatio() > 0.80;
        boolean cpuRisk = (deltaCpu > 0.20 && newest.cpuUsage() > 0.60) || newest.cpuUsage() > 0.80;
        boolean latencyRisk = newest.latencyMs() > 1500;
        boolean outageRisk = "DOWN".equalsIgnoreCase(newest.status()) || "UNREACHABLE".equalsIgnoreCase(newest.status());

        long now = System.currentTimeMillis();
        long lastRun = lastAiPredictionTime.getOrDefault(serviceName, 0L);

        if ((memoryRisk || cpuRisk || latencyRisk || outageRisk) && (now - lastRun > 15000)) {
            lastAiPredictionTime.put(serviceName, now);
            runAiPrediction(serviceName, list, memoryRisk, cpuRisk, latencyRisk, outageRisk);
        } else if (!memoryRisk && !cpuRisk && !latencyRisk && !outageRisk && newest.memoryRatio() < 0.60 && newest.cpuUsage() < 0.60) {
            latestPredictions.put(serviceName, new ServicePrediction(
                serviceName,
                "NONE",
                0.96,
                -1,
                "LOW",
                "Service is operating in optimal health parameters.",
                "All metric derivatives within baseline thresholds.",
                "Maintain continuous monitoring.",
                "Ensure standard horizontal auto-scaling rules are configured.",
                List.of("CPU: " + Math.round(newest.cpuUsage() * 100) + "%", "RAM: " + Math.round(newest.memoryRatio() * 100) + "%", "Latency: " + newest.latencyMs() + "ms"),
                "NONE",
                LocalDateTime.now()
            ));
        }
    }

    private void runAiPrediction(String serviceName, List<MetricSnapshot> snapshots, boolean memRisk, boolean cpuRisk, boolean latRisk, boolean outageRisk) {
        List<String> traces = new ArrayList<>();
        for (MetricSnapshot s : snapshots) {
            traces.add(String.format("CPU: %.1f%% | RAM: %.1f%% | Latency: %dms | Status: %s",
                s.cpuUsage() * 100, s.memoryRatio() * 100, s.latencyMs(), s.status()));
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Recent metric trajectory for [").append(serviceName).append("]:\n");
            for (String t : traces) {
                sb.append("- ").append(t).append("\n");
            }

            String systemPrompt = """
                You are AutoHeal AI SRE Copilot.
                Analyze the trajectory and output clear, easy-to-understand diagnostic advice in strict JSON:
                {
                  "predictedAnomaly": "MEMORY_LEAK" | "CPU_SPIKE" | "LATENCY_BREACH" | "SERVICE_CRASH" | "NONE",
                  "confidenceScore": float between 0.0 and 1.0,
                  "estimatedTimeToFailureSeconds": integer seconds until crash (e.g. 40, or -1 if stable),
                  "severity": "CRITICAL" | "HIGH" | "MEDIUM" | "LOW",
                  "plainDiagnosis": "Simple 1-sentence explanation of what is going wrong",
                  "rootCause": "Technical cause of the anomaly",
                  "immediateAction": "Exact remediation action AutoHeal should execute",
                  "preventionAdvice": "Long term architectural fix to prevent recurrence",
                  "oneClickAction": "SCALE_MEMORY" | "RESTART" | "ENABLE_RATE_LIMIT" | "TRIP_CIRCUIT" | "NONE"
                }
                """;

            JsonNode response = geminiClient.generateStructuredJson(systemPrompt, sb.toString());
            if (response != null && response.has("predictedAnomaly")) {
                ServicePrediction prediction = new ServicePrediction(
                    serviceName,
                    response.path("predictedAnomaly").asText("RESOURCE_ANOMALY"),
                    response.path("confidenceScore").asDouble(0.88),
                    response.path("estimatedTimeToFailureSeconds").asInt(45),
                    response.path("severity").asText("HIGH"),
                    response.path("plainDiagnosis").asText("Resource anomaly detected in recent interval."),
                    response.path("rootCause").asText("Rapid consumption of container resources."),
                    response.path("immediateAction").asText("Execute automated remediation."),
                    response.path("preventionAdvice").asText("Audit application resource allocation."),
                    traces,
                    response.path("oneClickAction").asText(memRisk ? "SCALE_MEMORY" : (cpuRisk ? "ENABLE_RATE_LIMIT" : "RESTART")),
                    LocalDateTime.now()
                );
                latestPredictions.put(serviceName, prediction);
                log.info("AI SRE PREDICTION: {} -> {} (Confidence: {}%)", serviceName, prediction.predictedAnomaly(), (int)(prediction.confidenceScore() * 100));
                return;
            }
        } catch (Exception e) {
            log.warn("Gemini prediction call fell back to local expert heuristics: {}", e.getMessage());
        }

        // Domain-expert fallback with mathematical telemetry trace
        generateDomainExpertHeuristics(serviceName, memRisk, cpuRisk, latRisk, outageRisk, traces);
    }

    private void generateDomainExpertHeuristics(String serviceName, boolean memRisk, boolean cpuRisk, boolean latRisk, boolean outageRisk, List<String> traces) {
        String anomaly;
        String severity;
        int timeToFail;
        double confidence = 0.92;
        String diagnosis;
        String rootCause;
        String immediateAction;
        String prevention;
        String oneClick;

        if (outageRisk) {
            anomaly = "SERVICE_CRASH";
            severity = "CRITICAL";
            timeToFail = 0;
            diagnosis = "Service process terminated abruptly or health probe timed out.";
            rootCause = "Application crash (System.exit or uncaught SIGKILL fatal error).";
            immediateAction = "Trip Circuit Breaker to return cached fallbacks & reboot container.";
            prevention = "Add container restart policy, graceful shutdown hook, and process supervisor.";
            oneClick = "RESTART";
        } else if (memRisk) {
            anomaly = "MEMORY_LEAK";
            severity = "HIGH";
            timeToFail = 35;
            diagnosis = "JVM Heap Escalation: Heap allocation slope indicates uncollected byte buildup.";
            rootCause = "Unbounded memory buffer accumulation without garbage collection relief.";
            immediateAction = "Dynamically increase container RAM limit to 512MB or restart process.";
            prevention = "Profile heap dump with Eclipse Memory Analyzer (MAT); audit byte[] allocations.";
            oneClick = "SCALE_MEMORY";
        } else if (cpuRisk) {
            anomaly = "CPU_SPIKE";
            severity = "HIGH";
            timeToFail = 45;
            diagnosis = "CPU Thread Saturation: Worker threads locked in intensive processing loop.";
            rootCause = "High computational loop or unthrottled concurrent requests.";
            immediateAction = "Enable API rate-limiting on gateway routes to shed burst load.";
            prevention = "Introduce worker thread pools, bounded queues, and circuit breaker rate limiters.";
            oneClick = "ENABLE_RATE_LIMIT";
        } else {
            anomaly = "LATENCY_BREACH";
            severity = "MEDIUM";
            timeToFail = 60;
            diagnosis = "Response Latency Degradation: Latency exceeded 1500ms threshold.";
            rootCause = "Downstream endpoint artificial delay or database connection pool contention.";
            immediateAction = "Divert traffic via Circuit Breaker and verify post-healing response times.";
            prevention = "Configure non-blocking I/O (WebFlux) and set tight HTTP connection timeouts.";
            oneClick = "TRIP_CIRCUIT";
        }

        ServicePrediction expertPred = new ServicePrediction(
            serviceName,
            anomaly,
            confidence,
            timeToFail,
            severity,
            diagnosis,
            rootCause,
            immediateAction,
            prevention,
            traces,
            oneClick,
            LocalDateTime.now()
        );
        latestPredictions.put(serviceName, expertPred);
        log.info("LOCAL SRE EXPERT HEURISTIC: {} -> {} (Confidence: {}%)", serviceName, anomaly, (int)(confidence * 100));
    }

    public Map<String, ServicePrediction> getAllPredictions() {
        return latestPredictions;
    }

    public ServicePrediction getPrediction(String serviceName) {
        return latestPredictions.get(serviceName);
    }

    public List<MetricSnapshot> getHistory(String serviceName) {
        Deque<MetricSnapshot> deque = history.get(serviceName);
        return deque != null ? new ArrayList<>(deque) : Collections.emptyList();
    }
}
