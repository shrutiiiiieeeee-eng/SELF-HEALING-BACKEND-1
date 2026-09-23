package com.selfhealing.monitor_service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.monitor_service.HealingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class HealingAgent {

    private static final Logger log = LoggerFactory.getLogger(HealingAgent.class);

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private HealingEngine healingEngine;

    private final RestTemplate restTemplate = new RestTemplate();

    public record AgentResolution(
        String serviceName,
        String diagnosis,
        String plannedAction,
        String executionResult,
        boolean success,
        String agentReasoning
    ) {}

    public AgentResolution diagnoseAndHeal(String serviceUrl, String serviceName, String failureContext) {
        log.info("AI HEALING AGENT: Summoned for {} with context: {}", serviceName, failureContext);

        // Step 1: Tool call - Inspect current telemetry
        String healthStatus = checkHealth(serviceUrl);
        double cpu = getMetric(serviceUrl, "system.cpu.usage");
        double memUsed = getMetric(serviceUrl, "jvm.memory.used");
        double memMax = getMetric(serviceUrl, "jvm.memory.max");
        double memRatio = memMax > 0 ? (memUsed / memMax) : 0;

        String observation = String.format(
            "Service: %s\nReported Issue: %s\nHealth Endpoint: %s\nCPU Usage: %.2f%%\nMemory Usage: %.2f%%\nCircuit State: %s",
            serviceName, failureContext, healthStatus, cpu * 100, memRatio * 100,
            healingEngine.isCircuitOpen(serviceName) ? "OPEN" : "CLOSED"
        );

        // Step 2: Agent Reasoning via Gemini
        String systemPrompt = """
            You are an Autonomous Site Reliability Engineering (SRE) Agent.
            You have autonomous authority to diagnose and execute remediation on microservices.
            Available Actions:
            - "RESTART": Reboot container process to clear corrupted state/threads.
            - "SCALE_MEMORY": Increase container memory limit to alleviate heap pressure.
            - "RATE_LIMIT": Enable rate limiting to shed traffic and reduce CPU.
            - "TRIP_CIRCUIT": Open circuit breaker and return fallback responses.
            
            Respond in strict raw JSON:
            {
              "diagnosis": "technical assessment of the problem",
              "action": "RESTART" | "SCALE_MEMORY" | "RATE_LIMIT" | "TRIP_CIRCUIT",
              "reasoning": "why this specific action is the optimal remediation"
            }
            """;

        JsonNode decision = geminiClient.generateStructuredJson(systemPrompt, observation);

        String action = "RESTART";
        String diagnosis = "Unresponsive service state";
        String reasoning = "Forced container reboot to restore availability";

        if (decision != null) {
            action = decision.path("action").asText("RESTART");
            diagnosis = decision.path("diagnosis").asText(diagnosis);
            reasoning = decision.path("reasoning").asText(reasoning);
        } else {
            // Intelligent fallback selection
            if (memRatio > 0.80) action = "SCALE_MEMORY";
            else if (cpu > 0.80) action = "RATE_LIMIT";
            else action = "RESTART";
        }

        log.info("AI AGENT DECISION: Action=[{}] for service [{}] -> {}", action, serviceName, reasoning);

        // Step 3: Tool Execution
        boolean success = false;
        String executionResult;

        switch (action) {
            case "SCALE_MEMORY" -> {
                success = healingEngine.scaleUpMemory(serviceUrl, serviceName);
                executionResult = success ? "Successfully expanded container memory headroom." : "Memory scaling failed; escalating to restart.";
                if (!success) success = healingEngine.restartAndVerify(serviceUrl, serviceName);
            }
            case "RATE_LIMIT" -> {
                success = healingEngine.applyRateLimit(serviceUrl, serviceName);
                executionResult = success ? "Applied rate-limiting to dampen CPU spike." : "Rate-limiting failed; restarting container.";
                if (!success) success = healingEngine.restartAndVerify(serviceUrl, serviceName);
            }
            case "TRIP_CIRCUIT" -> {
                healingEngine.openCircuitBreaker(serviceName);
                success = true;
                executionResult = "Circuit breaker opened. Fallbacks enabled.";
            }
            default -> {
                healingEngine.openCircuitBreaker(serviceName);
                success = healingEngine.restartAndVerify(serviceUrl, serviceName);
                if (success) healingEngine.closeCircuitBreaker(serviceName);
                executionResult = success ? "Container restart verified healthy." : "Container restart verification timed out.";
            }
        }

        return new AgentResolution(serviceName, diagnosis, action, executionResult, success, reasoning);
    }

    private String checkHealth(String serviceUrl) {
        try {
            String res = restTemplate.getForObject(serviceUrl + "/actuator/health", String.class);
            return res != null ? res : "DOWN";
        } catch (Exception e) {
            return "DOWN (" + e.getMessage() + ")";
        }
    }

    private double getMetric(String serviceUrl, String metricName) {
        try {
            Map res = restTemplate.getForObject(serviceUrl + "/actuator/metrics/" + metricName, Map.class);
            if (res != null) {
                var measurements = (java.util.List<?>) res.get("measurements");
                if (measurements != null && !measurements.isEmpty()) {
                    var first = (Map<?, ?>) measurements.get(0);
                    return ((Number) first.get("value")).doubleValue();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
