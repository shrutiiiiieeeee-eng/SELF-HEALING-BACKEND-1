# AutoHeal: Autonomous Self-Healing Microservices Backend & AI SRE Platform

An enterprise-grade, autonomous self-healing backend platform built with **Spring Boot 3**, **Google Gemini 2.5 Flash**, **Docker**, **Prometheus**, and an interactive real-time **Observability & Chaos Engineering Dashboard**.

The system continuously monitors distributed microservices, uses **Gemini AI** for predictive crash forecasting and explainable Root Cause Analysis (RCA), and applies automated multi-tier remediation strategies (traffic rate-limiting, dynamic RAM scaling, circuit breaking, and Docker container restarts) without human intervention.

---

## 1. System Architecture & How It Works

```
                                      +---------------------------------------------+
                                      |    AutoHeal Dashboard (http://localhost:8085) |
                                      | - Real-Time Node Health Grid                |
                                      | - Streaming Chart.js Telemetry              |
                                      | - Chaos Control Deck & Traffic Generator     |
                                      | - Gemini Predictive Advisory & RCA Modal    |
                                      +----------------------+----------------------+
                                                             |
                                           REST API & Gateway Requests
                                                             v
+---------------------------------------------------------------------------------------------------------+
| monitor-service (Port 8085)                                                                            |
|                                                                                                         |
|  +--------------------+        +---------------------+      +-----------------+   +------------------+  |
|  | GatewayController  | <----> |   Circuit Breaker   |      | HealingLogRepo  |   | TrafficSimulator |  |
|  | (/gateway/...)     |        | (OPEN/CLOSED State) |      |  (H2 Database)  |   |  (Virtual Users) |  |
|  +--------------------+        +---------------------+      +--------+--------+   +------------------+  |
|                                                                      ^                                  |
|  +--------------------+                     +--------------------+   |    +--------------------------+  |
|  |  FailureDetector   | ---(Every 10s)----> |   HealingEngine    |---+    |     Gemini AI Engine     |  |
|  | (Health,CPU,Mem,MS)|                     | (Scale/Restart/RL) |        | - Predictive Forecast    |  |
|  +---------+----------+                     +---------+----------+        | - Explainable RCA        |  |
|            |                                          |                   | - Autonomous SRE Agent   |  |
|            | Rolling Telemetry                        | Docker Engine API +--------------------------+  |
|            v                                          v                                                 |
|  +----------------------------+                                                                         |
|  |  PredictiveAnomalyEngine   | ------------------------------------------------------------------------+
|  |  (Linear slope regression) |
|  +----------------------------+
+------------|------------------------------------------|-------------------------------------------------+
             |                                          |
     Polls /actuator/metrics                    (restart / memory scale)
             |                                          |
             v                                          v
+--------------------------+       +--------------------------+       +--------------------------+
|        service-a         |       |        service-b         |       |        service-c         |
|       (Port 8081)        |       |       (Port 8082)        |       |       (Port 8083)        |
| - Spring Boot Actuator   |       | - Spring Boot Actuator   |       | - Spring Boot Actuator   |
| - /health-check, /ping   |       | - Chaos Simulation APIs  |       | - Chaos Simulation APIs  |
+--------------------------+       +--------------------------+       +--------------------------+
             ^                                  ^                                  ^
             |                                  |                                  |
             +----------------------------------+----------------------------------+
                                                | Scrapes metrics every 10s
                                      +---------+---------+
                                      |    Prometheus     | (Port 9090)
                                      +---------+---------+
                                                | Visualizes dashboards
                                      +---------v---------+
                                      |      Grafana      | (Port 3000)
                                      +-------------------+
```

---

## 2. Key Capabilities & Features

### 1. Real-Time Visual Dashboard (`http://localhost:8085/`)
- **Zero-Dependency SPA**: Built with modern semantic HTML5, glassmorphic CSS3, and vanilla JavaScript. Served directly by `monitor-service`.
- **Live Node Topology**: Real-time status cards (UP, DOWN, HEALING) and Circuit Breaker states (`CLOSED` / `OPEN`).
- **Telemetry Streaming Charts**: Live Chart.js graphs displaying CPU (%), Memory (%), and Latency (ms) trajectories.
- **Chaos Engineering Lab**: 1-click fault injection (CPU spike, memory leak, crash, latency toggle, rate-limiting toggle).
- **Multi-User Traffic Simulator**: Slider for 1–60 concurrent virtual users simulating high-traffic loads and routing through the Gateway (`req.txt` Objective 8).

