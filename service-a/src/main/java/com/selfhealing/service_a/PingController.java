package com.selfhealing.service_a;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.List;

@RestController
public class PingController {

    private final List<byte[]> memoryHog = new ArrayList<>();
    private volatile boolean rateLimitEnabled = false;
    private volatile boolean slowMode = false;

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/health-check")
    public String health() {
        return "service-a is UP";
    }

    @PostMapping("/admin/rate-limit/enable")
    public String enableRateLimit() {
        rateLimitEnabled = true;
        return "Rate limiting enabled on service-a";
    }

    @PostMapping("/admin/rate-limit/disable")
    public String disableRateLimit() {
        rateLimitEnabled = false;
        return "Rate limiting disabled on service-a";
    }

    @PostMapping("/admin/slow/enable")
    public String enableSlowMode() {
        slowMode = true;
        return "Slow mode enabled on service-a";
    }

    @PostMapping("/admin/slow/disable")
    public String disableSlowMode() {
        slowMode = false;
        return "Slow mode disabled on service-a";
    }

    @GetMapping("/simulate/cpu")
    public String cpuSpike() {
        for (int i = 0; i < 4; i++) {
            new Thread(() -> { while (true) Math.random(); }).start();
        }
        return "CPU spike started on service-a";
    }

    @GetMapping("/simulate/memory")
    public String memoryLeak() {
        memoryHog.add(new byte[20 * 1024 * 1024]);
        return "Memory leak triggered on service-a. Total chunks: " + memoryHog.size();
    }

    @GetMapping("/simulate/crash")
    public void crash() {
        System.exit(1);
    }

    @GetMapping("/simulate/slow")
    public String slowApi() throws InterruptedException {
        if (slowMode) {
            Thread.sleep(5000);
            return "Slow response from service-a";
        }
        return "Normal response from service-a";
    }

    @GetMapping("/process")
    public String process() throws InterruptedException {
        if (rateLimitEnabled) {
            Thread.sleep(100);
            return "processed (rate limited)";
        }
        return "processed normally";
    }
}
