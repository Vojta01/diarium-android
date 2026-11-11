#!/usr/bin/env python3
"""Validate a loopback-only disposable PostgreSQL target and run psql safely."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from urllib.parse import unquote, urlparse


TEST_DATABASE = re.compile(r"diarium_test_[A-Za-z0-9_]+\Z")
LOOPBACK_HOSTS = {"127.0.0.1", "::1"}
SAFE_PSQL_ENVIRONMENT = {
    "PATH": "/usr/bin:/bin",
    "LC_ALL": "C",
    # Prevent libpq from reading a caller's default or ambient password file.
    "PGPASSFILE": os.devnull,
}
REDIRECTING_PSQL_OPTIONS = {
    "--dbname",
    "--host",
    "--hostaddr",
    "--port",
    "--service",
    "--servicefile",
    "--username",
}
REDIRECTING_PSQL_SHORT_OPTIONS = {"-d", "-h", "-p", "-U"}


class GuardRefusal(ValueError):
    """A safe, non-secret-bearing reason that a target must be refused."""


def validate_local_database_url(raw: str, *, expected_database: str | None = None) -> str:
    """Return the validated database name, never exposing URL contents."""
    if not raw:
        raise GuardRefusal("Refusing: LOCAL_TEST_DATABASE_URL is required.")
    if raw != raw.strip() or any(ord(character) <= 0x20 or ord(character) == 0x7F for character in raw):
        raise GuardRefusal("Refusing: database URL is malformed.")
    try:
        parsed = urlparse(raw)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        raise GuardRefusal("Refusing: database URL is malformed.") from None

    if parsed.scheme not in {"postgres", "postgresql"}:
        raise GuardRefusal("Refusing: LOCAL_TEST_DATABASE_URL must be a PostgreSQL URI.")
    if host not in LOOPBACK_HOSTS:
        raise GuardRefusal("Refusing: database host must be explicit numeric loopback 127.0.0.1 or ::1.")
    if port is not None and not 1 <= port <= 65535:
        raise GuardRefusal("Refusing: database port is invalid.")

    # No libpq URI parameters are needed by this harness. Reject the entire
    # query/fragment surface so hostaddr, service, SSL paths, and future
    # connection overrides cannot supersede the numeric loopback authority.
    if parsed.params or parsed.query or parsed.fragment or "?" in raw or "#" in raw:
        raise GuardRefusal("Refusing: database URI query, path, and fragment options are unsupported.")

    if not parsed.path.startswith("/") or parsed.path.count("/") != 1:
        raise GuardRefusal("Refusing: database URI must name exactly one database.")
    database = unquote(parsed.path[1:])
    if expected_database is not None:
        if database != expected_database:
            raise GuardRefusal("Refusing: migration guard requires its exact disposable database.")
    elif not TEST_DATABASE.fullmatch(database):
        raise GuardRefusal("Refusing: database name must start with diarium_test_.")
    return database


def validate_psql_arguments(arguments: list[str]) -> None:
    """Reject psql flags that can replace the validated connection target."""
    for argument in arguments:
        if argument in REDIRECTING_PSQL_OPTIONS or argument in REDIRECTING_PSQL_SHORT_OPTIONS:
            raise GuardRefusal("Refusing: psql connection override options are unsupported.")
        if argument.startswith("--"):
            option = argument.partition("=")[0]
            if option in REDIRECTING_PSQL_OPTIONS:
                raise GuardRefusal("Refusing: psql connection override options are unsupported.")
        elif len(argument) > 2 and argument[:2] in REDIRECTING_PSQL_SHORT_OPTIONS:
            raise GuardRefusal("Refusing: psql connection override options are unsupported.")


def psql_command_for_url(
    raw: str,
    arguments: list[str],
    *,
    expected_database: str | None = None,
) -> list[str]:
    """Build a psql argv only after validating the URI and options."""
    validate_local_database_url(raw, expected_database=expected_database)
    validate_psql_arguments(arguments)
    executable = shutil.which("psql")
    if executable is None:
        raise GuardRefusal("Refusing: PostgreSQL client psql was not found.")
    return [executable, "--no-password", raw, *arguments]


def sanitized_psql_environment() -> dict[str, str]:
    """Return the complete environment passed to every guarded psql process."""
    return dict(SAFE_PSQL_ENVIRONMENT)


def run_psql_for_url(
    raw: str,
    arguments: list[str],
    *,
    expected_database: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    """Run psql for an explicitly validated local URL with a clean environment."""
    command = psql_command_for_url(raw, arguments, expected_database=expected_database)
    return subprocess.run(
        command,
        env=sanitized_psql_environment(),
        text=True,
        capture_output=True,
        check=check,
    )


def _main(arguments: list[str]) -> int:
    if arguments and arguments[0] == "--exec":
        raw = os.environ.get("LOCAL_TEST_DATABASE_URL", "")
        try:
            command = psql_command_for_url(raw, arguments[1:])
            os.execve(command[0], command, sanitized_psql_environment())
        except GuardRefusal as error:
            print(str(error), file=sys.stderr)
            return 1
        except OSError:
            print("Refusing: PostgreSQL client could not be started.", file=sys.stderr)
            return 1
        return 1

    if arguments:
        print("Usage: guard_local_test_database.py [--exec psql-options...]", file=sys.stderr)
        return 2
    try:
        print(validate_local_database_url(os.environ.get("LOCAL_TEST_DATABASE_URL", "")))
    except GuardRefusal as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
