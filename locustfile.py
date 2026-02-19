"""
Monitoring-only Locust load test for card-fraud-rule-engine-monitoring.

Usage:
  locust -f locustfile.py --host=http://localhost:8082 --headless -u 50 -r 5 --run-time=2m
"""

import json
import os
import random
import uuid

from locust import HttpUser, between, constant, events, task

TARGET_HOST = os.environ.get("TARGET_HOST", "http://localhost:8082")
wait_mode = os.environ.get("LOCUST_WAIT_MODE", "none").lower()
min_wait_ms = int(os.environ.get("LOCUST_MIN_WAIT_MS", "0"))
max_wait_ms = int(os.environ.get("LOCUST_MAX_WAIT_MS", str(min_wait_ms)))

if max_wait_ms < min_wait_ms:
    max_wait_ms = min_wait_ms

if wait_mode == "between":
    wait_time_fn = between(min_wait_ms / 1000.0, max_wait_ms / 1000.0)
else:
    wait_time_fn = constant(0)

CURRENCIES = ["USD", "GBP", "EUR", "CAD", "AUD", "JPY"]
COUNTRIES = ["US"]
MERCHANT_CATEGORIES = ["5411", "5999", "5734", "5812", "5541", "5942", "5912"]
CARD_HASHES = [f"card_hash_{i:04d}" for i in range(200)]


def generate_transaction_id() -> str:
    return f"txn-{uuid.uuid4().hex[:16]}"


def generate_monitoring_request() -> dict:
    return {
        "transaction_id": generate_transaction_id(),
        "card_hash": random.choice(CARD_HASHES),
        "amount": round(random.uniform(10.0, 500.0), 2),
        "currency": random.choice(CURRENCIES),
        "country_code": random.choice(COUNTRIES),
        "merchant_id": f"merch_{random.randint(1000, 9999)}",
        "merchant_category_code": random.choice(MERCHANT_CATEGORIES),
        "decision": random.choice(["APPROVE", "DECLINE"]),
    }


class MonitoringUser(HttpUser):
    wait_time = wait_time_fn

    def on_start(self):
        self.headers = {"Content-Type": "application/json"}
        self.client.verify = False

    @task
    def monitoring_evaluation(self):
        with self.client.post(
            "/v1/evaluate/monitoring",
            json=generate_monitoring_request(),
            headers=self.headers,
            catch_response=True,
            name="POST /v1/evaluate/monitoring",
        ) as response:
            if response.status_code != 200:
                response.failure(f"Status {response.status_code}: {response.text[:160]}")
                return

            try:
                data = response.json()
            except json.JSONDecodeError:
                response.failure("Invalid JSON response")
                return

            mode = data.get("engine_mode", "NORMAL")
            if mode in ("FAIL_OPEN", "DEGRADED"):
                response.failure(f"{mode}: {data.get('engine_error_code', 'UNKNOWN')}")
                return

            response.success()


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    print("=" * 60)
    print("Card Fraud Monitoring Load Test")
    print(f"  Target: {environment.host}")
    print("  Workload: 100% /v1/evaluate/monitoring")
    print(f"  Wait mode: {wait_mode} ({min_wait_ms}ms to {max_wait_ms}ms)")
    print("=" * 60)

    print("  Ruleset load step: skipped (uses startup preloaded registry)")
    print("=" * 60)


@events.request.add_listener
def on_request(request_type, name, response_time, response_length, exception, context, **kwargs):
    if response_time > 100 and random.random() < 0.001:
        print(f"SLOW: {name} {response_time:.0f}ms")
