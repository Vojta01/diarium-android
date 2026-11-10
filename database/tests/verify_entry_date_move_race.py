#!/usr/bin/env python3
"""Verify separate-session date-move race results and final synthetic rows."""

import json
import sys
from pathlib import Path


def read_envelope(path: str) -> dict:
    candidates = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        try:
            value = json.loads(line.strip())
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and "status" in value:
            candidates.append(value)
    if len(candidates) != 1:
        raise AssertionError(f"expected one RPC outcome in {path}, found {len(candidates)}")
    return candidates[0]


def read_rows(path: str) -> list[dict]:
    value = json.loads(Path(path).read_text(encoding="utf-8").strip())
    rows = value.get("rows") if isinstance(value, dict) else None
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise AssertionError(f"invalid final row snapshot in {path}: {value!r}")
    return rows


def assert_same_day(a: dict, b: dict, rows: list[dict]) -> None:
    if [a.get("status"), b.get("status")] != ["unchanged", "unchanged"]:
        raise AssertionError(f"same-day race must be unchanged twice: {a}, {b}")
    if sorted([a.get("replayed"), b.get("replayed")], key=str) != [False, True]:
        raise AssertionError(f"same request must execute once and replay once: {a}, {b}")
    if len(rows) != 1 or rows[0].get("date") != "2003-01-01":
        raise AssertionError(f"same-day race changed the source row: {rows}")
    print("PASS concurrent same-day no-op and idempotent replay")


def assert_missing_source(a: dict, b: dict, rows: list[dict]) -> None:
    if any(item.get("status") != "conflict" or item.get("reason") != "source_missing" for item in (a, b)):
        raise AssertionError(f"missing-source requests must both conflict identically: {a}, {b}")
    if rows:
        raise AssertionError(f"missing-source race created data: {rows}")
    print("PASS concurrent missing-source requests create no destination")


def assert_same_source(a: dict, b: dict, rows: list[dict]) -> None:
    outcomes = [a, b]
    if sorted(item.get("status") for item in outcomes) != ["conflict", "moved"]:
        raise AssertionError(f"one source cannot move to two dates: {a}, {b}")
    winner = next(item for item in outcomes if item["status"] == "moved")
    loser = next(item for item in outcomes if item["status"] == "conflict")
    if loser.get("reason") != "source_missing":
        raise AssertionError(f"losing move should observe consumed source: {loser}")
    if len(rows) != 1 or rows[0].get("id") != "30000000-0000-0000-0000-000000000003":
        raise AssertionError(f"same-source race lost or duplicated the source: {rows}")
    if rows[0].get("date") != winner.get("target_date"):
        raise AssertionError(f"winner envelope and stored destination disagree: {winner}, {rows}")
    print("PASS concurrent moves of one source have exactly one winner")


def assert_target_race(a: dict, b: dict, rows: list[dict], overwrite: bool) -> None:
    outcomes = [a, b]
    if sorted(item.get("status") for item in outcomes) != ["conflict", "moved"]:
        raise AssertionError(f"target race must have one move and one conflict: {a}, {b}")
    winner = next(item for item in outcomes if item["status"] == "moved")
    loser = next(item for item in outcomes if item["status"] == "conflict")
    wanted_reason = "stale_target" if overwrite else "destination_exists"
    if loser.get("reason") != wanted_reason:
        raise AssertionError(f"losing target request should report {wanted_reason}: {loser}")
    target = "2003-04-03" if overwrite else "2003-03-03"
    target_rows = [row for row in rows if row.get("date") == target]
    if len(target_rows) != 1 or target_rows[0].get("id") != winner.get("entry_id"):
        raise AssertionError(f"target did not preserve the winning source: {winner}, {rows}")
    if overwrite:
        if len(rows) != 2 or any(row.get("id") == "30000000-0000-0000-0000-000000000006" for row in rows):
            raise AssertionError(f"confirmed overwrite race has unexpected rows: {rows}")
        expected_source_ids = {
            "30000000-0000-0000-0000-000000000007",
            "30000000-0000-0000-0000-000000000008",
        }
        remaining = [row for row in rows if row.get("date") != target]
        if len(remaining) != 1 or remaining[0].get("id") not in expected_source_ids:
            raise AssertionError(f"losing source was not preserved: {rows}")
    else:
        if len(rows) != 2 or {row.get("id") for row in rows} != {
            "30000000-0000-0000-0000-000000000004",
            "30000000-0000-0000-0000-000000000005",
        }:
            raise AssertionError(f"absent-target race lost the losing source: {rows}")
    label = "confirmed occupied-target overwrite" if overwrite else "absent-target contention"
    print(f"PASS concurrent {label}: winner kept, losing source preserved")


def assert_update_race(move: dict, save: dict, rows: list[dict]) -> None:
    statuses = sorted([move.get("status"), save.get("status")])
    if statuses not in (["conflict", "moved"], ["conflict", "saved"]):
        raise AssertionError(f"same source token must allow exactly one update/move: {move}, {save}")
    if len(rows) != 1 or rows[0].get("id") != "30000000-0000-0000-0000-000000000009":
        raise AssertionError(f"update/move race lost or duplicated the source row: {rows}")
    if move.get("status") == "moved":
        if (
            save.get("status") != "conflict"
            or save.get("reason") != "row_missing"
            or rows[0].get("date") != "2003-05-02"
            or rows[0].get("note") != "update race seed"
        ):
            raise AssertionError(f"move winner and save conflict disagree: {move}, {save}, {rows}")
    else:
        if (
            save.get("status") != "saved"
            or move.get("reason") != "stale_source"
            or rows[0].get("date") != "2003-05-01"
            or rows[0].get("note") != "concurrent update won"
        ):
            raise AssertionError(f"save winner and move conflict disagree: {move}, {save}, {rows}")
    print("PASS concurrent conditional update vs move: one token winner, no lost row")


def assert_cross_user(owner: dict, other: dict, rows: list[dict]) -> None:
    if owner.get("status") != "moved":
        raise AssertionError(f"source owner move should succeed: {owner}")
    if other.get("status") != "conflict" or other.get("reason") != "source_missing":
        raise AssertionError(f"other account must see an indistinguishable missing source: {other}")
    if (
        len(rows) != 1
        or rows[0].get("user_id") != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        or rows[0].get("id") != "30000000-0000-0000-0000-000000000010"
        or rows[0].get("date") != "2003-06-02"
    ):
        raise AssertionError(f"cross-user race changed or exposed another owner's row: {rows}")
    print("PASS concurrent cross-user probe cannot affect owner move")


if len(sys.argv) != 5:
    raise SystemExit("usage: verify_entry_date_move_race.py GROUP OUTPUT_A OUTPUT_B SNAPSHOT")

group, output_a, output_b, snapshot_path = sys.argv[1:]
a = read_envelope(output_a)
b = read_envelope(output_b)
rows = read_rows(snapshot_path)

if group == "same_day":
    assert_same_day(a, b, rows)
elif group == "missing_source":
    assert_missing_source(a, b, rows)
elif group == "same_source":
    assert_same_source(a, b, rows)
elif group == "same_target":
    assert_target_race(a, b, rows, overwrite=False)
elif group == "occupied_target":
    assert_target_race(a, b, rows, overwrite=True)
elif group == "update_race":
    assert_update_race(a, b, rows)
elif group == "cross_user":
    assert_cross_user(a, b, rows)
else:
    raise SystemExit(f"unknown race group: {group}")
