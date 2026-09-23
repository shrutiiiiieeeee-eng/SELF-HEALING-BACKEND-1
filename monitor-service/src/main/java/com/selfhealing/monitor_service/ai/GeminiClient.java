package com.selfhealing.monitor_service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateText(String prompt) {
        try {
            String endpoint = apiUrl + "?key=" + apiKey;

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(
                        Map.of("text", prompt)
                    ))
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(endpoint, entity, String.class);
            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                    return textNode.asText("");
                }
            }
        } catch (Exception e) {
            log.warn("Gemini API call failed: {}", e.getMessage());
        }
        return null;
    }

    public JsonNode generateStructuredJson(String systemInstruction, String userPrompt) {
        String fullPrompt = systemInstruction + "\n\nCRITICAL: Respond ONLY with valid, raw JSON (no markdown backticks, no quotes around JSON, no explanation):\n" + userPrompt;
        String rawText = generateText(fullPrompt);
        if (rawText != null) {
            try {
                // Strip markdown backticks if returned (e.g. ```json ... ```)
                String cleaned = rawText.trim();
                if (cleaned.startsWith("```json")) {
                    cleaned = cleaned.substring(7);
                } else if (cleaned.startsWith("```")) {
                    cleaned = cleaned.substring(3);
                }
                if (cleaned.endsWith("```")) {
                    cleaned = cleaned.substring(0, cleaned.length() - 3);
                }
                cleaned = cleaned.trim();
                return objectMapper.readTree(cleaned);
            } catch (Exception e) {
                log.warn("Failed to parse Gemini JSON output: {}", e.getMessage());
            }
        }
        return null;
    }
}
