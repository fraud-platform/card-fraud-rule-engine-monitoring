# Load Testing Baseline

**Last Updated:** 2026-02-13  
**Environment:** Local platform containers (`card-fraud-platform` apps profile)  
**Status:** In progress. AUTH SLO still not met under current single-container test topology.

## 2026-02-15 Split-Service Production-Mirror Session

This session focused on two questions:
- Why load-testing cycles feel slow
- Whether latest AUTH + MONITORING changes improved latency under comparable runs

### A) Time-to-results decomposition (local)

Measured wall-clock contributors:

| Step | Command pattern | Wall time |
| --- | --- | ---: |
| Platform start (no rebuild) | `platform-up -- --apps` | `~14.6s` |
| Platform start (with rebuild) | `platform-up -- --apps --build` | `~65.0s` |
| Harness seed-only phase | `lt-rule-engine ... --scenario seed-only` | `~8.8s` |
| Locust-only short run | `30s` run, `--skip-seed --skip-teardown` | `~39.9s` |

Key takeaway:
- Largest avoidable iteration tax is image rebuild (`--build`).
- Harness seed/teardown are useful for correctness flows but should be skipped for rapid perf iteration.

### B) Comparable baseline runs (harness, skip-seed/skip-teardown)

Command pattern:
- `uv run lt-rule-engine --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless --skip-seed --skip-teardown`
- `uv run lt-rule-engine-monitoring --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless --skip-seed --skip-teardown`

Results:

| Service | Requests | Failures | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AUTH | `32,216` | `0` | `92` | `77` | `220` | `320` | `~670` |
| MONITORING | `31,805` | `0` | `89` | `72` | `210` | `380` | `~1200` |

### C) Fast smoke runs (harness, 30s, skip-seed/skip-teardown)

| Service | Requests | Failures | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AUTH | `3,988` | `0` | `35` | `19` | `110` | `220` | `2008` |
| MONITORING | `7,673` | `0` | `22` | `17` | `53` | `99` | `411` |

### D) Production-mirror direct Locust runs (no harness)

All direct runs used:
- Local platform containers as system under test
- Direct Locust from this repo (`locustfile-auth-only.py` / `locustfile.py`)
- `20 users`, `spawn 5/s`, `5m`

#### D1) AUTH initial run (invalid for baseline)

Command:
- `uv run locust -f locustfile-auth-only.py --host=http://localhost:8081 --headless -u 20 -r 5 --run-time=5m --only-summary`

Result (invalid run):
- `39,972` requests, `39,972` failures (`100%`)
- failure type: `FAIL_OPEN: INTERNAL_ERROR`

Root cause:
- AUTH registry was empty (ruleset not loaded)
- AUTH loader expected `rulesets/local/CARD_AUTH/v1/ruleset.json` while local MinIO had split path `rulesets/local/US/CARD_AUTH/v1/ruleset.json`

#### D2) AUTH registry fix (local)

Actions taken:
- Added compatibility object at `rulesets/local/CARD_AUTH/v1/ruleset.json` in MinIO
- Retried `POST /v1/evaluate/rulesets/load` for `CARD_AUTH v1`

Verification:
- `rulesets/load` response: success
- Registry status: `totalRulesets=1`, `global: [CARD_AUTH]`

#### D3) AUTH production-mirror run (valid)

Paced user model:
- `LOCUST_WAIT_MODE=between`, `LOCUST_MIN_WAIT_MS=20`, `LOCUST_MAX_WAIT_MS=80`

Result:
- Requests: `48,250`
- Failures: `0`
- Avg: `61 ms`
- p50: `54 ms`
- p95: `110 ms`
- p99: `170 ms`
- Max: `455 ms`
- RPS: `161.27`

#### D4) MONITORING production-mirror run (valid, paced)

Paced user model:
- `LOCUST_WAIT_MODE=between`, `LOCUST_MIN_WAIT_MS=20`, `LOCUST_MAX_WAIT_MS=80`

Result:
- Requests: `54,609`
- Failures: `0`
- Avg: `52 ms`
- p50: `50 ms`
- p95: `68 ms`
- p99: `96 ms`
- Max: `649 ms`
- RPS: `182.48`

#### D5) MONITORING throughput-pressure run (valid, no wait)

