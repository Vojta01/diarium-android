#!/usr/bin/env bash
# Source this helper in every SQL test runner before invoking psql.
_DIARIUM_TEST_DATABASE_GUARD="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/guard_local_test_database.py"

psql() {
  python3 "$_DIARIUM_TEST_DATABASE_GUARD" --exec "$@"
}
