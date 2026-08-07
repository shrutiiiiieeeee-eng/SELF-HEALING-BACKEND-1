package com.selfhealing.monitor_service;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HealingLogRepository extends JpaRepository<HealingLog, Long> {
    List<HealingLog> findTop20ByOrderByDetectedAtDesc();
    long countByServiceNameAndDetectedAtAfter(String serviceName, java.time.LocalDateTime after);
}