package com.selfhealing.monitor_service;

import com.selfhealing.monitor_service.ai.AiRcaService;
import com.selfhealing.monitor_service.ai.GeminiClient;
import com.selfhealing.monitor_service.ai.PredictiveAnomalyEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiServicesTest {

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private PredictiveAnomalyEngine predictiveEngine;

    @Autowired
    private AiRcaService aiRcaService;

    @Autowired
    private HealingLogRepository healingLogRepo;

    @Test
    void testGeminiClientConnectivity() {
        assertNotNull(geminiClient);
        String response = geminiClient.generateText("Respond with the exact word: PONG");
        assertNotNull(response, "Gemini response should not be null");
        assertTrue(response.toUpperCase().contains("PONG"), "Response should contain PONG");
    }

    @Test
    void testPredictiveAnomalyEngineRecording() {
        predictiveEngine.recordSnapshot("service-b", 0.35, 0.40, 45, "UP");
        predictiveEngine.recordSnapshot("service-b", 0.50, 0.65, 50, "UP");
        predictiveEngine.recordSnapshot("service-b", 0.70, 0.82, 120, "UP");

        List<PredictiveAnomalyEngine.MetricSnapshot> history = predictiveEngine.getHistory("service-b");
        assertFalse(history.isEmpty());
        assertEquals(3, history.size());
    }

    @Test
    void testHealingLogPersistenceWithRca() {
        String rca = aiRcaService.generateExplainableRca(
            "service-b", "MEMORY_LEAK", "SCALE_UP -> RESTART", "HEALED", 1500L, "Memory was 88%"
        );
        assertNotNull(rca);

        HealingLog log = new HealingLog(
            "service-b", "MEMORY_LEAK", "SCALE_UP -> RESTART", "HEALED",
            rca, 0.92, 1500L, false
        );
        HealingLog saved = healingLogRepo.save(log);
        assertNotNull(saved.getId());
        assertEquals("service-b", saved.getServiceName());
        assertNotNull(saved.getRootCauseAnalysis());

        List<HealingLog> logs = healingLogRepo.findTop20ByOrderByDetectedAtDesc();
        assertFalse(logs.isEmpty());
    }
}
