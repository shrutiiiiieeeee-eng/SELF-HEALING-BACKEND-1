package com.selfhealing.monitor_service;

import jakarta.servlet.http.HttpServletRequest;
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

    // All requests route through here: /gateway/{serviceName}/**
    @RequestMapping(value = "/{serviceName}/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Object> route(
            @PathVariable String serviceName,
            HttpServletRequest request) {

        // if circuit is open → return fallback immediately
        if (healingEngine.isCircuitOpen(serviceName)) {
            return ResponseEntity.ok(Map.of(
                "status",   "FALLBACK",
                "service",  serviceName,
                "circuit",  "OPEN",
                "message",  serviceName + " is temporarily unavailable. Circuit is OPEN; serving graceful fallback.",
                "data",     getDefaultResponse(serviceName)
            ));
        }

        // Circuit closed → resolve downstream URL
        String baseTarget = SERVICE_URLS.get(serviceName);
        if (baseTarget == null) {
            // Local dev fallback if running outside docker
            baseTarget = switch (serviceName) {
                case "service-a" -> "http://localhost:8081";
                case "service-b" -> "http://localhost:8082";
                case "service-c" -> "http://localhost:8083";
                default -> null;
            };
        }

        if (baseTarget == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown service: " + serviceName));
        }

        String fullUri = request.getRequestURI();
        String prefix = "/gateway/" + serviceName;
        String subPath = "";
        if (fullUri.startsWith(prefix)) {
            subPath = fullUri.substring(prefix.length());
            if (subPath.startsWith("/")) {
                subPath = subPath.substring(1);
            }
        }

        String targetUrl = baseTarget + "/" + subPath;
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            targetUrl += "?" + request.getQueryString();
        }

        try {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                Object response = restTemplate.postForObject(targetUrl, null, Object.class);
                return ResponseEntity.ok(response != null ? response : Map.of("status", "SUCCESS"));
            } else {
                Object response = restTemplate.getForObject(targetUrl, Object.class);
                return ResponseEntity.ok(response != null ? response : Map.of("status", "SUCCESS"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status",  "ERROR",
                "service", serviceName,
                "targetUrl", targetUrl,
                "message", "Service unavailable: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/circuit-states")
    public Map<String, String> circuitStates() {
        return healingEngine.getAllCircuitStates();
    }

    private Object getDefaultResponse(String serviceName) {
        return switch (serviceName) {
            case "service-a" -> Map.of("result", "cached-data-service-a", "timestamp", System.currentTimeMillis());
            case "service-b" -> Map.of("result", "cached-data-service-b", "timestamp", System.currentTimeMillis());
            case "service-c" -> Map.of("result", "cached-data-service-c", "timestamp", System.currentTimeMillis());
            default          -> Map.of("result", "fallback-default", "timestamp", System.currentTimeMillis());
        };
    }
}