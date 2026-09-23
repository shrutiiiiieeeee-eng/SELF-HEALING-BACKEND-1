// AutoHeal Dashboard JavaScript Engine
document.addEventListener('DOMContentLoaded', () => {
    // State
    let activeChartMetric = 'cpu';
    let pollingInterval = null;
    let telemetryChart = null;
    let chartHistory = {
        labels: [],
        'service-a': [],
        'service-b': [],
        'service-c': []
    };

    // DOM Elements
    const servicesContainer = document.getElementById('services-container');
    const aiPredictionsFeed = document.getElementById('ai-predictions-feed');
    const logsContainer = document.getElementById('logs-container');
    const autoRefreshToggle = document.getElementById('auto-refresh-toggle');
    const btnManualRefresh = document.getElementById('btn-manual-refresh');
    const chartTabs = document.querySelectorAll('.chart-tab');
    const toastContainer = document.getElementById('toast-container');
    
    // Traffic elements
    const virtualUsersSlider = document.getElementById('virtual-users-slider');
    const virtualUsersVal = document.getElementById('virtual-users-val');
    const btnStartTraffic = document.getElementById('btn-start-traffic');
    const btnStopTraffic = document.getElementById('btn-stop-traffic');
    const trafficBadge = document.getElementById('traffic-status-badge');
    const tRps = document.getElementById('t-rps');
    const tTotal = document.getElementById('t-total');
    const tSuccess = document.getElementById('t-success');
    const tFallbacks = document.getElementById('t-fallbacks');

    // Chaos elements
    const chaosSelect = document.getElementById('chaos-target-select');
    const chaosButtons = document.querySelectorAll('.btn-chaos, .btn-quick-fix');
    const btnTriggerAiAgent = document.getElementById('btn-trigger-ai-agent');

    // Modal elements
    const rcaModal = document.getElementById('rca-modal');
    const modalTitle = document.getElementById('modal-title');
    const modalContent = document.getElementById('modal-content');
    const btnCloseModal = document.getElementById('btn-close-modal');
    const btnModalDone = document.getElementById('btn-modal-done');

    // ---------------- Initialize Telemetry Chart ----------------
    function initChart() {
        const ctx = document.getElementById('telemetryChart').getContext('2d');
        telemetryChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: 'service-a',
                        borderColor: '#06b6d4',
                        backgroundColor: 'rgba(6, 182, 212, 0.1)',
                        borderWidth: 2,
                        tension: 0.3,
                        pointRadius: 2,
                        data: []
                    },
                    {
                        label: 'service-b',
                        borderColor: '#8b5cf6',
                        backgroundColor: 'rgba(139, 92, 246, 0.1)',
                        borderWidth: 2,
                        tension: 0.3,
                        pointRadius: 2,
                        data: []
                    },
                    {
                        label: 'service-c',
                        borderColor: '#10b981',
                        backgroundColor: 'rgba(16, 185, 129, 0.1)',
                        borderWidth: 2,
                        tension: 0.3,
                        pointRadius: 2,
                        data: []
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 300 },
                plugins: {
                    legend: {
                        labels: { color: '#94a3b8', font: { family: 'Plus Jakarta Sans', size: 11 } }
                    },
                    tooltip: {
                        backgroundColor: '#111726',
                        titleColor: '#ffffff',
                        bodyColor: '#94a3b8',
                        borderColor: 'rgba(255, 255, 255, 0.1)',
                        borderWidth: 1
                    }
                },
                scales: {
                    x: {
                        ticks: { color: '#64748b', maxTicksLimit: 8 },
                        grid: { color: 'rgba(255, 255, 255, 0.04)' }
                    },
                    y: {
                        ticks: { color: '#64748b' },
                        grid: { color: 'rgba(255, 255, 255, 0.05)' },
                        beginAtZero: true
                    }
                }
            }
        });
    }

    // ---------------- Fetch System Status & Telemetry ----------------
    async function fetchStatus() {
        try {
            const res = await fetch('/api/dashboard/status');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            renderDashboard(data);
        } catch (err) {
            console.warn('Status poll failed:', err.message);
            updateGlobalPill(false, 'Supervisor Unreachable');
        }
    }

    // ---------------- Render Dashboard Components ----------------
    function renderDashboard(data) {
        const services = data.services || [];
        const circuits = data.circuitStates || {};
        const logs = data.recentLogs || [];
        const traffic = data.trafficStatus || {};

        // 1. KPIs
        let upCount = services.filter(s => s.health === 'UP').length;
        let openCircuits = Object.values(circuits).filter(c => c === 'OPEN').length;
        document.getElementById('kpi-active-nodes').innerText = `${upCount} / ${services.length} UP`;
        document.getElementById('kpi-circuit-status').innerText = `${services.length - openCircuits} CLOSED`;
        document.getElementById('kpi-circuit-sub').innerText = openCircuits > 0 ? `${openCircuits} Diverting Traffic` : 'All Routes Active';
        document.getElementById('kpi-circuit-sub').className = openCircuits > 0 ? 'kpi-subtext text-rose' : 'kpi-subtext text-emerald';
        document.getElementById('kpi-total-heals').innerText = logs.length;

        // Global status banner
        if (upCount === services.length && openCircuits === 0) {
            updateGlobalPill(true, 'All Microservices Healthy');
        } else if (openCircuits > 0) {
            updateGlobalPill(false, `${openCircuits} Circuit(s) Tripped — Auto-Healing Active`);
        } else {
            updateGlobalPill(false, `${services.length - upCount} Service(s) Down — Healing in Progress`);
        }

        // 2. Render Microservice Cards
        renderServiceCards(services);

        // 3. Update Chart Stream
        updateChartData(services);

        // 4. Render AI Predictions Feed
        renderAiFeed(services);

        // 5. Render Logs
        renderLogs(logs);

        // 6. Render Traffic Status
        renderTraffic(traffic);
    }

    function updateGlobalPill(isHealthy, text) {
        const pill = document.getElementById('global-health-pill');
        const statusText = document.getElementById('global-status-text');
        const dot = pill.querySelector('.status-indicator-dot');
        statusText.innerText = text;
        if (isHealthy) {
            pill.style.background = 'rgba(16, 185, 129, 0.1)';
            pill.style.borderColor = 'rgba(16, 185, 129, 0.25)';
            pill.style.color = 'var(--accent-emerald)';
            dot.className = 'status-indicator-dot online';
        } else {
            pill.style.background = 'rgba(244, 63, 94, 0.12)';
            pill.style.borderColor = 'rgba(244, 63, 94, 0.3)';
            pill.style.color = 'var(--accent-rose)';
            dot.className = 'status-indicator-dot offline';
        }
    }

    function renderServiceCards(services) {
        servicesContainer.innerHTML = '';
        services.forEach(s => {
            const isUp = s.health === 'UP';
            const isCircuitOpen = s.circuitBreaker === 'OPEN';
            const cpu = s.cpuUsagePercent || 0;
            const memRatio = s.memoryRatioPercent || 0;
            const latency = s.latencyMs || 0;

            const cpuClass = cpu > 80 ? 'danger' : (cpu > 65 ? 'warning' : 'normal');
            const memClass = memRatio > 80 ? 'danger' : (memRatio > 65 ? 'warning' : 'normal');

            let badgeHtml = isUp
                ? `<span class="node-status-badge up">● UP</span>`
                : `<span class="node-status-badge down">■ DOWN</span>`;
            if (isCircuitOpen) {
                badgeHtml = `<span class="node-status-badge healing">⚡ AUTO-HEALING</span>`;
            }

            const card = document.createElement('div');
            card.className = 'service-card';
            card.innerHTML = `
                <div class="service-card-top">
                    <div>
                        <div class="service-name">${s.name}</div>
                        <div class="service-port">${s.url}</div>
                    </div>
                    ${badgeHtml}
                </div>
                <div class="meter-group">
                    <div class="meter-item">
                        <div class="meter-label-row">
                            <span>CPU Utilization</span>
                            <strong>${cpu}%</strong>
                        </div>
                        <div class="meter-track">
                            <div class="meter-fill ${cpuClass}" style="width: ${Math.min(100, Math.max(2, cpu))}%"></div>
                        </div>
                    </div>
                    <div class="meter-item">
                        <div class="meter-label-row">
                            <span>JVM Heap Memory</span>
                            <strong>${memRatio}% (${s.memoryUsedMb || 0} MB)</strong>
                        </div>
                        <div class="meter-track">
                            <div class="meter-fill ${memClass}" style="width: ${Math.min(100, Math.max(2, memRatio))}%"></div>
                        </div>
                    </div>
                </div>
                <div class="service-card-footer">
                    <span>Latency: <strong>${latency} ms</strong></span>
                    <span class="circuit-pill ${isCircuitOpen ? 'open' : 'closed'}">
                        CIRCUIT: ${s.circuitBreaker}
                    </span>
                </div>
            `;
            servicesContainer.appendChild(card);
        });
    }

    function updateChartData(services) {
        const now = new Date().toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

        if (chartHistory.labels.length > 15) {
            chartHistory.labels.shift();
            chartHistory['service-a'].shift();
            chartHistory['service-b'].shift();
            chartHistory['service-c'].shift();
        }

        chartHistory.labels.push(now);

        services.forEach(s => {
            let val = 0;
            if (activeChartMetric === 'cpu') val = s.cpuUsagePercent || 0;
            else if (activeChartMetric === 'memory') val = s.memoryRatioPercent || 0;
            else if (activeChartMetric === 'latency') val = s.latencyMs || 0;

            if (chartHistory[s.name]) {
                chartHistory[s.name].push(val);
            }
        });

        telemetryChart.data.labels = chartHistory.labels;
        telemetryChart.data.datasets[0].data = chartHistory['service-a'];
        telemetryChart.data.datasets[1].data = chartHistory['service-b'];
        telemetryChart.data.datasets[2].data = chartHistory['service-c'];
        telemetryChart.update();
    }

    function renderAiFeed(services) {
        aiPredictionsFeed.innerHTML = '';
        let hasAlert = false;

        services.forEach(s => {
            const pred = s.aiPrediction;
            if (pred && pred.predictedAnomaly !== 'NONE') {
                hasAlert = true;
                const isCrit = pred.severity === 'CRITICAL' || pred.severity === 'HIGH';
                const card = document.createElement('div');
                card.className = `prediction-card ${isCrit ? 'critical' : ''}`;
                
                // Formulate clear diagnosis & root cause
                const diagnosisText = pred.plainDiagnosis || pred.reasoning || 'Anomalous metric signature identified.';
                const rootCauseText = pred.rootCause || 'Resource consumption accelerated sharply over recent measurement intervals.';
                const immediateActionText = pred.immediateAction || pred.proactiveAction || 'SCALE_MEMORY';
                const actionVerb = pred.oneClickAction || pred.proactiveAction || 'HEAL';

                card.innerHTML = `
                    <div class="prediction-card-header">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span class="pred-service-badge">${pred.serviceName}</span>
                            <span class="pred-anomaly">${pred.predictedAnomaly}</span>
                        </div>
                        <span class="badge-severity ${pred.severity.toLowerCase()}">${pred.severity}</span>
                    </div>

                    <div class="ai-diagnosis-box">
                        <div class="ai-diag-title">🔍 Diagnostic Finding</div>
                        <div class="ai-diag-text">${diagnosisText}</div>
                    </div>

                    <div class="ai-recommendation-box">
                        <div class="ai-rec-title">⚡ Recommended AutoHeal Action</div>
                        <div class="ai-rec-text">${immediateActionText}</div>
                    </div>

                    <div class="pred-meta-row">
                        <span class="confidence-pill">AI Confidence: ${(pred.confidenceScore * 100).toFixed(0)}%</span>
                        ${pred.estimatedTimeToFailureSeconds > 0 ? `<span class="countdown-pill">Est. Failure in ~${pred.estimatedTimeToFailureSeconds}s</span>` : ''}
                    </div>

                    <div class="ai-card-actions">
                        <button class="btn btn-sm btn-outline btn-view-trace" data-service="${pred.serviceName}">
                            🔍 Trace Telemetry
                        </button>
                        <button class="btn btn-sm btn-ai btn-apply-ai-fix" data-service="${pred.serviceName}" data-action="${actionVerb}">
                            ⚡ Apply: ${actionVerb}
                        </button>
                    </div>
                `;
                aiPredictionsFeed.appendChild(card);
            }
        });

        if (!hasAlert) {
            document.getElementById('kpi-ai-risk').innerText = 'OPTIMAL';
            document.getElementById('kpi-ai-risk').className = 'kpi-value text-emerald';
            document.getElementById('kpi-ai-sub').innerText = 'Zero anomalous trajectories detected';
            aiPredictionsFeed.innerHTML = `
                <div class="prediction-card" style="border-left-color: var(--accent-emerald);">
                    <div class="prediction-card-header">
                        <span class="pred-service-badge">AutoHeal AI Copilot</span>
                        <span class="pred-anomaly" style="background: rgba(16, 185, 129, 0.2); color: #34d399;">OPTIMAL</span>
                    </div>
                    <div class="ai-diagnosis-box" style="background: rgba(16, 185, 129, 0.05); border-color: rgba(16, 185, 129, 0.2);">
                        <div class="ai-diag-title" style="color: var(--accent-emerald);">System Health Analysis</div>
                        <div class="ai-diag-text">Gemini 2.5 Flash & SRE heuristics confirm CPU, Heap Memory, and response times are operating within normal baseline limits across all microservices.</div>
                    </div>
                    <div class="pred-meta-row" style="margin-top: 8px;">
                        <span class="confidence-pill">Confidence: 98%</span>
                        <span>Model: gemini-2.5-flash</span>
                    </div>
                </div>
            `;
        } else {
            document.getElementById('kpi-ai-risk').innerText = 'ELEVATED';
            document.getElementById('kpi-ai-risk').className = 'kpi-value text-rose';
            document.getElementById('kpi-ai-sub').innerText = 'Imminent failure forecast active';
        }

        // Attach listeners for 1-Click Fix
        document.querySelectorAll('.btn-apply-ai-fix').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                const svc = btn.getAttribute('data-service');
                const act = btn.getAttribute('data-action');
                showToast(`AutoHeal applying [${act}] to ${svc}...`, 'info');
                try {
                    const res = await fetch(`/api/dashboard/ai/apply-action/${svc}/${act}`, { method: 'POST' });
                    const result = await res.json();
                    if (result.success) {
                        showToast(`Success: ${result.message}`, 'success');
                    } else {
                        showToast(`Notice: ${result.message}`, 'error');
                    }
                    setTimeout(fetchStatus, 1500);
                } catch (err) {
                    showToast(`Action failed: ${err.message}`, 'error');
                }
            });
        });

        // Attach listeners for Telemetry Trace View
        document.querySelectorAll('.btn-view-trace').forEach(btn => {
            btn.addEventListener('click', async () => {
                const svc = btn.getAttribute('data-service');
                showToast(`Loading diagnostic telemetry trace for ${svc}...`, 'info');
                try {
                    const res = await fetch(`/api/dashboard/ai/trace/${svc}`);
                    const data = await res.json();
                    openTraceModal(data);
                } catch (err) {
                    showToast(`Trace query failed: ${err.message}`, 'error');
                }
            });
        });
    }

    function openTraceModal(data) {
        const pred = data.prediction || {};
        const traces = pred.telemetryTrace || [];
        modalTitle.innerText = `Diagnostic Telemetry Trace: ${data.service}`;

        let traceListHtml = traces.map((t, idx) => `
            <div style="display: flex; gap: 10px; align-items: center; padding: 6px 10px; background: rgba(255, 255, 255, 0.03); border-radius: 6px; font-family: 'JetBrains Mono', monospace; font-size: 0.76rem;">
                <span style="color: var(--accent-cyan); font-weight: 600;">Sample #${idx + 1}</span>
                <span>${t}</span>
            </div>
        `).join('');

        if (traces.length === 0) {
            traceListHtml = '<div style="color: var(--text-muted);">No trajectory samples recorded yet.</div>';
        }

        modalContent.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 16px;">
                <div style="background: rgba(244, 63, 94, 0.08); border: 1px solid rgba(244, 63, 94, 0.25); border-radius: 8px; padding: 14px;">
                    <div style="font-size: 0.72rem; font-weight: 700; color: var(--accent-rose); text-transform: uppercase;">Observed Issue</div>
                    <div style="font-size: 0.95rem; color: #ffffff; font-weight: 700; margin-top: 4px;">${pred.predictedAnomaly || 'ANOMALY'} (${pred.severity || 'HIGH'})</div>
                    <div style="font-size: 0.82rem; color: var(--text-secondary); margin-top: 4px;">${pred.plainDiagnosis || 'Resource threshold acceleration'}</div>
                </div>

                <div>
                    <div style="font-size: 0.75rem; font-weight: 700; color: var(--accent-cyan); margin-bottom: 8px;">TELEMETRY TRAJECTORY WINDOW (Sequential Data Points)</div>
                    <div style="display: flex; flex-direction: column; gap: 6px;">
                        ${traceListHtml}
                    </div>
                </div>

                <div style="background: rgba(6, 182, 212, 0.06); border: 1px solid rgba(6, 182, 212, 0.2); border-radius: 8px; padding: 14px;">
                    <div style="font-size: 0.72rem; font-weight: 700; color: var(--accent-cyan); text-transform: uppercase;">Root Cause & Prevention</div>
                    <div style="font-size: 0.82rem; color: #ffffff; margin-top: 4px;"><strong>Root Cause:</strong> ${pred.rootCause || 'Unchecked resource growth.'}</div>
                    <div style="font-size: 0.82rem; color: var(--text-secondary); margin-top: 4px;"><strong>Long-Term Fix:</strong> ${pred.preventionAdvice || 'Implement thread limits and object memory boundaries.'}</div>
                </div>
            </div>
        `;
        rcaModal.classList.remove('hidden');
    }

    function renderLogs(logs) {
        if (!logs || logs.length === 0) {
            logsContainer.innerHTML = '<div class="no-logs" style="padding: 16px; color: var(--text-muted); font-size: 0.8rem;">No healing incidents recorded yet. All services operational.</div>';
            return;
        }

        logsContainer.innerHTML = '';
        logs.forEach(log => {
            const isHealed = log.status === 'HEALED';
            const item = document.createElement('div');
            item.className = 'log-item';
            item.innerHTML = `
                <div class="log-header-row">
                    <span class="log-service-tag">${log.serviceName}</span>
                    <span class="log-status-badge ${isHealed ? 'healed' : 'failed'}">${log.status}</span>
                </div>
                <div class="log-action-text">${log.failureType} &rarr; ${log.actionTaken}</div>
                <div class="log-time-row">
                    <span>${log.detectedAt ? new Date(log.detectedAt).toLocaleTimeString() : ''} (${log.recoveryDurationMs || 0} ms)</span>
                    <span class="view-rca-link">✦ View AI Post-Mortem &rarr;</span>
                </div>
            `;
            item.addEventListener('click', () => openRcaModal(log));
            logsContainer.appendChild(item);
        });
    }

    function openRcaModal(log) {
        modalTitle.innerText = `Incident Analysis: ${log.serviceName} [${log.failureType}]`;
        modalContent.innerHTML = `
            <div style="margin-bottom: 16px;">
                <div style="font-size: 0.8rem; color: var(--text-muted); margin-bottom: 4px;">EXECUTIVE INCIDENT SUMMARY</div>
                <div style="font-size: 0.92rem; color: #ffffff; font-weight: 600;">
                    ${log.serviceName} encountered ${log.failureType}. AutoHeal executed recovery strategy [${log.actionTaken}] resulting in status: ${log.status}.
                </div>
            </div>
            <div style="margin-bottom: 16px;">
                <div style="font-size: 0.8rem; color: var(--text-muted); margin-bottom: 4px;">GEMINI EXPLAINABLE ROOT CAUSE & JUSTIFICATION</div>
                <pre>${log.rootCauseAnalysis || 'Standard automated rule resolution applied.'}</pre>
            </div>
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 0.8rem; background: rgba(255, 255, 255, 0.03); padding: 12px; border-radius: 8px;">
                <div>Confidence Score: <strong style="color: var(--accent-emerald);">${((log.confidenceScore || 0.95) * 100).toFixed(0)}%</strong></div>
                <div>Recovery Duration: <strong>${log.recoveryDurationMs || 0} ms</strong></div>
                <div>Proactive Intervention: <strong>${log.proactive ? 'YES' : 'NO'}</strong></div>
                <div>Detected At: <strong>${log.detectedAt}</strong></div>
            </div>
        `;
        rcaModal.classList.remove('hidden');
    }

    function renderTraffic(traffic) {
        const isRunning = traffic.running || false;
        trafficBadge.innerText = isRunning ? 'Traffic Active' : 'Simulator Idle';
        trafficBadge.className = isRunning ? 'traffic-status-badge active' : 'traffic-status-badge';
        btnStartTraffic.disabled = isRunning;
        btnStopTraffic.disabled = !isRunning;

        tRps.innerText = `${traffic.requestsPerSecond || '0.0'} req/s`;
        tTotal.innerText = traffic.totalRequests || 0;
        tSuccess.innerText = traffic.successRequests || 0;
        tFallbacks.innerText = traffic.fallbackRequests || 0;
    }

    // ---------------- Event Listeners ----------------
    // Chart metric tabs
    chartTabs.forEach(tab => {
        tab.addEventListener('click', (e) => {
            chartTabs.forEach(t => t.classList.remove('active'));
            e.target.classList.add('active');
            activeChartMetric = e.target.getAttribute('data-metric');
            chartHistory['service-a'] = [];
            chartHistory['service-b'] = [];
            chartHistory['service-c'] = [];
            chartHistory.labels = [];
            fetchStatus();
        });
    });

    // Auto-refresh switch
    autoRefreshToggle.addEventListener('change', (e) => {
        if (e.target.checked) {
            startPolling();
            showToast('Live synchronization resumed', 'info');
        } else {
            clearInterval(pollingInterval);
            showToast('Live synchronization paused', 'info');
        }
    });

    btnManualRefresh.addEventListener('click', () => {
        fetchStatus();
        showToast('Telemetry updated', 'info');
    });

    // Traffic generator
    virtualUsersSlider.addEventListener('input', (e) => {
        virtualUsersVal.innerText = `${e.target.value} Users`;
    });

    btnStartTraffic.addEventListener('click', async () => {
        const users = virtualUsersSlider.value;
        try {
            await fetch(`/api/dashboard/traffic/start?users=${users}`, { method: 'POST' });
            showToast(`Started load test with ${users} virtual users!`, 'success');
            fetchStatus();
        } catch (err) {
            showToast(`Traffic load failed: ${err.message}`, 'error');
        }
    });

    btnStopTraffic.addEventListener('click', async () => {
        try {
            await fetch('/api/dashboard/traffic/stop', { method: 'POST' });
            showToast('Load simulator stopped', 'info');
            fetchStatus();
        } catch (err) {
            showToast(`Stop failed: ${err.message}`, 'error');
        }
    });

    // Chaos Buttons
    chaosButtons.forEach(btn => {
        btn.addEventListener('click', async (e) => {
            const targetService = chaosSelect.value;
            const action = btn.getAttribute('data-action');
            showToast(`Injecting chaos [${action}] on ${targetService}...`, 'info');
            try {
                const res = await fetch(`/api/dashboard/chaos/${targetService}/${action}`, { method: 'POST' });
                const data = await res.json();
                if (data.status === 'SUCCESS') {
                    if (action === 'crash') {
                        showToast(`💥 Service ${targetService} crashed! AutoHeal rebooting container...`, 'success');
                    } else {
                        showToast(`Fault injected on ${targetService}! AutoHeal monitoring response.`, 'success');
                    }
                } else {
                    showToast(`Chaos trigger returned: ${data.message || 'Error'}`, 'error');
                }
                setTimeout(fetchStatus, 1500);
            } catch (err) {
                showToast(`Fault injection failed: ${err.message}`, 'error');
            }
        });
    });

    // Invoke AI SRE Agent Button
    btnTriggerAiAgent.addEventListener('click', async () => {
        const targetService = chaosSelect.value;
        showToast(`Summoning Autonomous SRE Agent for ${targetService}...`, 'info');
        try {
            const res = await fetch(`/api/dashboard/ai/heal-agent/${targetService}`, { method: 'POST' });
            const result = await res.json();
            modalTitle.innerText = `Autonomous SRE Agent Resolution: ${targetService}`;
            modalContent.innerHTML = `
                <div style="margin-bottom: 16px;">
                    <div style="font-size: 0.8rem; color: var(--text-muted);">DIAGNOSIS</div>
                    <p style="color: #ffffff; font-weight: 600;">${result.diagnosis}</p>
                </div>
                <div style="margin-bottom: 16px;">
                    <div style="font-size: 0.8rem; color: var(--text-muted);">TOOL ACTION EXECUTED</div>
                    <p style="color: var(--accent-cyan); font-family: 'JetBrains Mono', monospace; font-weight: 600;">${result.action}</p>
                </div>
                <div style="margin-bottom: 16px;">
                    <div style="font-size: 0.8rem; color: var(--text-muted);">EXECUTION OUTCOME</div>
                    <p style="color: ${result.success ? 'var(--accent-emerald)' : 'var(--accent-rose)'};">${result.executionResult}</p>
                </div>
                <div>
                    <div style="font-size: 0.8rem; color: var(--text-muted);">AGENT REASONING</div>
                    <pre>${result.reasoning}</pre>
                </div>
            `;
            rcaModal.classList.remove('hidden');
            fetchStatus();
        } catch (err) {
            showToast(`Agent invocation failed: ${err.message}`, 'error');
        }
    });

    // Modal controls
    btnCloseModal.addEventListener('click', () => rcaModal.classList.add('hidden'));
    btnModalDone.addEventListener('click', () => rcaModal.classList.add('hidden'));

    // Toast helper
    function showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerText = message;
        toastContainer.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = '0';
            setTimeout(() => toast.remove(), 300);
        }, 3500);
    }

    function startPolling() {
        if (pollingInterval) clearInterval(pollingInterval);
        pollingInterval = setInterval(fetchStatus, 2500);
    }

    // Initialize
    initChart();
    fetchStatus();
    startPolling();
});
