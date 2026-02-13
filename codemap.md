# Code Map

## Repository Purpose

Quarkus runtime for MONITORING/analytics fraud decision evaluation and compiled ruleset execution. MONITORING evaluates all rules for analytics tracking (decision comes from input, not from rules). Publishes decisions asynchronously to Kafka (no Redis outbox).

Ruleset resolution uses `CARD_MONITORING` for MONITORING. Resolution is country-aware: the engine looks up `rulesetRegistry.getRuleset(countryCode, key)` with fallback to global namespace.

MONITORING evaluation semantics:
- All-match: evaluates all rules for analytics tracking
- Decision comes from transaction input (must be APPROVE or DECLINE)
- No fail-open - returns decision as provided

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
