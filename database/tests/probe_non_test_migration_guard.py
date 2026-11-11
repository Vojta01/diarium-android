#!/usr/bin/env python3
"""Exercise the non-test migration gate only on the local CI postgres DB."""

from __future__ import annotations

import ipaddress
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = {
    "cas": ROOT / "save_daily_entry_v2_2026-11-10.sql",
    "move": ROOT / "entry_date_move_v1_2026-11-10.sql",
    "capability": ROOT / "android_move_capability_v1_2026-11-11.sql",
}
URL = os.environ.get("NON_TEST_GUARD_DATABASE_URL", "")
parsed = urlparse(URL)
database = unquote(parsed.path.lstrip("/"))
if parsed.scheme not in {"postgres", "postgresql"} or database != "postgres":
    sys.exit("Refusing: guard probe requires the exact disposable postgres database.")
host = parsed.hostname or ""
if host and host.lower() != "localhost":
    try:
        if not ipaddress.ip_address(host).is_loopback:
            sys.exit("Refusing: guard probe host must be loopback or a local Unix socket.")
    except ValueError:
        sys.exit("Refusing: guard probe host must be loopback or a local Unix socket.")


def psql(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["psql", URL, "-X", "-v", "ON_ERROR_STOP=1", *args],
        text=True,
        capture_output=True,
        check=check,
    )


expected_guard_error = "production candidate refuses non-test database without transaction-local operator approval"
for migration in MIGRATIONS.values():
    result = psql("-1", "-f", str(migration), check=False)
    output = result.stdout + result.stderr
    if result.returncode == 0 or expected_guard_error not in output:
        sys.exit(f"Fail-closed guard did not reject unapproved {migration.name}.")

# These operator-approval values are synthetic CI markers, not backup evidence
# or a production approval. The probe only verifies the guard contract.
with tempfile.TemporaryDirectory(prefix="diarium-guard-probe-") as scratch:
    for name in ("cas", "move"):
        expected_after_guard = {
            "cas": "save_daily_entry_v2 requires public.entries, public.scales, and public.scale_entries",
            "move": "date-move foundation requires the CAS fixture and save_daily_entry_v2",
        }[name]
        probe = Path(scratch) / f"{name}.sql"
        probe.write_text(
            "\\set ON_ERROR_STOP on\n"
            "BEGIN;\n"
            "CREATE TEMP TABLE diarium_production_migration_approval ("
            "expected_database text NOT NULL, verified_backup_reference text NOT NULL, "
            "approved_change_window text NOT NULL, confirmation text NOT NULL) ON COMMIT DROP;\n"
            "INSERT INTO pg_temp.diarium_production_migration_approval VALUES ("
            "'postgres', 'ci-synthetic-reference-only', 'ci-test-window-only', "
            "'I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE');\n"
            f"\\i {MIGRATIONS[name]}\n",
            encoding="utf-8",
        )
        result = psql("-f", str(probe), check=False)
        output = result.stdout + result.stderr
        if result.returncode == 0 or expected_guard_error in output or expected_after_guard not in output:
            sys.exit(f"Valid synthetic approval did not pass the guard for {name} to the expected preflight.")

    capability_probe = Path(scratch) / "capability.sql"
    capability_probe.write_text(
        "\\set ON_ERROR_STOP on\n"
        "BEGIN;\n"
        "CREATE TEMP TABLE diarium_production_migration_approval ("
        "expected_database text NOT NULL, verified_backup_reference text NOT NULL, "
        "approved_change_window text NOT NULL, confirmation text NOT NULL) ON COMMIT DROP;\n"
        "INSERT INTO pg_temp.diarium_production_migration_approval VALUES ("
        "'postgres', 'ci-synthetic-reference-only', 'ci-test-window-only', "
        "'I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE');\n"
        f"\\i {MIGRATIONS['capability']}\n"
        "SELECT public.android_move_capability_v1() ->> 'move_daily_entry_v1';\n"
        "ROLLBACK;\n",
        encoding="utf-8",
    )
    result = psql("-f", str(capability_probe), check=False)
    output = result.stdout + result.stderr
    if result.returncode != 0 or "false" not in output:
        sys.exit("Valid synthetic approval did not allow the capability migration probe to fail closed.")

state = psql(
    "-qAt",
    "-c",
    "SELECT CASE WHEN "
    "pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)') IS NULL "
    "AND pg_catalog.to_regprocedure('public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)') IS NULL "
    "AND pg_catalog.to_regprocedure('public.android_move_capability_v1()') IS NULL "
    "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'pgcrypto') "
    "THEN 'clean' ELSE 'unexpected candidate objects persisted' END;",
).stdout.strip()
if state != "clean":
    sys.exit("A rolled-back or rejected guard probe left candidate objects in the disposable postgres database.")

print("PASS non-test gate rejects all migrations without approval; synthetic temporary approval reaches expected preflight and rolls back.")
