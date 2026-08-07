package com.selfhealing.servicec;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.List;

@RestController
public class SimulatorController {

    private final List<byte[]> memoryHog = new ArrayList<>();

    // ✅ Added
    private volatile boolean rateLimitEnabled = false;

    // ✅ Added
    @PostMapping("/admin/rate-limit/enable")
    public String enableRateLimit() {
        rateLimitEnabled = true;
        return "Rate limiting enabled on " + System.getenv("SPRING_APPLICATION_NAME");
    }

    // ✅ Added
    @PostMapping("/admin/rate-limit/disable")
    public String disableRateLimit() {
        rateLimitEnabled = false;
        return "Rate limiting disabled";
    }

    @GetMapping("/simulate/cpu")
    public String cpuSpike() {
        for (int i = 0; i < 4; i++) {
            new Thread(() -> { while (true) Math.random(); }).start();
        }
        return "CPU spike started on service-c";
    }

    @GetMapping("/simulate/memory")
    public String memoryLeak() {
        memoryHog.add(new byte[20 * 1024 * 1024]);
        return "Memory leak triggered on service-c. Total chunks: " + memoryHog.size();
    }

    @GetMapping("/simulate/crash")
    public void crash() {
        System.exit(1);
    }

    @GetMapping("/simulate/slow")
    public String slowApi() throws InterruptedException {
        Thread.sleep(5000);
        return "Slow response from service-c";
    }

    // ✅ Added
    @GetMapping("/process")
    public String process() throws InterruptedException {
        if (rateLimitEnabled) {
            Thread.sleep(100); // artificial throttle
            return "processed (rate limited)";
        }
        return "processed normally";
    }

    @GetMapping("/health-check")
    public String health() {
        return "service-c is UP";
    }
}