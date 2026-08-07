package com.selfhealing.monitor_service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class HealingLogController {

    @Autowired
    private HealingLogRepository healingLogRepo;

    @GetMapping("/healing-logs")
    public List<HealingLog> getLogs() {
        return healingLogRepo.findTop20ByOrderByDetectedAtDesc();
    }
}