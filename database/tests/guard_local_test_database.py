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
PSQL_FLAG_OPTIONS = {"1", "A", "q", "t", "X"}
PSQL_VALUE_OPTIONS = {"c", "f", "v"}
PSQL_VARIABLE_ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=.*\Z")


class GuardRefusal(ValueError):
    """A safe, non-secret-bearing reason that a target must be refused."""


class PsqlExecutionError(RuntimeError):
    """A psql failure with a safe message and the original exit status."""

    def __init__(self, returncode: int):
        self.returncode = returncode
        super().__init__(f"psql exited with status {returncode}.")


class PsqlStartError(RuntimeError):
    """A safe error raised when the psql executable cannot be started."""


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
        if expected_database != "postgres" and not TEST_DATABASE.fullmatch(expected_database):
            raise GuardRefusal("Refusing: expected database must be an exact disposable test name or the postgres probe.")
        if database != expected_database:
            raise GuardRefusal("Refusing: migration guard requires its exact disposable database.")
    elif not TEST_DATABASE.fullmatch(database):
        raise GuardRefusal("Refusing: database name must start with diarium_test_.")
    return database


def validate_psql_arguments(arguments: list[str]) -> None:
    """Accept only the psql options required by the test harness callers."""
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument == "--" or argument.startswith("--") or argument == "-" or not argument.startswith("-"):
            raise GuardRefusal("Refusing: unsupported psql argument or positional connection override.")

        options = argument[1:]
        offset = 0
        while offset < len(options):
            option = options[offset]
            if option in PSQL_FLAG_OPTIONS:
                offset += 1
                continue
            if option not in PSQL_VALUE_OPTIONS:
                raise GuardRefusal("Refusing: unsupported psql option; only the test-harness allowlist is accepted.")

            value = options[offset + 1:]
            if not value:
                index += 1
                if index >= len(arguments):
                    raise GuardRefusal("Refusing: psql option is missing its required value.")
                value = arguments[index]
            if not value:
                raise GuardRefusal("Refusing: psql option value must not be empty.")
            if option == "v" and not PSQL_VARIABLE_ASSIGNMENT.fullmatch(value):
                raise GuardRefusal("Refusing: psql variable options must use NAME=VALUE form.")
            # c/f/v consume the rest of a short-option token or the next argv
            # element. Never reinterpret the value as another psql option.
            break
        index += 1


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


def _redact_psql_output(output: str, raw_url: str) -> str:
    """Remove the supplied URL and URI credentials from captured psql text."""
    if not output:
        return output
    redacted = output.replace(raw_url, "[database URL redacted]")
    try:
        parsed = urlparse(raw_url)
    except ValueError:
        return redacted

    authority = raw_url.partition("://")[2].partition("/")[0]
    userinfo = authority.rpartition("@")[0] if "@" in authority else ""
    secrets = {userinfo, parsed.username or "", parsed.password or ""}
    secrets.update(unquote(secret) for secret in tuple(secrets) if secret)
    for secret in sorted((secret for secret in secrets if secret), key=len, reverse=True):
        redacted = redacted.replace(secret, "[redacted]")
    return redacted


def run_psql_for_url(
    raw: str,
    arguments: list[str],
    *,
    expected_database: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    """Run psql safely without returning credential-bearing argv or diagnostics."""
    command = psql_command_for_url(raw, arguments, expected_database=expected_database)
    try:
        completed = subprocess.run(
            command,
            env=sanitized_psql_environment(),
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError:
        raise PsqlStartError("Refusing: PostgreSQL client could not be started.") from None

    result = subprocess.CompletedProcess(
        args="psql",
        returncode=completed.returncode,
        stdout=_redact_psql_output(completed.stdout, raw),
        stderr=_redact_psql_output(completed.stderr, raw),
    )
    if check and result.returncode != 0:
        raise PsqlExecutionError(result.returncode)
    return result


def _main(arguments: list[str]) -> int:
    if arguments and arguments[0] == "--exec":
        raw = os.environ.get("LOCAL_TEST_DATABASE_URL", "")
        try:
            result = run_psql_for_url(raw, arguments[1:], check=False)
        except GuardRefusal as error:
            print(str(error), file=sys.stderr)
            return 1
        except PsqlStartError as error:
            print(str(error), file=sys.stderr)
            return 1
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        return result.returncode

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
