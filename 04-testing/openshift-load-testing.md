# OpenShift (Pods-Only) Load Testing Runbook

**Purpose:** Run enterprise-style load tests against the rule-engine exactly how it runs in production: as containerized OpenShift pods.

**Key idea:** The correctness and latency you care about comes from running the same container image, config, and resource limits you use in OpenShift. Local Docker Compose can be a useful *development* approximation, but it is not an acceptance-quality substitute for a real OpenShift environment.

## What "no packaged JAR" means in practice

In OpenShift, you still run a production artifact inside the container (a runner JAR or native image). The important constraint is:

- **Do not run Quarkus dev mode** (`mvn quarkus:dev`) for performance acceptance.
- **Do run the service the same way your pods run it** (the same image + runtime config).

So you don’t need to run a local `java -jar ...` command on your laptop, but your pod’s image must still contain the built artifact.

## Recommended topology

- **System under test:** OpenShift `Deployment`/`DeploymentConfig` for AUTH and/or MONITORING rule-engine.
- **Dependencies:** Redis + Kafka/Redpanda + MinIO/S3 reachable from the pods (ideally also in-cluster or in the same environment tier).
- **Load generator:** Locust from `card-fraud-e2e-load-testing`.
  - Option A (preferred for stable networking): run Locust **in-cluster** as a `Job` or `Deployment`.
  - Option B: run Locust from a dedicated load-test VM outside the cluster targeting the OpenShift Route.

## Pre-flight (acceptance-quality requirements)

Before trusting any numbers:

- Confirm you are targeting the right service:
  - AUTH should hit the AUTH Route/Service
  - MONITORING should hit the MONITORING Route/Service
- Confirm rulesets are on the **hot path**:
  - Rulesets must be present in object storage AND loaded into the service registry (bulk-load) before traffic.
- Confirm resource limits resemble production:
  - CPU request/limit, memory request/limit
  - replica count (even if 1 pod for baseline)

## Locust targeting for split services

From `card-fraud-e2e-load-testing`, set:

```bash
export RULE_ENGINE_AUTH_URL="https://<auth-route-host>"
export RULE_ENGINE_MONITORING_URL="https://<monitoring-route-host>"
export RULE_ENGINE_MODE="monitoring"  # or "auth"
```

Run a baseline:

```bash
uv run lt-run --service rule-engine-monitoring --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless
```

Notes:
- Keep `--scenario baseline` consistent across comparisons.
- Prefer a fixed warm-up period (for example, run one short smoke run first) to reduce JIT/startup noise.

## What to collect (minimum)

- Locust artifacts:
  - `html-reports/runs/<run-id>/locust/*.html`
  - `html-reports/runs/<run-id>/locust/*_stats.csv`
- Pod-level signals for the same time window:
  - CPU throttling
  - GC activity / heap pressure
  - request rate and error rate

## Interpreting the "50 users / 2m" gate

If you don’t meet SLOs at 50 users for 2 minutes, **it still can be valuable to run higher-concurrency points**, but only after you confirm:

- you are not measuring the wrong service,
- you are not measuring an error path (ruleset not loaded),
- and the runtime mode/config matches production pods.

Higher-user points help you find the knee of the curve (saturation point) and whether latency increases due to CPU throttling, GC, Redis, or Kafka backpressure.

## Next steps

1. Run one clean baseline against AUTH and MONITORING in OpenShift with explicit `RULE_ENGINE_MODE` and URLs.
2. Compare to `docs/06-operations/slos.md` targets and record the run IDs + percentiles in `04-testing/load-testing-baseline.md`.
3. If P99 is above target, capture a short profiling window (JFR/async-profiler) on the pods and iterate on the dominant bottleneck.
