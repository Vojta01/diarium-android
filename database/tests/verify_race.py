#!/usr/bin/env python3
"""Assert concurrent RPC outputs and committed synthetic database snapshots."""

import json
import sys
from pathlib import Path


def read_json(path: str) -> dict:
    lines = [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    candidates = []
    for line in lines:
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and "status" in value:
            candidates.append(value)
    if len(candidates) != 1:
        raise AssertionError(f"expected one RPC envelope in {path}, found {len(candidates)}")
    return candidates[0]


def read_snapshot(path: str) -> dict:
    value = json.loads(Path(path).read_text(encoding="utf-8").strip())
    if not isinstance(value, dict):
        raise AssertionError(f"invalid row snapshot in {path}")
    return value


def assert_creation_race(a: dict, b: dict, snapshot: dict) -> None:
    envelopes = [a, b]
    statuses = sorted(item.get("status") for item in envelopes)
    if statuses != ["conflict", "saved"]:
        raise AssertionError(f"absent-baseline race must have one saved and one conflict: {envelopes}")
    winner = next(item for item in envelopes if item["status"] == "saved")
    if snapshot != {"count": 1, "revision": 1, "note": winner["entry"]["note"]}:
        raise AssertionError(f"creation race overwrote or duplicated the winner: {snapshot}, {winner}")
    if winner["entry"].get("revision") != 1:
        raise AssertionError(f"new entry must start at revision 1: {winner}")
    print("PASS concurrent absent-baseline creates: one saved, one conflict, one revision-1 row")


def assert_duplicate_replay(a: dict, b: dict, snapshot: dict) -> None:
    if [a.get("status"), b.get("status")] != ["saved", "saved"]:
        raise AssertionError(f"same-request race must return saved outcomes: {a}, {b}")
    if sorted([a.get("replayed"), b.get("replayed")], key=str) != [False, True]:
        raise AssertionError(f"same request must execute once and replay once: {a}, {b}")
    if a.get("entry_id") != b.get("entry_id") or a.get("revision") != b.get("revision"):
        raise AssertionError(f"duplicate request callers must receive the same immutable receipt: {a}, {b}")
    if snapshot != {"count": 1, "revision": 1, "note": "same-intent"}:
        raise AssertionError(f"duplicate replay changed or duplicated the row: {snapshot}")
    print("PASS concurrent duplicate request: one mutation and one immutable replay")


def assert_update_race(a: dict, b: dict, snapshot: dict) -> None:
    statuses = sorted([a.get("status"), b.get("status")])
    if statuses != ["conflict", "saved"]:
        raise AssertionError(f"same-token updates must have one saved and one conflict: {a}, {b}")
    winner = next(item for item in (a, b) if item.get("status") == "saved")
    if snapshot != {"count": 1, "revision": 2, "note": winner["entry"]["note"]}:
        raise AssertionError(f"same-token race lost data or incremented revision incorrectly: {snapshot}, {winner}")
    print("PASS concurrent matching-token updates: one saved, one conflict, one revision increment")


if len(sys.argv) != 5:
    raise SystemExit("usage: verify_race.py GROUP OUTPUT_A OUTPUT_B SNAPSHOT")

group, output_a, output_b, snapshot_path = sys.argv[1:]
a = read_json(output_a)
b = read_json(output_b)
snapshot = read_snapshot(snapshot_path)

if group == "create":
    assert_creation_race(a, b, snapshot)
elif group == "duplicate":
    assert_duplicate_replay(a, b, snapshot)
elif group == "update":
    assert_update_race(a, b, snapshot)
else:
    raise SystemExit(f"unknown race group: {group}")
