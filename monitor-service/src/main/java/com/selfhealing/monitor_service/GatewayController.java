package com.selfhealing.monitor_service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/gateway")
public class GatewayController {

    @Autowired
    private HealingEngine healingEngine;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Map<String, String> SERVICE_URLS = Map.of(
        "service-a", "http://service-a:8081",
        "service-b", "http://service-b:8082",
        "service-c", "http://service-c:8083"
    );

    // All requests go through here: /gateway/service-b/any-endpoint
    @GetMapping("/{serviceName}/**")
    public ResponseEntity<Object> route(
            @PathVariable String serviceName,
            @RequestParam(required = false) String path) {

        // if circuit is open → return fallback immediately
        if (healingEngine.isCircuitOpen(serviceName)) {
            return ResponseEntity.ok(Map.of(
                "status",   "FALLBACK",
                "service",  serviceName,
                "message",  serviceName + " is temporarily unavailable. Using cached response.",
                "data",     getDefaultResponse(serviceName)
            ));
        }

        // circuit closed → forward request normally
        try {
            String url = SERVICE_URLS.get(serviceName) + "/" + path;
            Object response = restTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status",  "ERROR",
                "message", "Service unavailable"
            ));
        }
    }

    @GetMapping("/circuit-states")
    public Map<String, String> circuitStates() {
        return healingEngine.getAllCircuitStates();
    }

    private Object getDefaultResponse(String serviceName) {
        // return sensible defaults per service
        return switch (serviceName) {
            case "service-a" -> Map.of("result", "default-data-from-service-a");
            case "service-b" -> Map.of("result", "default-data-from-service-b");
            case "service-c" -> Map.of("result", "default-data-from-service-c");
            default          -> Map.of("result", "service-unavailable");
        };
    }
}