High-throughput user model:
- `LOCUST_WAIT_MODE=none`

Result:
- Requests: `78,418`
- Failures: `0`
- Avg: `67 ms`
- p50: `58 ms`
- p95: `110 ms`
- p99: `210 ms`
- Max: `920 ms`
- RPS: `266.36`

Interpretation:
- Increasing throughput from `~182 req/s` to `~266 req/s` raised tails (`p95 68 -> 110`, `p99 96 -> 210`).
- Capacity pressure is a confirmed contributor to latency.

#### D6) Paired rerun (AUTH then MON, paced, 2026-02-15)

Rerun settings:
- `20 users`, `spawn 5/s`, `5m`
- `LOCUST_WAIT_MODE=between`, `LOCUST_MIN_WAIT_MS=20`, `LOCUST_MAX_WAIT_MS=80`
- 30s container sampling collected in parallel with each run

Results:

| Service | Requests | Failures | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | RPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AUTH | `72,312` | `0` | `20` | `14` | `55` | `120` | `970` | `242.86` |
| MONITORING | `72,458` | `0` | `20` | `15` | `54` | `100` | `500` | `242.18` |

Artifacts:
- `04-testing/auth-locust-2026-02-15-rerun.out.log`
- `04-testing/auth-container-stats-2026-02-15-rerun.log`
- `04-testing/mon-locust-2026-02-15-rerun.out.log`
- `04-testing/mon-container-stats-2026-02-15-rerun.log`

#### D7) Throughput ladder rerun (paced, 2026-02-15)

Purpose:
- Validate whether higher offered load affects latency tails under the same pacing model.

Run settings:
- Pacing: `LOCUST_WAIT_MODE=between`, `LOCUST_MIN_WAIT_MS=20`, `LOCUST_MAX_WAIT_MS=80`
- Durations: `2m` per point
- Users tested per service: `20`, `35`, `50`

Results:

| Service | Users | Requests | Failures | RPS | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AUTH | `20` | `28,839` | `0` | `246.41` | `19.44` | `16` | `45` | `80` |
| AUTH | `35` | `35,499` | `0` | `300.23` | `33.65` | `29` | `68` | `100` |
| AUTH | `50` | `34,616` | `0` | `292.84` | `57.60` | `50` | `120` | `160` |
| MONITORING | `20` | `30,192` | `0` | `254.69` | `18.13` | `14` | `41` | `74` |
| MONITORING | `35` | `31,485` | `0` | `261.47` | `42.63` | `34` | `91` | `170` |
| MONITORING | `50` | `27,767` | `0` | `235.75` | `76.97` | `42` | `190` | `870` |

Interpretation:
- Both services show clear latency growth as concurrent users increase.
- MONITORING tails degrade most sharply (`p99 74 -> 170 -> 870`) across `20 -> 35 -> 50` users.
- AUTH also degrades (`p95 45 -> 68 -> 120`, `p99 80 -> 100 -> 160`) as load rises.

Knee-point snapshot (from paced ladder):

| Service | Knee zone | Why |
| --- | --- | --- |
| AUTH | `35 -> 50 users` | `p95` jumps `68 -> 120` and `p50` jumps `29 -> 50` while RPS does not increase (`300.23 -> 292.84`). |
| MONITORING | `20 -> 35 users` (onset), severe at `50` | `p95` more than doubles `41 -> 91` by `35`; at `50`, tails surge (`p99 870`) with lower RPS (`261.47 -> 235.75`). |

Compact trend chart:

| Service | Users=20 | Users=35 | Users=50 |
| --- | --- | --- | --- |
| AUTH p95/p99 (ms) | `45 / 80` | `68 / 100` | `120 / 160` |
| MON p95/p99 (ms) | `41 / 74` | `91 / 170` | `190 / 870` |

Artifacts:
- `04-testing/auth-step-2026-02-15-u20_stats.csv`
- `04-testing/auth-step-2026-02-15-u35_stats.csv`
- `04-testing/auth-step-2026-02-15-u50_stats.csv`
- `04-testing/mon-step-2026-02-15-u20_stats.csv`
- `04-testing/mon-step-2026-02-15-u35_stats.csv`
- `04-testing/mon-step-2026-02-15-u50_stats.csv`

### E) Container capacity observations (sampled during runs)

