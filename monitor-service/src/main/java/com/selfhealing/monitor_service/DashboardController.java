package com.selfhealing.monitor_service;

import com.selfhealing.monitor_service.ai.HealingAgent;
import com.selfhealing.monitor_service.ai.PredictiveAnomalyEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    @Autowired
    private HealingEngine healingEngine;

    @Autowired
    private HealingLogRepository healingLogRepo;

    @Autowired
    private PredictiveAnomalyEngine predictiveEngine;

    @Autowired
    private HealingAgent healingAgent;

    @Autowired
    private TrafficSimulatorService trafficSimulator;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Map<String, String> SERVICE_URLS = Map.of(
        "service-a", "http://service-a:8081",
        "service-b", "http://service-b:8082",
        "service-c", "http://service-c:8083"
    );

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> result = new HashMap<>();

        // 1. Monitored Services Telemetry
        List<Map<String, Object>> services = new ArrayList<>();
        for (String serviceName : List.of("service-a", "service-b", "service-c")) {
            String url = resolveTargetUrl(serviceName);
            Map<String, Object> serviceData = new HashMap<>();
            serviceData.put("name", serviceName);
            serviceData.put("url", url);

            // Health & Latency
            long start = System.currentTimeMillis();
            String health = "DOWN";
            try {
                String h = restTemplate.getForObject(url + "/actuator/health", String.class);
                if (h != null && h.contains("UP")) health = "UP";
            } catch (Exception ignored) {}
            long latency = System.currentTimeMillis() - start;

            serviceData.put("health", health);
            serviceData.put("circuitBreaker", healingEngine.isCircuitOpen(serviceName) ? "OPEN" : "CLOSED");

            // CPU & Memory metrics
            double cpu = getMetric(url, "system.cpu.usage");
            double memUsed = getMetric(url, "jvm.memory.used");
            double memMax = getMetric(url, "jvm.memory.max");
            double memRatio = memMax > 0 ? (memUsed / memMax) : 0;

            serviceData.put("cpuUsagePercent", Math.round(cpu * 1000.0) / 10.0);
            serviceData.put("memoryUsedMb", Math.round((memUsed / (1024 * 1024)) * 10.0) / 10.0);
            serviceData.put("memoryMaxMb", Math.round((memMax / (1024 * 1024)) * 10.0) / 10.0);
            serviceData.put("memoryRatioPercent", Math.round(memRatio * 1000.0) / 10.0);
            serviceData.put("latencyMs", latency);

            // History for charts
            serviceData.put("history", predictiveEngine.getHistory(serviceName));

            // AI Prediction
            PredictiveAnomalyEngine.ServicePrediction pred = predictiveEngine.getPrediction(serviceName);
            serviceData.put("aiPrediction", pred);

            services.add(serviceData);
        }

        result.put("services", services);
        result.put("circuitStates", healingEngine.getAllCircuitStates());
        result.put("recentLogs", healingLogRepo.findTop20ByOrderByDetectedAtDesc());
        result.put("trafficStatus", trafficSimulator.getStatus());
        result.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/chaos/{service}/{action}")
    public ResponseEntity<Map<String, Object>> triggerChaos(
            @PathVariable String service,
            @PathVariable String action) {
        String url = resolveTargetUrl(service);
        try {
            String targetEndpoint;
            switch (action.toLowerCase()) {
                case "cpu" -> targetEndpoint = url + "/simulate/cpu";
                case "memory" -> targetEndpoint = url + "/simulate/memory";
                case "crash" -> {
                    new Thread(() -> {
                        try { restTemplate.getForObject(url + "/simulate/crash", String.class); } catch (Exception ignored) {}
                    }).start();
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Crash signal sent to " + service));
                }
                case "slow-enable" -> {
                    restTemplate.postForObject(url + "/admin/slow/enable", null, String.class);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Slow mode enabled on " + service));
                }
                case "slow-disable" -> {
                    restTemplate.postForObject(url + "/admin/slow/disable", null, String.class);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Slow mode disabled on " + service));
                }
                case "rate-limit-enable" -> {
                    restTemplate.postForObject(url + "/admin/rate-limit/enable", null, String.class);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Rate-limit enabled on " + service));
                }
                case "rate-limit-disable" -> {
                    restTemplate.postForObject(url + "/admin/rate-limit/disable", null, String.class);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Rate-limit disabled on " + service));
                }
                default -> {
                    return ResponseEntity.badRequest().body(Map.of("error", "Unknown chaos action: " + action));
                }
            }
            String resp = restTemplate.getForObject(targetEndpoint, String.class);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "service", service, "response", resp != null ? resp : "OK"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/traffic/start")
    public ResponseEntity<Map<String, Object>> startTraffic(@RequestParam(defaultValue = "15") int users) {
        trafficSimulator.start(users);
        return ResponseEntity.ok(trafficSimulator.getStatus());
    }

    @PostMapping("/traffic/stop")
    public ResponseEntity<Map<String, Object>> stopTraffic() {
        trafficSimulator.stop();
        return ResponseEntity.ok(trafficSimulator.getStatus());
    }

    @PostMapping("/ai/apply-action/{service}/{action}")
    public ResponseEntity<Map<String, Object>> applyAiAction(
            @PathVariable String service,
            @PathVariable String action) {
        String url = resolveTargetUrl(service);
        boolean success = false;
        String message = "";

        switch (action.toUpperCase()) {
            case "SCALE_MEMORY" -> {
                success = healingEngine.scaleUpMemory(url, service);
                message = success ? "Dynamically scaled container RAM to 512MB." : "Memory scale-up failed.";
            }
            case "RESTART" -> {
                success = healingEngine.restartAndVerify(url, service);
                message = success ? "Container successfully restarted and healthy." : "Restart verification timed out.";
            }
            case "ENABLE_RATE_LIMIT" -> {
                success = healingEngine.applyRateLimit(url, service);
                message = success ? "Rate-limiting enabled to stabilize CPU." : "Rate-limiting activation failed.";
            }
            case "TRIP_CIRCUIT" -> {
                healingEngine.openCircuitBreaker(service);
                success = true;
                message = "Circuit breaker opened. Traffic routed to fallback.";
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown action: " + action));
            }
        }

        return ResponseEntity.ok(Map.of(
            "service", service,
            "action", action,
            "success", success,
            "message", message
        ));
    }

    @GetMapping("/ai/trace/{service}")
    public ResponseEntity<Map<String, Object>> getServiceTrace(@PathVariable String service) {
        var pred = predictiveEngine.getPrediction(service);
        var hist = predictiveEngine.getHistory(service);
        return ResponseEntity.ok(Map.of(
            "service", service,
            "prediction", pred != null ? pred : Map.of(),
            "history", hist != null ? hist : List.of()
        ));
    }

    @PostMapping("/ai/heal-agent/{service}")
    public ResponseEntity<Map<String, Object>> triggerAiHealingAgent(@PathVariable String service) {
        String url = resolveTargetUrl(service);
        var resolution = healingAgent.diagnoseAndHeal(url, service, "MANUAL_AI_AGENT_INVOCATION");
        return ResponseEntity.ok(Map.of(
            "service", service,
            "diagnosis", resolution.diagnosis(),
            "action", resolution.plannedAction(),
            "executionResult", resolution.executionResult(),
            "success", resolution.success(),
            "reasoning", resolution.agentReasoning()
        ));
    }

    private String resolveTargetUrl(String serviceName) {
        String dockerUrl = SERVICE_URLS.get(serviceName);
        try {
            restTemplate.getForObject(dockerUrl + "/actuator/health", String.class);
            return dockerUrl;
        } catch (Exception e) {
            return switch (serviceName) {
                case "service-a" -> "http://localhost:8081";
                case "service-b" -> "http://localhost:8082";
                case "service-c" -> "http://localhost:8083";
                default -> dockerUrl;
            };
        }
    }

    private double getMetric(String url, String metricName) {
        try {
            Map response = restTemplate.getForObject(url + "/actuator/metrics/" + metricName, Map.class);
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
}
