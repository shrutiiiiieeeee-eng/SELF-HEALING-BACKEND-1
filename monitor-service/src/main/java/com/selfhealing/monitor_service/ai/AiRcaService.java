package com.selfhealing.monitor_service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiRcaService {

    private static final Logger log = LoggerFactory.getLogger(AiRcaService.class);

    @Autowired
    private GeminiClient geminiClient;

    public String generateExplainableRca(String serviceName, String failureType, String actionTaken,
                                         String status, long durationMs, String metricContext) {
        try {
            String prompt = String.format("""
                You are an SRE AI Diagnostic Specialist for a self-healing microservice backend.
                Analyze this incident and generate a concise, explainable post-incident Root Cause Analysis (RCA):
                
                - Service: %s
                - Failure Category: %s
                - Telemetry Snapshot: %s
                - Healing Action Executed: %s
                - Resulting Status: %s
                - Recovery Duration: %d ms
                
                Please format your response in 2-3 concise, high-impact bullet points:
                1. Root Cause: (Why this happened, e.g. unbounded array allocation, infinite computation, crash)
                2. Strategy Justification: (Why the selected action was effective or necessary)
                3. Prevention Recommendation: (Concrete architectural fix)
                Keep it technical, clear, and professional.
                """, serviceName, failureType, metricContext, actionTaken, status, durationMs);

            String rca = geminiClient.generateText(prompt);
            if (rca != null && !rca.isBlank()) {
                return rca.trim();
            }
        } catch (Exception e) {
            log.warn("Failed to generate AI RCA for {}: {}", serviceName, e.getMessage());
        }

        // Deterministic explainability fallback
        return switch (failureType) {
            case "CPU_SPIKE" ->
                "• Root Cause: High computational load or thread saturation.\n• Strategy: Rate limiting applied to shed burst traffic; restarted process when CPU remained >80%.\n• Prevention: Implement work queue throttling and circuit breaking on high-load endpoints.";
            case "MEMORY_LEAK" ->
                "• Root Cause: JVM heap accumulation exceeding 85% without sufficient GC collection.\n• Strategy: Dynamically expanded memory headroom via Docker API followed by clean restart.\n• Prevention: Audit object lifecycles, clear memory hogs, and profile heap dumps.";
            case "SLOW_API" ->
                "• Root Cause: Downstream latency exceeded 3000ms threshold, threatening cascade timeouts.\n• Strategy: Circuit breaker tripped to protect callers with fallback response; restarted service.\n• Prevention: Configure timeout thresholds, asynchronous processing, and connection pooling.";
            default ->
                "• Root Cause: Service crashed or health check failed to respond.\n• Strategy: Tripped circuit breaker to divert traffic, restarted container, and verified UP health check.\n• Prevention: Ensure zero-downtime rolling deploys and health probe readiness checks.";
        };
    }
}