- AUTH rerun sample summary (`10` samples):
  - CPU avg `46.04%`, CPU max `64.49%`
  - Memory avg `59.87%`, memory max `60.04%`
- MONITORING rerun sample summary (`10` samples):
  - CPU avg `55.80%`, CPU max `192.53%` (short burst)
  - Memory avg `60.19%`, memory max `61.43%`

Operational interpretation:
- Memory was stable and below limit in these runs.
- CPU pressure (and shared local host/container overhead) is the more likely floor for tail latency in this setup.

## 2026-02-10 Baseline Snapshot (Reference)

- Baseline command:
  - `uv run lt-rule-engine --users=100 --spawn-rate=10 --run-time=2m --scenario baseline --headless --skip-seed --skip-teardown`
- Result:
  - Requests: `88,144`
  - Failures: `0`
  - p50: `63 ms`
  - p95: `120 ms`
  - p99: `160 ms`
  - Avg: `66.65 ms`
  - RPS: `~810.9`
- Artifacts:
  - `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/run-summary-20260210-184613.json`
  - `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/runs/lt-e99435c13dea/locust/rule-engine_stats.csv`

## 2026-02-11 Distributed AUTH-Only Validation

All runs used Locust distributed mode (`master + 2 workers`) from:
- `C:/Users/kanna/github/card-fraud-e2e-load-testing`

Per-run artifacts:
- `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/runs/<run-id>/locust/`

## 2026-02-12 Distributed AUTH-Only Validation (latest)

All runs used Locust distributed mode (`master + 2 workers`) from:
- `C:/Users/kanna/github/card-fraud-e2e-load-testing`

Source-of-truth for metrics:
- `.../rule-engine-distributed_stats.csv` aggregated row (manual distributed runs can produce incorrect `run-summary-*.json` values)

## 2026-02-13 Quick Baseline After Quarkus 3.31.2 + Java 25

- Topology:
  - Single headless Locust process (non-distributed), local host -> local platform containers
- Command:
  - `uv run lt-run --service rule-engine --users=50 --spawn-rate=10 --run-time=2m --scenario baseline --headless`
- Result:
  - Requests: `80,682`
  - Failures: `0`
  - p50: `36 ms`
  - p95: `64 ms`
  - p99: `96 ms`
  - Avg: `37.77 ms`
  - RPS: `629.8`
  - Locust warning: load-generator CPU crossed 90%
- Artifacts:
  - `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/run-summary-20260213-093622.json`
  - `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/runs/lt-cd44d547fc9c/locust/rule-engine.html`
- Comparison to prior best clean 50-user result (`2026-02-12`, OFF control rerun):
  - Prior: `p50 21`, `p95 54`, `p99 89`, `rps 1066.61`
  - Current: `p50 36`, `p95 64`, `p99 96`, `rps 629.8`
  - Interpretation: this quick run did not improve latency; run-quality/topology differs (single-process Locust with CPU warning vs distributed clean reruns), so keep distributed reruns as acceptance source.

## 2026-02-13 Distributed Apples-to-Apples Validation (master + 3 workers)

- Topology:
  - Manual Locust distributed mode (`master + 3 workers`), AUTH-only traffic mix
- Source of truth:
  - `rule-engine-distributed_stats.csv` aggregated row
- Run ID:
  - `lt-dist-java25-u50-w3-20260213-114023`
- Result (aggregated CSV):
  - Requests: `134,517`
  - Failures: `0`
  - p50: `26 ms`
  - p95: `69 ms`
  - p99: `110 ms`
  - Avg: `30.89 ms`
  - RPS: `1147.43`
- Artifact:
  - `C:/Users/kanna/github/card-fraud-e2e-load-testing/html-reports/runs/lt-dist-java25-u50-w3-20260213-114023/locust/rule-engine-distributed_stats.csv`

Comparison to prior clean distributed 3-worker reference:
- Reference run:
  - `lt-dist-nextphase-async-off-u50-w3-20260212-212939`
  - `p50 26`, `p95 71`, `p99 110`, `avg 31.21`, `rps 1093.03`, `131,676 req`, `0 failures`
- Delta (new - reference):
  - `p50 0 ms` (no change)
  - `p95 -2 ms` (improved)
  - `p99 0 ms` (no change)
  - `avg -0.32 ms` (improved)
  - `rps +54.40` (improved)
  - `requests +2,841` (same duration)

