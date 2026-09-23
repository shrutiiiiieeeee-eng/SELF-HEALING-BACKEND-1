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

    @Lob
    @Column(length = 4000)
    private String rootCauseAnalysis;

    private Double confidenceScore;
    private Long recoveryDurationMs;
    private Boolean proactive;

    public HealingLog() {}

    public HealingLog(String serviceName, String failureType, String actionTaken, String status) {
        this(serviceName, failureType, actionTaken, status, "Automated rule-based resolution", 1.0, 0L, false);
    }

    public HealingLog(String serviceName, String failureType, String actionTaken, String status,
                      String rootCauseAnalysis, Double confidenceScore, Long recoveryDurationMs, Boolean proactive) {
        this.serviceName = serviceName;
        this.failureType = failureType;
        this.actionTaken = actionTaken;
        this.detectedAt = LocalDateTime.now();
        this.status = status;
        this.rootCauseAnalysis = rootCauseAnalysis;
        this.confidenceScore = confidenceScore != null ? confidenceScore : 1.0;
        this.recoveryDurationMs = recoveryDurationMs != null ? recoveryDurationMs : 0L;
        this.proactive = proactive != null ? proactive : false;
    }

    // getters and setters
    public Long getId() { return id; }
    public String getServiceName() { return serviceName; }
    public String getFailureType() { return failureType; }
    public String getActionTaken() { return actionTaken; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public String getStatus() { return status; }
    public String getRootCauseAnalysis() { return rootCauseAnalysis; }
    public void setRootCauseAnalysis(String rootCauseAnalysis) { this.rootCauseAnalysis = rootCauseAnalysis; }
    public Double getConfidenceScore() { return confidenceScore; }
    public Long getRecoveryDurationMs() { return recoveryDurationMs; }
    public Boolean getProactive() { return proactive; }
}