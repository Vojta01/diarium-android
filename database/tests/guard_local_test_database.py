#!/usr/bin/env python3
"""Validate a disposable loopback PostgreSQL URL without printing credentials."""

import ipaddress
import os
import re
import sys
from urllib.parse import parse_qs, unquote, urlparse

raw = os.environ.get("LOCAL_TEST_DATABASE_URL", "")
parsed = urlparse(raw)
if parsed.scheme not in {"postgres", "postgresql"}:
    sys.exit("Refusing: LOCAL_TEST_DATABASE_URL must be a PostgreSQL URI.")

database = unquote(parsed.path.lstrip("/"))
if not re.fullmatch(r"diarium_test_[A-Za-z0-9_]+", database):
    sys.exit("Refusing: database name must start with diarium_test_.")

params = parse_qs(parsed.query, keep_blank_values=True)
if set(params) - {"sslmode", "connect_timeout", "application_name", "port"}:
    sys.exit("Refusing: unsupported connection URI options.")

host = parsed.hostname or ""
if host and host.lower() != "localhost":
    try:
        if not ipaddress.ip_address(host).is_loopback:
            sys.exit("Refusing: database host must be loopback or a local Unix socket.")
    except ValueError:
        sys.exit("Refusing: database host must be loopback or a local Unix socket.")

# The caller may print only this validated name, never the supplied URI.
print(database)
