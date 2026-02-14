# Service Level Objectives (SLOs) - Monitoring Service

> Project: card-fraud-rule-engine-monitoring
> Last Updated: 2026-02-14
> Endpoint scope: `POST /v1/evaluate/monitoring`

## Overview

This document defines runtime SLO targets for the MONITORING rule-engine service after the AUTH/MONITORING split.

## Latency SLOs

### Primary targets

| Percentile | Target |
|---|---|
| P50 | < 50ms |
| P90 | < 80ms |
| P99 | < 100ms |

### Measurement scope

- Measured at the HTTP API boundary for `POST /v1/evaluate/monitoring`.
- Includes request parsing, ruleset lookup/evaluation, Redis velocity checks, and response serialization.
- Excludes client network latency outside the service process.

## Reliability SLOs

| Metric | Target |
|---|---|
| HTTP 5xx rate | < 0.1% |
| Success rate (`2xx`) | >= 99.9% |

Notes:
- Client errors (`4xx`) are excluded from service reliability SLO.
- Degraded-mode responses (`engine_mode=DEGRADED`) must be tracked as an operational risk metric.

## Throughput Guidance

The service should sustain representative monitoring traffic without violating latency SLOs.
Use rate/load plans that match current platform limits and verify latency percentiles as the source of truth.

## Load-Test Policy

- Do not use Quarkus dev mode (`mvn quarkus:dev`) for performance measurement.
- Preferred flow is packaged runtime with load-test profile as documented in `AGENTS.md`.
- Keep load shedding disabled in load-test profile to measure real capacity.

## Alerting Thresholds

| Condition | Severity |
|---|---|
| P90 >= 80ms for 5 minutes | Warning |
| P99 >= 100ms for 2 minutes | Critical |
| HTTP 5xx >= 0.1% for 2 minutes | Critical |

## Review Cadence

| Frequency | Activity |
|---|---|
| Weekly | Review latency and error trends |
| Monthly | Reconfirm SLO targets against observed traffic |
| Quarterly | Re-baseline targets if architecture/traffic changes |

