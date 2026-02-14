# Code Map

## Repository Purpose

Quarkus runtime for MONITORING fraud decision evaluation and compiled ruleset execution.

Service focus:
- `POST /v1/evaluate/monitoring` only (MONITORING service split).
- Uses fixed ruleset key `CARD_MONITORING` with country fallback to global.
- Runs Redis-backed velocity checks and publishes decision events asynchronously to Kafka.

Monitoring latency SLO targets:
- `P50 < 50ms`
- `P90 < 80ms`
- `P99 < 100ms`

Reference: `docs/06-operations/slos.md`.

## Documentation Layout

- `01-setup/`: Setup
- `02-development/`: Development
- `03-api/`: API
- `04-testing/`: Testing
- `05-deployment/`: Deployment
- `06-operations/`: Operations
- `07-reference/`: Reference

## Local Commands

- `uv sync`
- `uv run doppler-local`
- `uv run test-unit`
- `uv run snyk-test`

## Platform Modes

- Standalone mode: run this repository with its own local commands and Doppler config.
- Consolidated mode: run via `card-fraud-platform` for cross-service local validation.
