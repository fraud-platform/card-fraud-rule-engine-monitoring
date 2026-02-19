# E2E & Load Testing Guide

**Purpose:** Guide for running E2E and load tests using the shared `card-fraud-e2e-load-testing` repository.

**Last Updated:** 2026-02-15

---

## Overview

The Card Fraud Rule Engine uses the centralized **card-fraud-e2e-load-testing** repository for all E2E and load testing. This avoids duplication and ensures consistency across all card fraud detection services.

**Repository:** sibling repo `../card-fraud-e2e-load-testing`

This repository provides the service under test (MONITORING). AUTH load tests target the sibling AUTH service.

### Key Features

| Feature | Description |
|----------|-------------|
| **Locust-based** | Industry-standard load testing framework |
| **Multiple Scenarios** | Smoke, Baseline, Stress, Soak, Spike |
| **Test Data Generation** | Transactions, Users, Rules |
| **Reporting** | HTML reports, Grafana dashboards |
| **CI/CD Integration** | GitHub Actions workflow templates |

---

## Quick Start

### 1. Navigate to Load Testing Repository

```bash
cd ../card-fraud-e2e-load-testing
```

### 2. Install Dependencies

```bash
# Install uv (fast Python package manager)
curl -sSL https://astral.sh/uv | bash

# Install dependencies
uv sync --extra load-test
```

### 3. Run Load Tests

```bash
# Interactive Web UI (http://localhost:8089)
uv run lt-web

# Rule Engine Load Test (headless)
uv run lt-rule-engine --users=1000 --spawn-rate=100 --run-time=5m

# With authentication modes
```

Notes:
- For split services, prefer explicit targeting via env vars (see Configuration below).
- For acceptance-quality numbers, run the service in a production-like container (OpenShift pods or Docker Compose). Avoid Quarkus dev-mode as a performance signal.

---

## Blessed Commands (Local Baseline)

These are the “known-good” commands for a local, production-like baseline (containers run the packaged artifact; load is generated externally from your host).

1. Start the platform apps (packaged container runtime):

```bash
cd ../card-fraud-platform
doppler run -- uv run platform-up -- --apps --build --force-recreate
```

2. Run AUTH baseline (50 users, 2m):

```bash
cd ../card-fraud-e2e-load-testing
export RULE_ENGINE_AUTH_URL="http://localhost:8081"
export RULE_ENGINE_MONITORING_URL="http://localhost:8082"

uv run lt-rule-engine --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless
```

3. Run MONITORING baseline (50 users, 2m):

```bash
cd ../card-fraud-e2e-load-testing
export RULE_ENGINE_AUTH_URL="http://localhost:8081"
export RULE_ENGINE_MONITORING_URL="http://localhost:8082"

uv run lt-rule-engine-monitoring --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless
```

Notes:
- Seed uploads rulesets to object storage, validates the uploaded keys are visible, then bulk-loads into the target registry.

---

## Test Scenarios

### Smoke Test (2 minutes)

Quick validation that the service is operational.

```bash
uv run lt-rule-engine --users=50 --spawn-rate=10 --run-time=2m --scenario=smoke
```

### Baseline Performance (10 minutes)

Establish baseline performance metrics.

```bash
uv run lt-rule-engine --users=1000 --spawn-rate=100 --run-time=10m --scenario=baseline
```

### Stress Test (30 minutes)

Find breaking point and measure degradation.

```bash
uv run lt-rule-engine --users=5000 --spawn-rate=500 --run-time=30m --scenario=stress
```

### Soak Test (1-24 hours)

Detect memory leaks, resource exhaustion.

```bash
uv run lt-rule-engine --users=1000 --spawn-rate=50 --run-time=1h --scenario=soak
```

### Spike Test (5 minutes)

Sudden traffic spike to test burst handling.

```bash
uv run lt-rule-engine --users=5000 --spawn-rate=1000 --run-time=5m --scenario=spike
```

---

## Target Metrics

Service-level targets are defined in the SLO docs and may differ by endpoint/profile.

- Monitoring SLO reference: `docs/06-operations/slos.md`
- Baseline tracking: `04-testing/load-testing-baseline.md`

---

## Configuration

### Environment Variables