### 2026-02-12 Matrix (clean reruns)

| Run ID | Async durability mode | Target users | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | RPS | Requests | Failures | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `lt-dist-lazyctx-lazytimestamp-async-off-u200-20260212-055446` | OFF | 200 | 120 | 280 | 460 | 134.62 | 787.04 | 93,067 | 0 | clean |
| `lt-dist-lazyctx-lazytimestamp-async-on-u200-20260212-060144` | ON | 200 | 120 | 290 | 620 | 146.16 | 802.81 | 95,644 | 0 | clean |
| `lt-dist-lazyctx-lazytimestamp-async-off-u100-rerun-20260212-064654` | OFF | 100 | 69 | 180 | 280 | 82.04 | 812.07 | 96,400 | 0 | clean rerun |
| `lt-dist-lazyctx-lazytimestamp-async-on-u100-20260212-061959` | ON | 100 | 60 | 180 | 300 | 78.16 | 867.67 | 103,151 | 0 | clean |
| `lt-dist-lazyctx-lazytimestamp-async-off-u50-rerun-20260212-065122` | OFF | 50 | 21 | 57 | 95 | 25.19 | 1042.35 | 123,076 | 0 | clean rerun (one worker CPU-threshold warning) |
| `lt-dist-lazyctx-lazytimestamp-async-on-u50-20260212-063137` | ON | 50 | 35 | 88 | 130 | 40.80 | 848.00 | 99,699 | 0 | clean |

### 2026-02-12 Scope-cache optimization reruns (AUTH-only OFF)

| Run ID | Async durability mode | Target users | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | RPS | Requests | Failures | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `lt-dist-scopecache-async-off-u50-20260212-185959` | OFF | 50 | 45 | 150 | 320 | 59.88 | 463.46 | 54,598 | 0 | diagnostic; early worker-report anomaly |
| `lt-dist-scopecache-async-off-u50-rerun-20260212-192456` | OFF | 50 | 36 | 100 | 190 | 44.61 | 608.73 | 72,275 | 0 | clean spawn (`50/50`) |
| `lt-dist-scopecache-nojfr-async-off-u50-20260212-194613` | OFF | 50 | 43 | 130 | 240 | 54.36 | 504.53 | 60,174 | 0 | clean spawn (`50/50`), JFR explicitly disabled |

Implementation context for these runs:
- code change: bounded scope-tuple cache in `Ruleset.getApplicableRules(...)`
- compose override change: disabled startup JFR via `JAVA_JFR_OPTS=` in `testing/compose-auth-no-async.override.yml`

### 2026-02-12 Controlled reruns (AUTH-only, late-night reruns)

Purpose:
- Re-run 50-user points after environment stabilization and explicit mode toggles.
- Validate ON/OFF deltas with better load-generator quality controls.

#### 2-worker reruns

| Run ID | Async durability mode | Target users | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | RPS | Requests | Failures | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `lt-dist-nextphase-async-off-u50-20260212-205800` | OFF | 50 | 41 | 130 | 250 | 53.45 | 494.03 | 59,200 | 0 | clean |
| `lt-dist-nextphase-async-off-u50-20260212-210046` | OFF | 50 | 41 | 120 | 230 | 52.29 | 499.35 | 59,766 | 0 | clean |
| `lt-dist-nextphase-async-off-u50-20260212-210336` | OFF | 50 | 37 | 110 | 210 | 47.14 | 562.14 | 66,843 | 0 | clean |
| `lt-dist-nextphase-async-on-u50-20260212-210947` | ON | 50 | 21 | 57 | 90 | 25.10 | 1093.20 | 128,605 | 0 | worker CPU warning (not acceptance) |
| `lt-dist-nextphase-async-on-u50-20260212-211230` | ON | 50 | 18 | 42 | 65 | 20.30 | 1264.06 | 148,284 | 0 | worker CPU warnings (not acceptance) |
| `lt-dist-nextphase-async-off-poston-u50-20260212-211755` | OFF | 50 | 21 | 54 | 89 | 24.65 | 1066.61 | 125,957 | 0 | clean control rerun after ON sequence |

#### 3-worker reruns (cleaner ON/OFF comparison)