### 2. Gemini 2.5 Flash AI Integration
- **Smart Future Predictions (`PredictiveAnomalyEngine`)**:
  - Analyzes multi-interval metric trends ($\Delta \text{Memory}/\Delta t$, $\Delta \text{CPU}/\Delta t$).
  - Invokes Gemini 2.5 Flash structured JSON mode to forecast crashes 30–60 seconds **before** they occur.
  - Outputs risk severity (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), confidence score ($0.0 - 1.0$), and estimated time to failure.
  - Enables **Proactive Healing** (scaling memory or rate-limiting before the user ever experiences an outage).
- **Explainable Root Cause Analysis (`AiRcaService`)**:
  - Fulfills `req.txt` Objective 6 ("generate explainable logs detailing recovery actions and their reasons").
  - On every healing event, Gemini generates an incident post-mortem explaining:
    1. Root Cause Analysis.
    2. Healing Strategy Justification.
    3. Prevention Recommendations.
- **Autonomous Remediation SRE Agent (`HealingAgent`)**:
  - Secondary escalation specialist for unclassified or recurring anomalies.
  - Uses tool-calling routines (`inspectMetrics`, `scaleMemory`, `restartContainer`, `applyRateLimit`, `verifyHealth`).

### 3. Multi-Level Deterministic Healing Strategies
- **CPU Spikes**: Applies endpoint rate-limiting; escalates to container restart if CPU remains $> 80\%$.
- **Memory Leaks**: Dynamically increases Docker container RAM limits to 512 MB / 1024 MB swap via Docker API; escalates to restart if heap pressure persists.
- **Service Outages**: Trips circuit breaker to `OPEN` (returns cached fallback responses), restarts container via Docker API, verifies `/actuator/health`, and closes the circuit breaker (`CLOSED`).
- **High Latency**: Trips circuit breaker, executes restart, verifies latency drops below 1500 ms.
- **Cooldown Protection**: Limits healing to at most 3 actions per 5-minute rolling window per service to avoid restart loops.

---

## 3. Tech Stack

| Layer | Technologies Used | Details |
|---|---|---|
| **Backend & Services** | Java 17+, Spring Boot 3.5.14 | Spring Web, Spring Actuator, Spring Data JPA |
| **Artificial Intelligence** | Google Gemini 2.5 Flash | Structured JSON anomaly forecasting & Explainable RCA |
| **Frontend UI** | HTML5, Vanilla CSS3, JavaScript, Chart.js | Glassmorphism SPA hosted at `http://localhost:8085/` |
| **Containerization** | Docker, Docker Compose | Multi-container bridge network (`healing-net`) |
| **Container API Client** | `docker-java` 3.3.4 | Apache HttpClient5 transport (`5.5.2`) |
| **Database** | H2 Database | Embedded in-memory audit log repository (`jdbc:h2:mem:healingdb`) |
| **Observability** | Micrometer, Prometheus, Grafana | Metric scraping every 10s; Grafana dashboards on port 3000 |

---

## 4. Project Structure

```
self_healing/
└── SELF-HEALING-BACKEND-1/
    ├── docker-compose.yml              # Multi-container orchestration (6 services)
    ├── prometheus.yml                  # Prometheus scrape configuration
    ├── monitor-service/                # Central brain: detector, AI engine, healing engine, UI
    │   ├── pom.xml                     # Spring Boot, docker-java, JPA, H2
    │   └── src/
    │       ├── main/
    │       │   ├── java/com/selfhealing/monitor_service/
    │       │   │   ├── MonitorServiceApplication.java   # Spring Boot entry point
    │       │   │   ├── FailureDetector.java             # Polling detector (10s) with AI ingestion
    │       │   │   ├── HealingEngine.java               # Rule-based Docker actions & DB persistence
    │       │   │   ├── GatewayController.java           # Intelligent proxy with Circuit Breaker
    │       │   │   ├── DashboardController.java         # Unified dashboard REST endpoints
    │       │   │   ├── TrafficSimulatorService.java     # Multi-user traffic generator
    │       │   │   ├── HealingLog.java                  # Audit entity with RCA & confidence fields
    │       │   │   ├── HealingLogRepository.java        # JPA repository with cooldown queries
    │       │   │   ├── HealingLogController.java        # GET /healing-logs endpoint
    │       │   │   └── ai/
    │       │   │       ├── GeminiClient.java            # Google Gemini 2.5 Flash API connector
    │       │   │       ├── PredictiveAnomalyEngine.java # Time-series trend analysis & predictions
    │       │   │       ├── AiRcaService.java            # Explainable Root Cause Analysis generator
    │       │   │       └── HealingAgent.java            # Autonomous tool-based SRE agent
    │       │   └── resources/
    │       │       ├── application.properties           # Configuration: ports, Gemini key, Docker
    │       │       └── static/                          # High-performance SPA dashboard
    │       │           ├── index.html                   # Semantic HTML5 UI layout
    │       │           ├── styles.css                   # Dark-mode glassmorphic styling
    │       │           └── app.js                       # Live polling, Chart.js, chaos triggers
    │       └── test/java/com/selfhealing/monitor_service/
    │           ├── MonitorServiceApplicationTests.java  # Spring Boot context smoke test
    │           └── AiServicesTest.java                  # Gemini, predictive, & audit persistence tests
    ├── service-a/                                       # Target Microservice A (Core)
    ├── service-b/                                       # Target Microservice B (Simulator)
    └── service-c/                                       # Target Microservice C (Simulator)
```