```bash
# Split-service targeting (preferred)
export RULE_ENGINE_AUTH_URL="http://localhost:8081"
export RULE_ENGINE_MONITORING_URL="http://localhost:8082"

# Select which service the Locust user targets
# - "auth" (default)
# - "monitoring"
export RULE_ENGINE_MODE="monitoring"


# Optional - MinIO for artifact publishing
export MINIO_ENDPOINT="localhost:9000"
export MINIO_ACCESS_KEY="minioadmin"
export MINIO_SECRET_KEY="minioadmin"
```

Notes:
- Seeding uploads rulesets to object storage and then bulk-loads them into the target service registry so the load test hits the hot path.
- If you run AUTH-only tests, set `RULE_ENGINE_MODE=auth` (or leave unset) and `RULE_ENGINE_AUTH_URL` appropriately.

### Test Configuration

The test configuration is centralized in `card-fraud-e2e-load-testing`:

```python
RuleEngineConfig:
  # Target service
  # (selected by RULE_ENGINE_MODE and the *_URL env vars)

    # Load test parameters
    target_rps: 10000
    target_p50_ms: 5
    target_p95_ms: 15
    target_p99_ms: 30

    # User simulation
    min_wait: 1ms
    max_wait: 10ms

    # Task weights
    preauth_normal: 1        # Normal transactions
    preauth_high_value: 1    # High-value transactions
    preauth_suspicious: 1    # Suspicious transactions
    postauth_approve: 1     # Post-auth approval
    postauth_decline: 1     # Post-auth decline
    monitoring: 1           # Monitoring evaluation
```

---

## Authentication Modes

| Mode | Description | Use Case |
|------|-------------|----------|


   ```bash
   APP_ENV=local
   ```

2. **Run load test without authentication:**
   ```bash
   ```


---

## From Rule Engine Directory

### Quick Load Test Command

```bash
# From rule-engine directory, run load test in sibling repo
cd ../card-fraud-e2e-load-testing && \
  uv run lt-rule-engine --users=1000 --spawn-rate=100 --run-time=5m && \
  cd -
```

### Using the Wrapper Script

```bash
# From rule-engine directory
./scripts/run-load-test.sh --users 1000 --run-time 5m
```

---

## Reports

### Report Locations

| Report | Location | Description |
|--------|----------|-------------|
| **Locust HTML** | `card-fraud-e2e-load-testing/html-reports/locust/` | Interactive charts, real-time stats |
| **pytest HTML** | `card-fraud-e2e-load-testing/html-reports/pytest/` | Functional test results |
| **Combined** | `card-fraud-e2e-load-testing/html-reports/combined/` | Executive summary |

### Generate Report

```bash
cd ../card-fraud-e2e-load-testing
uv run gen-report --input html-reports/ --output html-reports/combined/
```

---

## CI/CD Integration

### GitHub Actions Workflow

```yaml
name: Load Tests

on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM
  workflow_dispatch:

jobs:
  load-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.14'

      - name: Install uv
        uses: astral-sh/setup-uv@v4

      - name: Checkout load testing repo
        uses: actions/checkout@v4
        with:
          repository: card-fraud-e2e-load-testing
          path: load-testing

      - name: Install dependencies
        working-directory: ./load-testing
        run: uv sync --extra load-test

      - name: Run load test
        working-directory: ./load-testing
        run: |
          uv run lt-rule-engine \
            --users=${{ github.event.inputs.users || 1000 }} \
            --run-time=15m \
            --headless

      - name: Upload report
        uses: actions/upload-artifact@v4
        with:
          name: load-test-report
          path: load-testing/html-reports/
```

---

## Troubleshooting

### Connection Refused

**Error:** `Failed to connect to RULE_ENGINE_URL`

**Solution:**
1. Ensure rule engine is running: `uv run doppler-local`
2. Check `RULE_ENGINE_URL` is correct
3. Verify port: `curl http://localhost:8081/v1/evaluate/health`


**Error:** `401 Unauthorized`

**Solution:**
2. Verify token hasn't expired

### High Latency

**Error:** P99 latency > 50ms

**Solution:**
1. Check system resources (CPU, memory)
2. Reduce concurrent users
3. Verify Redis is running

---

## Related Documentation

| Document | Location |
|----------|----------|
| Load Testing README (`../card-fraud-e2e-load-testing/README.md`) | External repo |
| [AGENTS.md](../../AGENTS.md) | This repo |
| [PENDING_TODO.md](../04-testing/README.md) | This repo |

---

**End of Document**
