package com.selfhealing.monitor_service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.selfhealing.monitor_service.ai.AiRcaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HealingEngine {

    private static final Logger log = LoggerFactory.getLogger(HealingEngine.class);

    @Value("${docker.host:unix:///var/run/docker.sock}")
    private String dockerHostUri;

    @Value("${docker.enabled:true}")
    private boolean dockerEnabled;

    private DockerClient dockerClient;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private HealingLogRepository healingLogRepo;

    @Autowired
    private AiRcaService aiRcaService;

    private final Map<String, String> circuitState = new ConcurrentHashMap<>();
    private final Set<String> healingInProgress = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void initDockerClient() {
        if (!dockerEnabled) {
            log.info("Docker management disabled by configuration.");
            return;
        }
        try {
            String resolved = dockerHostUri;
            if (new java.io.File("/var/run/docker.sock").exists()) {
                resolved = "unix:///var/run/docker.sock";
            }
            log.info("Initializing Docker connection to: {}", resolved);

            var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(resolved)
                .build();

            this.dockerClient = DockerClientBuilder.getInstance(config)
                .withDockerHttpClient(
                    new ZerodepDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .maxConnections(50)
                        .connectionTimeout(Duration.ofSeconds(10))
                        .responseTimeout(Duration.ofSeconds(30))
                        .build()
                ).build();

            this.dockerClient.pingCmd().exec();
            log.info("Successfully connected to Docker daemon at {}", resolved);
        } catch (Exception e) {
            log.warn("Docker daemon initialization at {} deferred/failed: {}", dockerHostUri, e.getMessage());
            this.dockerClient = null;
        }
    }

    public boolean isHealing(String serviceName) {
        return healingInProgress.contains(serviceName);
    }

    public void heal(String serviceUrl, String serviceName, String failureType) {
        if (healingInProgress.contains(serviceName)) {
            log.info("Healing already in progress for {}, skipping duplicate trigger.", serviceName);
            return;
        }

        long recentHeals = healingLogRepo.countByServiceNameAndDetectedAtAfter(
            serviceName, LocalDateTime.now().minusMinutes(5)
        );
        if (recentHeals >= 8) {
            log.warn("COOLDOWN: {} skipped — healed {} times recently", serviceName, recentHeals);
            return;
        }

        healingInProgress.add(serviceName);
        long startTime = System.currentTimeMillis();

        try {
            log.info("HEALING ENGAGED: {} | Failure: {}", serviceName, failureType);
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
                default:
                    openCircuitBreaker(serviceName);
                    recovered = restartAndVerify(serviceUrl, serviceName);
                    if (recovered) closeCircuitBreaker(serviceName);
                    break;
            }

            long duration = System.currentTimeMillis() - startTime;
            String status = recovered ? "HEALED" : "FAILED";
            String strategy = getStrategyUsed(failureType);

            if (recovered) {
                log.info("HEALED: {} | Failure: {} | Duration: {} ms", serviceName, failureType, duration);
            } else {
                log.warn("HEALING FAILED: {} | Failure: {} | Duration: {} ms", serviceName, failureType, duration);
            }

            // Generate Explainable AI Root Cause Analysis
            String rca = aiRcaService.generateExplainableRca(
                serviceName,
                failureType,
                strategy,
                status,
                duration,
                "Service at " + serviceUrl + " triggered " + failureType
            );

            // Persist audit record to database
            try {
                HealingLog logEntry = new HealingLog(
                    serviceName,
                    failureType,
                    strategy,
                    status,
                    rca,
                    0.95,
                    duration,
                    false
                );
                healingLogRepo.save(logEntry);
                log.info("Audit log successfully saved for {} [ID: {}]", serviceName, logEntry.getId());
            } catch (Exception e) {
                log.error("Failed to persist healing audit log: {}", e.getMessage());
            }
        } finally {
            healingInProgress.remove(serviceName);
        }
    }

    public boolean applyRateLimit(String serviceUrl, String serviceName) {
        log.info("STRATEGY: Rate limiting {}", serviceName);
        try {
            restTemplate.postForObject(serviceUrl + "/admin/rate-limit/enable", null, String.class);
            Thread.sleep(3000);
            double cpu = getCpuUsage(serviceUrl);
            boolean ok = cpu < 0.75;
            if (ok) log.info("Rate limiting successfully stabilized {}", serviceName);
            return ok;
        } catch (Exception e) {
            log.warn("Rate limit strategy failed for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }

    public boolean scaleUpMemory(String serviceUrl, String serviceName) {
        log.info("STRATEGY: Scaling up memory for {}", serviceName);
        try {
            if (dockerClient != null) {
                String containerId = getContainerId(serviceName);
                if (containerId != null) {
                    dockerClient.updateContainerCmd(containerId)
                        .withMemory(512 * 1024 * 1024L)
                        .withMemorySwap(1024 * 1024 * 1024L)
                        .exec();
                    log.info("Docker container memory updated to 512MB for {}", serviceName);
                }
            } else {
                log.info("[Local Dev Mode] Memory scale-up simulated for {}", serviceName);
            }
            Thread.sleep(3000);
            return verifyRecovery(serviceUrl);
        } catch (Exception e) {
            log.warn("Scale up strategy failed for {}: {}", serviceName, e.getMessage());
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
        return "OPEN".equalsIgnoreCase(circuitState.getOrDefault(serviceName, "CLOSED"));
    }

    public Map<String, String> getAllCircuitStates() {
        return circuitState;
    }

    public boolean restartAndVerify(String serviceUrl, String serviceName) {
        try {
            log.info("STRATEGY: Restarting container {}", serviceName);
            if (dockerClient != null) {
                String containerId = getContainerId(serviceName);
                if (containerId != null) {
                    try {
                        dockerClient.restartContainerCmd(containerId).exec();
                        log.info("Restart issued via Docker API for container ID {}", containerId);
                    } catch (Exception re) {
                        log.warn("restartContainerCmd failed ({}), attempting startContainerCmd: ", re.getMessage());
                        try {
                            dockerClient.startContainerCmd(containerId).exec();
                            log.info("startContainerCmd succeeded for container ID {}", containerId);
                        } catch (Exception se) {
                            log.error("startContainerCmd failed: {}", se.getMessage());
                        }
                    }
                } else {
                    log.warn("Could not find container for service name: {}", serviceName);
                }
            } else {
                log.info("[Local Dev Mode] Simulated container restart for {}", serviceName);
            }

            // Spring Boot microservices take 6-12 seconds to boot and pass /actuator/health
            for (int i = 0; i < 12; i++) {
                Thread.sleep(1500);
                if (verifyRecovery(serviceUrl)) {
                    log.info("Service {} successfully recovered after {} ms", serviceName, (i + 1) * 1500);
                    return true;
                }
            }
            log.warn("Service {} did not report UP within verification window", serviceName);
            return false;
        } catch (Exception e) {
            log.error("Restart failed for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }

    public boolean restartAndVerifyLatency(String serviceUrl, String serviceName) {
        try {
            log.info("STRATEGY: Restarting slow service {}", serviceName);
            if (dockerClient != null) {
                String containerId = getContainerId(serviceName);
                if (containerId != null) {
                    try {
                        dockerClient.restartContainerCmd(containerId).exec();
                    } catch (Exception re) {
                        try { dockerClient.startContainerCmd(containerId).exec(); } catch (Exception ignored) {}
                    }
                }
            }

            // Disable slow mode on downstream service if supported
            try {
                restTemplate.postForObject(serviceUrl + "/admin/slow/disable", null, String.class);
            } catch (Exception ignored) {}

            boolean isUp = false;
            for (int i = 0; i < 12; i++) {
                Thread.sleep(1500);
                if (verifyRecovery(serviceUrl)) {
                    isUp = true;
                    break;
                }
            }

            if (!isUp) {
                log.warn("Service {} did not become healthy after restart", serviceName);
                return false;
            }

            long startTime = System.currentTimeMillis();
            restTemplate.getForObject(serviceUrl + "/actuator/health", String.class);
            long latency = System.currentTimeMillis() - startTime;

            log.info("POST-HEALING PROBE LATENCY: {} ms for {}", latency, serviceName);
            return latency < 1500;
        } catch (Exception e) {
            log.warn("Latency recovery verification failed for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }

    public boolean verifyRecovery(String serviceUrl) {
        try {
            String response = restTemplate.getForObject(serviceUrl + "/actuator/health", String.class);
            return response != null && response.contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    private String getContainerId(String serviceName) {
        if (dockerClient == null) return null;
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            for (Container c : containers) {
                for (String name : c.getNames()) {
                    String cleanName = name.startsWith("/") ? name.substring(1) : name;
                    if (cleanName.equalsIgnoreCase(serviceName) || cleanName.contains(serviceName)) {
                        return c.getId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query Docker container list: {}", e.getMessage());
        }
        return null;
    }

    public double getCpuUsage(String serviceUrl) {
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