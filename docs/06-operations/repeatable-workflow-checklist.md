# Repeatable Workflow Checklist

Purpose: eliminate session-to-session drift and run MONITORING/AUTH work with the same sequence every time.

## 1) Session Preflight (always first)

Preferred command:

```powershell
uv run preflight
```

Manual equivalent:

Run in order:

```powershell
doppler login
doppler setup
```

Then verify platform startup prerequisites are present:

```powershell
cd ../card-fraud-platform
doppler run -- uv run platform-up
uv run platform-status
cd ../card-fraud-rule-engine-monitoring
```

If platform startup fails with missing variables, do not continue until Doppler is fixed.

## 2) Shared Infra Contract (AUTH + MONITORING)

- Keep same container limits for both services in `card-fraud-platform/docker-compose.override.local.yml`.
- Keep `io-threads` aligned to `2x` available cores in each service config.
- Do not use static high thread counts that ignore CPU limits.

Current local convention:
- Platform limits: `cpus: '4'`, `memory: 2g` for both AUTH and MONITORING.
- Service default: `io-threads: ${HTTP_IO_THREADS:8}`.

## 3) Quality Gates (before load testing)

From MONITORING repo:

```powershell
uv run lint
uv run test-unit
uv run test-integration
uv run snyk-test
```

If Redis/service dependencies are unavailable, fix infra first and rerun gates.

## 4) Build Fresh Image / Runtime Artifact

For performance and load verification, use packaged runtime flow (not dev mode):

```powershell
doppler run --config local -- mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar
docker build -t card-fraud-rule-engine-monitoring:local .
```

Do not use `mvn quarkus:dev` for performance conclusions.

## 5) Repeatable Load Test Flow

Start shared apps from platform:

```powershell
cd ../card-fraud-platform
doppler run -- uv run platform-up -- --apps
uv run platform-status
```

### 5.1) Fast iteration loop (recommended for dev)

When iterating (small code/config changes) the goal is to minimize time-to-results.

- Do not use `--build` unless you changed container build inputs and need a new image.
- Skip seed/teardown unless you are explicitly validating the seed/bulk-load paths.

Start apps without rebuild:

```powershell
cd ../card-fraud-platform
doppler run -- uv run platform-up -- --apps
uv run platform-status
```

Run a quick smoke load (30s) to validate changes:

```powershell
cd ../card-fraud-e2e-load-testing

uv run lt-rule-engine --users=10 --spawn-rate=5 --run-time=30s --scenario baseline --headless --skip-seed --skip-teardown
uv run lt-rule-engine-monitoring --users=10 --spawn-rate=5 --run-time=30s --scenario baseline --headless --skip-seed --skip-teardown
```

### 5.2) Production-mirror API latency run (no harness)

Use direct Locust runs from this repository when you need API latency that mirrors
steady-state production traffic (no harness seed/teardown overhead).

Run AUTH (20 users, 5 minutes) with user pacing:

```powershell
Set-Location ../card-fraud-rule-engine-monitoring
$env:LOCUST_WAIT_MODE="between"
$env:LOCUST_MIN_WAIT_MS="20"
$env:LOCUST_MAX_WAIT_MS="80"
uv run locust -f locustfile-auth-only.py --host=http://localhost:8081 --headless -u 20 -r 5 --run-time=5m --only-summary
```

Run MONITORING (20 users, 5 minutes) with same pacing:

```powershell
$env:LOCUST_WAIT_MODE="between"
$env:LOCUST_MIN_WAIT_MS="20"
$env:LOCUST_MAX_WAIT_MS="80"
uv run locust -f locustfile.py --host=http://localhost:8082 --headless -u 20 -r 5 --run-time=5m --only-summary
```

Notes:
- This path intentionally skips harness phases so results reflect service latency under sustained traffic.
- Keep platform/app containers already running before starting these runs.

Run monitoring test:

```powershell
cd ../card-fraud-e2e-load-testing
uv run lt-rule-engine-monitoring --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless
```

Run auth test:

```powershell
uv run lt-rule-engine --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless
```

## 6) Handoff Record (mandatory)

Record these exact outputs in the handoff note:

- Commands run
- Pass/fail status for each gate
- P50/P90/P99 results for AUTH and MONITORING
- Any blocker + exact error text

## 7) Fast Failure Map

- Missing platform env vars: rerun with `doppler run -- uv run platform-up`.
- Redis not running: `uv run platform-status` and fix infra before tests.
- Unexpected high latency: confirm packaged JAR/image path was used, not dev mode.
- Inconsistent behavior between services: re-check identical platform limits and `io-threads` defaults.