---

## 5. Quickstart: How to Run the Platform

### Option A: Run via Docker Compose (Recommended)
Make sure Docker Desktop is active on your machine.

1. **Navigate to the project directory**:
   ```bash
   cd SELF-HEALING-BACKEND-1
   ```

2. **Build and launch the complete stack**:
   ```bash
   docker-compose up --build -d
   ```

3. **Open the AutoHeal Dashboard**:
   Open your browser to: **[http://localhost:8085/](http://localhost:8085/)**

4. **Verify Additional Endpoints**:
   - Prometheus: [http://localhost:9090](http://localhost:9090)
   - Grafana: [http://localhost:3000](http://localhost:3000) (User: `admin` / Password: `admin`)
   - H2 Console: [http://localhost:8085/h2-console](http://localhost:8085/h2-console)

5. **Tear down**:
   ```bash
   docker-compose down
   ```

---

### Option B: Run Locally (Native Development)
You can run the microservices natively on your machine:

1. **Terminal 1: Start `service-a`**:
   ```powershell
   cd SELF-HEALING-BACKEND-1\service-a
   .\mvnw.cmd spring-boot:run
   ```

2. **Terminal 2: Start `service-b`**:
   ```powershell
   cd SELF-HEALING-BACKEND-1\service-b
   .\mvnw.cmd spring-boot:run
   ```

3. **Terminal 3: Start `service-c`**:
   ```powershell
   cd SELF-HEALING-BACKEND-1\service-c
   .\mvnw.cmd spring-boot:run
   ```

4. **Terminal 4: Start `monitor-service`**:
   ```powershell
   cd SELF-HEALING-BACKEND-1\monitor-service
   .\mvnw.cmd spring-boot:run
   ```

5. Open your browser at **[http://localhost:8085/](http://localhost:8085/)**.

---

## 6. How to Test Self-Healing & AI Predictions

### Step 1: Open the Dashboard
Navigate to `http://localhost:8085/`. You will see all 3 microservices with live CPU, Memory, and Latency gauges.

### Step 2: Trigger a Memory Leak & Observe AI Prediction
1. On the **Chaos Injection Deck** (right panel), select `service-b`.
2. Click **Memory Leak** multiple times.
3. Watch the Memory graph climb on the **Telemetry Visualizer**.
4. Observe the **Gemini AI Advisory Center**:
   - As the memory trend slope accelerates, Gemini flags `MEMORY_LEAK` with high confidence ($> 85\%$) and a failure countdown timer.
   - Click **Execute Proactive HEAL** or let the automated rule engine dynamically scale memory when it exceeds the threshold.

### Step 3: Trigger a Service Crash & Observe Circuit Breaker Fallback
1. Select `service-b` and click **Crash Service**.
2. Within 10 seconds:
   - The status badge transitions to `DOWN` / `HEALING`.
   - The Circuit Breaker trips to `OPEN`.
   - External calls to `http://localhost:8085/gateway/service-b/process` immediately receive a graceful fallback response:
     ```json
     {
       "status": "FALLBACK",
       "circuit": "OPEN",
       "message": "service-b is temporarily unavailable. Circuit is OPEN; serving graceful fallback.",
       "data": { "result": "cached-data-service-b" }
     }
     ```
   - The healing engine triggers a container reboot, verifies `/actuator/health`, and closes the circuit (`CLOSED`).

### Step 4: Inspect Explainable AI Root Cause Analysis (RCA)
1. In the **Explainable AI Recovery Audit** section, click on the recorded healing entry.
2. A post-mortem modal will appear displaying Gemini's analysis:
   - **Root Cause**: Why the issue occurred.
   - **Strategy Justification**: Why the remediation was chosen.
   - **Prevention Recommendation**: Architectural recommendation to eliminate recurrence.

### Step 5: Test Multi-User Traffic Load
1. Under **Multi-User Traffic Simulator**, set the slider to 20 or 40 concurrent users.
2. Click **Start Traffic Load**.
3. Watch the real-time RPS counter, total requests, and fallback counts update live as you inject faults!

---

## 7. Running Automated Tests

Run the test suite inside `monitor-service` to verify that all Spring context, Gemini AI connectivity, predictive anomaly buffers, and H2 database persistence tests pass:

```powershell
cd SELF-HEALING-BACKEND-1\monitor-service
.\mvnw.cmd test
```
