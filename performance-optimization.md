# Performance Optimization Plan

**Service:** card-fraud-rule-engine-monitoring (+ auth cross-reference)
**Endpoint:** `POST /v1/evaluate/monitoring`
**Last Updated:** 2026-02-15
**Status:** Infrastructure bottleneck identified — code optimizations blocked until Phase 0 is resolved.

## SLA Targets

| Percentile | Target | Pre-Split Baseline (AUTH) | Post-Split Docker (Both) |
|------------|--------|--------------------------|--------------------------|
| P50 | < 50ms | ~30ms | ~100ms |
| P90 | < 80ms | unknown | ~220ms |
| P99 | < 100ms | unknown | ~630-720ms |

Source: `docs/06-operations/slos.md`, `AGENTS.md` Section 4

## Key Finding: Infrastructure Is the Bottleneck

Both AUTH (first-match, 1 velocity check) and MONITORING (all-match, N velocity checks)
show **identical** P50=100ms in Docker. This is impossible if code is the bottleneck.
The ceiling is container resource starvation.

### Root Cause

`card-fraud-platform/docker-compose.override.local.yml` limits each service to:
```yaml
limits:
  cpus: '1.0'    # 32 IO threads fighting over 1 core
  memory: 1g     # JVM configured for 1.5GB max heap (-Xmx1536M)
```

Pre-split, the monolith had 4 CPU / 2GB. Post-split, each service gets 1 CPU / 1GB.
No code optimization can overcome a 1-CPU ceiling with 32 IO threads.

---

## Hot Path Overview

Every `POST /v1/evaluate/monitoring` request flows through:

```
LoadSheddingFilter.filter()           [FILTER - every request]
  -> Semaphore.tryAcquire()
EvaluationResource.evaluateMonitoring()
  -> DecisionNormalizer.normalizeMONITORINGDecision()
  -> RulesetRegistry.getRulesetWithFallback()
  -> RuleEvaluator.evaluate()
       -> new Decision()                [UUID.randomUUID + Instant.now]
       -> Ruleset.getApplicableRules()  [scope bucket traversal + cache]
       -> MonitoringEvaluator.evaluate()
            -> Rule.getCompiledCondition().matches()  [per rule]
            -> VelocityEvaluator.checkVelocity()      [per matched rule with velocity]
                 -> VelocityService.buildVelocityKey()
                 -> VelocityService.incrementAndGetWithLua()  [Redis round-trip]
       -> finalizeDecision()
  -> DecisionPublisher.publishDecisionAsync()          [fire-and-forget]
LoadSheddingFilter.filter() [response]
  -> Semaphore.release()
```

---

## Current Status (2026-02-15)

This repo already includes the highest-impact, low-risk code/config wins identified in the earlier plan (Redis pool sizing, load shedding fast path, nanoTime timing, etc.). If AUTH and MONITORING still show **identical** P50 under load, assume you are hitting an **environment / infrastructure / measurement** ceiling, not application logic.

### Landed in MONITORING

- Redis pool defaults sized for the load-shedding limit (`64/512`) in `src/main/resources/application.yaml`
- Load shedding uses unfair semaphore and does minimal parsing on shed requests
- Evaluation timing uses `System.nanoTime()` and reports consistent `processingTimeMs`
- Velocity micro-optimizations:
  - single dimension lookup per velocity check
  - `encodeKeyPart()` avoids duplicate `Matcher` allocations
  - Lua call avoids allocating `String.valueOf(defaultThreshold)` per request

## What To Fix Next (in order)

### 0) Make the benchmark trustworthy

If you are load testing from Windows Docker Desktop to Windows Docker Desktop, you are measuring a mixed system: Windows host networking, Docker Desktop VM, NAT/port forwarding, and the containers.

Checklist:

1. Run the load generator on a different machine (or at least outside the Docker host) to avoid self-interference.
2. Avoid host-port NAT in the hot path. Prefer calling the service from inside the same Docker network.
3. Use the packaged JAR for performance runs (dev mode adds large overhead).
4. Verify resource limits are actually enforced in your execution mode (Compose `deploy.resources` has caveats outside Swarm).

### 1) Reduce Redis round-trips (big lever)

MONITORING can do N velocity checks per request; each one is at least one Redis RTT. If you need material SLA gains beyond infra cleanup, batch/pipeline the velocity checks (or reduce checks via ruleset changes) so a request does fewer Redis interactions.

## Validation (performance)

Do not validate with dev mode (`uv run doppler-local` / `uv run doppler-load-test`) when chasing latency.
For a real perf run, use the packaged JAR (see `AGENTS.md` Section 12) and then run the Locust harness from `card-fraud-e2e-load-testing`.

## AUTH Cross-Reference

- Keep AUTH and MONITORING resource limits identical when comparing infra ceilings.
- AUTH has different hot-path behavior (first-match + fewer velocity checks). If both services have the same P50, it’s almost always measurement/infra.

## Lessons Learned (2026-02-15)

1. **Check infrastructure before code.** Both services at identical P50 = infrastructure ceiling, not code.
2. **Post-split resource allocation must match pre-split baseline.** Splitting a monolith doubles resource requirements.
3. **IO threads must match available CPU cores** (2x cores, not a static 32).
4. **JVM heap must fit within container memory limit** with room for metaspace + threads (~500MB overhead).