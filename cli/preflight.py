#!/usr/bin/env python3
"""Session preflight checks for repeatable local execution.

Validates Doppler session, starts shared platform infrastructure with secrets,
checks platform status, and verifies Redis connectivity from this repository.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
PLATFORM_ROOT = REPO_ROOT.parent / "card-fraud-platform"


def _run(cmd: list[str], *, cwd: Path | None = None, required: bool = True) -> int:
    printable = " ".join(cmd)
    location = str(cwd) if cwd else str(REPO_ROOT)
    print(f"\n[run] ({location}) {printable}")
    result = subprocess.run(cmd, cwd=str(cwd) if cwd else None)
    if required and result.returncode != 0:
        raise SystemExit(result.returncode)
    return result.returncode


def _require_tool(name: str) -> None:
    if shutil.which(name) is None:
        print(f"[ERROR] Missing required tool: {name}")
        raise SystemExit(1)


def main() -> int:
    print("=" * 72)
    print("Card Fraud Monitoring - Preflight")
    print("=" * 72)

    for tool in ("doppler", "docker", "uv"):
        _require_tool(tool)

    if not PLATFORM_ROOT.exists():
        print(f"[ERROR] Shared platform repo not found: {PLATFORM_ROOT}")
        print("        Expected sibling path '../card-fraud-platform'")
        return 1

    doppler_ok = _run(["doppler", "me"], required=False) == 0
    if not doppler_ok:
        print("[ERROR] Doppler session is not active.")
        print("        Run 'doppler login' and 'doppler setup', then retry.")
        return 1

    _run(["doppler", "run", "--", "uv", "run", "platform-up"], cwd=PLATFORM_ROOT)
    _run(["uv", "run", "platform-status"], cwd=PLATFORM_ROOT)
    _run(["uv", "run", "redis-local-verify"], cwd=REPO_ROOT)

    print("\n[OK] Preflight complete.")
    print("Next recommended commands:")
    print("  uv run lint")
    print("  uv run test-unit")
    print("  uv run test-integration")
    print("  uv run snyk-test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
