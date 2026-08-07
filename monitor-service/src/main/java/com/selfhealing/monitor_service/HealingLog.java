package com.selfhealing.monitor_service;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class HealingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceName;
    private String failureType;
    private String actionTaken;
    private LocalDateTime detectedAt;
    private String status; // "HEALED" or "FAILED"

    public HealingLog() {}

    public HealingLog(String serviceName, String failureType, String actionTaken, String status) {
        this.serviceName = serviceName;
        this.failureType = failureType;
        this.actionTaken = actionTaken;
        this.detectedAt = LocalDateTime.now();
        this.status = status;
    }

    // getters
    public Long getId() { return id; }
    public String getServiceName() { return serviceName; }
    public String getFailureType() { return failureType; }
    public String getActionTaken() { return actionTaken; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public String getStatus() { return status; }
}