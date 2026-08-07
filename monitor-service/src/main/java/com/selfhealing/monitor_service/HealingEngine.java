package com.selfhealing.monitor_service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HealingEngine {

    private static final Logger log = LoggerFactory.getLogger(HealingEngine.class);

  private final DockerClient dockerClient = DockerClientBuilder.getInstance()
    .withDockerHttpClient(
        new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
            .dockerHost(URI.create("tcp://host.docker.internal:2375"))
            .maxConnections(10)
            .build()
    ).build();

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private HealingLogRepository healingLogRepo;

    private final Map<String, String> circuitState = new ConcurrentHashMap<>();
    private final Map<String, String> lastGoodImage = new ConcurrentHashMap<>();

    public void heal(String serviceUrl, String serviceName, String failureType) {

        long recentHeals = healingLogRepo.countByServiceNameAndDetectedAtAfter(
            serviceName, LocalDateTime.now().minusMinutes(5)
        );
        if (recentHeals >= 3) {
            log.warn("COOLDOWN: {} skipped — healed {} times recently", serviceName, recentHeals);
            return;
        }

        log.info("HEALING: {} | Failure: {}", serviceName, failureType);
        boolean recovered = false;

        switch (failureType) {
            case "CPU_SPIKE":
                recovered = applyRateLimit(serviceUrl, serviceName);
                if (!recovered) recovered = restartAndVerify(serviceUrl, serviceName);
                break;
            case "MEMORY_LEAK":
                recovered = scaleUpMemory(serviceUrl, serviceName);
                if (!recovered) recovered = restartAndVerify(serviceUrl, serviceName);
                break;
            case "SERVICE_DOWN":
    openCircuitBreaker(serviceName);
    recovered = restartAndVerify(serviceUrl, serviceName);
    if (recovered) closeCircuitBreaker(serviceName);
    break;

case "SLOW_API":
    openCircuitBreaker(serviceName);
    recovered = restartAndVerifyLatency(serviceUrl, serviceName);
    if (recovered) closeCircuitBreaker(serviceName);
    break;
    }

    if (recovered) {
        log.info(
                "HEALED: {} | Failure: {}",
                serviceName,
                failureType
        );
    } else {
        log.warn(
                "HEALING FAILED: {} | Failure: {}",
                serviceName,
                failureType
        );
    }
}


    private boolean applyRateLimit(String serviceUrl, String serviceName) {
        log.info("STRATEGY: Rate limiting {}", serviceName);
        try {
            restTemplate.postForObject(serviceUrl + "/admin/rate-limit/enable", null, String.class);
            Thread.sleep(15000);
            double cpu = getCpuUsage(serviceUrl);
            boolean ok = cpu < 0.70;
            if (ok) log.info("Rate limiting worked for {}", serviceName);
            return ok;
        } catch (Exception e) {
            log.warn("Rate limit strategy failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean scaleUpMemory(String serviceUrl, String serviceName) {
        log.info("STRATEGY: Scaling up memory for {}", serviceName);
        try {
            String containerId = getContainerId(serviceName);
            if (containerId == null) return false;
            dockerClient.updateContainerCmd(containerId)
                .withMemory(512 * 1024 * 1024L)
                .withMemorySwap(1024 * 1024 * 1024L)
                .exec();
            Thread.sleep(5000);
            return verifyRecovery(serviceUrl);
        } catch (Exception e) {
            log.warn("Scale up strategy failed: {}", e.getMessage());
            return false;
        }
    }

    public void openCircuitBreaker(String serviceName) {
        circuitState.put(serviceName, "OPEN");
        log.info("CIRCUIT BREAKER OPEN: {}", serviceName);
    }

    public void closeCircuitBreaker(String serviceName) {
        circuitState.put(serviceName, "CLOSED");
        log.info("CIRCUIT BREAKER CLOSED: {}", serviceName);
    }

    public boolean isCircuitOpen(String serviceName) {
        return "OPEN".equals(circuitState.getOrDefault(serviceName, "CLOSED"));
    }

    public Map<String, String> getAllCircuitStates() {
        return circuitState;
    }

    

    private boolean autoRollback(String serviceUrl, String serviceName) {
        log.info("STRATEGY: Auto rollback for {}", serviceName);
        try {
            String goodImage = lastGoodImage.getOrDefault(serviceName, null);
            if (goodImage == null) {
                log.warn("No previous image stored for {}", serviceName);
                return false;
            }
            String containerId = getContainerId(serviceName);
            if (containerId == null) return false;
            dockerClient.stopContainerCmd(containerId).exec();
            dockerClient.removeContainerCmd(containerId).exec();
            dockerClient.createContainerCmd(goodImage).withName(serviceName).exec();
            dockerClient.startContainerCmd(serviceName).exec();
            Thread.sleep(8000);
            return verifyRecovery(serviceUrl);
        } catch (Exception e) {
            log.error("Rollback failed for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }

    private boolean restartAndVerify(String serviceUrl, String serviceName) {
        try {
            log.info("STRATEGY: Restarting container {}", serviceName);
            String containerId = getContainerId(serviceName);
            if (containerId == null) return false;
            dockerClient.restartContainerCmd(containerId).exec();
            Thread.sleep(8000);
            return verifyRecovery(serviceUrl);
        } catch (Exception e) {
            log.error("Restart failed: {}", e.getMessage());
            return false;
        }
    }
    private boolean restartAndVerifyLatency(
        String serviceUrl,
        String serviceName) {

    try {
        log.info(
                "STRATEGY: Restarting slow service {}",
                serviceName
        );

        String containerId = getContainerId(serviceName);

        if (containerId == null) {
            log.warn(
                    "No container found for {}",
                    serviceName
            );
            return false;
        }

        dockerClient
                .restartContainerCmd(containerId)
                .exec();

        // Wait for the service to restart
        Thread.sleep(8000);

        // First verify that the service is healthy
        if (!verifyRecovery(serviceUrl)) {
            log.warn(
                    "Service {} did not become healthy",
                    serviceName
            );
            return false;
        }

        // Measure latency after recovery
        long startTime =
                System.currentTimeMillis();

        restTemplate.getForObject(
                serviceUrl + "/simulate/slow",
                String.class
        );

        long endTime =
                System.currentTimeMillis();

        long latency =
                endTime - startTime;

        log.info(
                "POST-HEALING LATENCY: {} ms",
                latency
        );

        // Paper requirement:
        // recovered latency must be < 1000 ms
        return latency < 1000;

    } catch (Exception e) {

        log.warn(
                "Latency recovery verification failed for {}: {}",
                serviceName,
                e.getMessage()
        );

        return false;
    }
}

    private boolean verifyRecovery(String serviceUrl) {
        try {
            String response = restTemplate.getForObject(serviceUrl + "/actuator/health", String.class);
            return response != null && response.contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    private String getContainerId(String serviceName) {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container c : containers) {
            for (String name : c.getNames()) {
                if (name.contains(serviceName)) return c.getId();
            }
        }
        return null;
    }

    private double getCpuUsage(String serviceUrl) {
        try {
            Map response = restTemplate.getForObject(
                serviceUrl + "/actuator/metrics/system.cpu.usage", Map.class);
            if (response != null) {
                var measurements = (List<?>) response.get("measurements");
                if (measurements != null && !measurements.isEmpty()) {
                    return ((Number)((Map<?,?>)measurements.get(0)).get("value")).doubleValue();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String getStrategyUsed(String failureType) {
        return switch (failureType) {
            case "CPU_SPIKE"    -> "RATE_LIMIT → RESTART";
            case "MEMORY_LEAK"  -> "SCALE_UP → RESTART";
            case "SERVICE_DOWN" -> "CIRCUIT_BREAKER → RESTART";
            case "SLOW_API"     -> "CIRCUIT_BREAKER → RESTART";
            default             -> "RESTART";
        };
    }
}