| Run ID | Async durability mode | Target users | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | RPS | Requests | Failures | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `lt-dist-nextphase-async-on-u50-w3-20260212-212352` | ON | 50 | 25 | 69 | 110 | 30.44 | 1099.44 | 130,659 | 0 | clean (`3/3` workers, no CPU warnings) |
| `lt-dist-nextphase-async-off-u50-w3-20260212-212939` | OFF | 50 | 26 | 71 | 110 | 31.21 | 1093.03 | 131,676 | 0 | clean (`3/3` workers, no CPU warnings) |

### Diagnostic runs (excluded from acceptance)

- `lt-dist-lazyctx-lazytimestamp-async-off-u100-20260212-061042`
  - early worker report mismatch warning, used only as directional signal.
- `lt-dist-lazyctx-lazytimestamp-async-off-u50-20260212-061403`
  - noisy long-tail outlier (`p99 450`, `max 2837`) replaced by clean rerun.

### 2026-02-11 Matrix

| Run ID | Async durability mode | Target users | p50 (ms) | p95 (ms) | p99 (ms) | Avg (ms) | RPS | Requests | Failures | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `lt-dist-optphase-async-off-u200-20260211-203859` | OFF | 200 | 180 | 540 | 980 | 230.83 | 442.06 | 52,397 | 0 | warm/startup affected |
| `lt-dist-optphase-async-off-u200-rerun1-20260211-205646` | OFF | 200 | 120 | 340 | 560 | 149.18 | 728.63 | 72,432 | 0 | worker heartbeat warnings |
| `lt-dist-optphase-async-off-u200-rerun2-20260211-210108` | OFF | 200 | 130 | 310 | 510 | 149.71 | 661.00 | 78,553 | 0 | cleanest 200 OFF rerun |
| `lt-dist-optphase-async-on-u200-20260211-205314` | ON | 200 | 140 | 350 | 560 | 166.52 | 661.89 | 78,599 | 0 | clean |
| `lt-dist-optphase-async-off-u100-20260211-204517` | OFF | 100 | 45 | 110 | 200 | 51.66 | 968.30 | 115,360 | 0 | clean |
| `lt-dist-optphase-async-on-u100-20260211-210645` | ON | 100 | 75 | 220 | 380 | 95.57 | 580.27 | 69,738 | 0 | partial spawn warning (`80` users reported) |
| `lt-dist-optphase-async-off-u50-20260211-204834` | OFF | 50 | 29 | 79 | 120 | 34.69 | 768.24 | 90,772 | 0 | clean |
| `lt-dist-optphase-async-on-u50-20260211-212537` | ON | 50 | 32 | 93 | 160 | 39.96 | 703.48 | 83,516 | 0 | clean |

### Invalid Run (Excluded)

- `lt-dist-optpass-async-off-u200-20260211-202016`
  - `5,259` requests, `5,259` failures
  - failure type: `ConnectionRefusedError` (service unreachable while Docker engine was unavailable)
  - this run is excluded from latency comparison.

## Current Takeaways

- AUTH SLO remains unmet:
  - target p50 `< 10 ms`, p95 `< 20 ms`
  - best clean latest result (50 users, OFF control rerun): `p50 21`, `p95 54`, `p99 89`
- In this local single-instance setup, lowering concurrency from `200 -> 50` improves latency substantially.
- Async OFF remains better at 200 users; at 100 users ON/OFF are close.
- At 50 users with controlled `3-worker` runs, ON/OFF are nearly identical (`25/69/110` vs `26/71/110`).
- Latest scope-cache optimization reruns did not beat the existing best clean OFF baseline (`21/57/95`).
- Even at the best 50-user point, p95 is still above target (`54 ms` vs `<20 ms`).
- Run quality controls matter:
  - discard runs with worker spawn/report anomalies, explicit Locust CPU-threshold warnings, or obvious long-tail outliers for acceptance
  - keep at least 2-3 clean reruns per point.

## Notes

- Authentication/authorization is enforced at API Gateway; rule-engine does not validate JWTs in-process.
- AUTH-only traffic mix is enforced by harness configuration (`auth=1.0`, `monitoring=0.0`).
- Run metadata and Locust artifacts are persisted per run under `html-reports/runs/<run-id>/`.
