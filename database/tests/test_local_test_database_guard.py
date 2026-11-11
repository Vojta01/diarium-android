from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


DATABASE_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = DATABASE_DIR / "tests"
GUARD = TESTS_DIR / "guard_local_test_database.py"
LOCAL_URL = "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard"
SAFE_ENV = {
    "PATH": "/usr/bin:/bin",
    "LC_ALL": "C",
    "PGPASSFILE": os.devnull,
}
REDIRECTION_ENV = {
    "PGHOSTADDR": "203.0.113.44",
    "PGPASSWORD": "fake-inherited-password-never-print",
    "PGSERVICE": "hostile-service-profile",
    "PGSERVICEFILE": "/nonexistent/hostile-service.conf",
    "PGOPTIONS": "-c application_name=hostile-routing-probe",
    "PGHOST": "db.external.invalid",
    "PGPORT": "6543",
    "PGDATABASE": "production",
    "PGUSER": "production_role",
    "PGSSLMODE": "verify-full",
    "PGSSLROOTCERT": "/nonexistent/hostile-root.crt",
}


class LocalTestDatabaseGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="local-db-guard-test-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bin_dir = self.directory / "bin"
        self.bin_dir.mkdir()
        self.capture = self.directory / "psql-calls.jsonl"
        self.install_fake_psql()

    def install_fake_psql(self):
        fake = self.bin_dir / "psql"
        fake.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "from pathlib import Path\n"
            f"capture = {str(self.capture)!r}\n"
            "with open(capture, 'a', encoding='utf-8') as stream:\n"
            "    stream.write(json.dumps({'args': sys.argv[1:], 'env': dict(os.environ)}) + '\\n')\n"
            "if Path(capture).with_suffix('.fail').exists():\n"
            "    print('fake psql failure: ' + ' '.join(sys.argv[1:]), file=sys.stderr)\n"
            "    raise SystemExit(23)\n",
            encoding="utf-8",
        )
        fake.chmod(0o700)

    def base_environment(self, **overrides) -> dict[str, str]:
        environment = {
            "PATH": f"{self.bin_dir}{os.pathsep}{os.path.dirname(sys.executable)}{os.pathsep}/usr/bin:/bin",
            "HOME": str(self.directory),
            "LOCAL_TEST_DATABASE_URL": LOCAL_URL,
            **REDIRECTION_ENV,
        }
        environment.update(overrides)
        return environment

    def run_guard(self, arguments=(), **environment_overrides):
        environment = self.base_environment(**environment_overrides)
        return subprocess.run(
            [sys.executable, str(GUARD), "--exec", *arguments],
            env=environment,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )

    def captured_calls(self) -> list[dict]:
        if not self.capture.exists():
            return []
        return [json.loads(line) for line in self.capture.read_text(encoding="utf-8").splitlines()]

    def assert_no_credentials_in_output(self, result: subprocess.CompletedProcess[str]):
        output = result.stdout + result.stderr
        self.assertNotIn("fake-pass", output)
        self.assertNotIn("fake-inherited-password-never-print", output)

    def test_inherited_libpq_redirection_environment_is_not_passed_to_psql(self):
        result = self.run_guard((
            "-X", "-1", "-qAt", "-v", "ON_ERROR_STOP=1",
            "-v", "expected_database=diarium_test_guard", "-c", "SELECT 1",
        ))
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.captured_calls()
        self.assertEqual(1, len(calls))
        self.assertEqual(LOCAL_URL, calls[0]["args"][1])
        self.assertEqual(SAFE_ENV, calls[0]["env"])
        self.assertIn("--no-password", calls[0]["args"])
        self.assert_no_credentials_in_output(result)

        file_result = self.run_guard(("-X", "-f", "/dev/null"))
        self.assertEqual(0, file_result.returncode, file_result.stderr)
        calls = self.captured_calls()
        self.assertEqual(2, len(calls))
        self.assertIn("-f", calls[1]["args"])
        self.assertEqual(SAFE_ENV, calls[1]["env"])

    def test_hostaddr_and_service_uri_query_options_are_rejected_before_psql(self):
        hostile_urls = (
            "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard?hostaddr=203.0.113.44",
            "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard?service=hostile-profile",
            "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard?host=db.external.invalid",
            "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard?sslrootcert=%2Ftmp%2Fhostile.crt",
        )
        for url in hostile_urls:
            with self.subTest(option=url.partition("?")[2].partition("=")[0]):
                result = self.run_guard(("-X", "-c", "SELECT 1"), LOCAL_TEST_DATABASE_URL=url)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.captured_calls())
                self.assert_no_credentials_in_output(result)

    def test_only_explicit_numeric_loopback_hosts_are_allowed(self):
        ipv6_url = "postgresql://fake_user:fake-pass@[::1]:5432/diarium_test_ipv6"
        result = self.run_guard(("-X", "-c", "SELECT 1"), LOCAL_TEST_DATABASE_URL=ipv6_url)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(ipv6_url, self.captured_calls()[0]["args"][1])
        self.assertEqual(SAFE_ENV, self.captured_calls()[0]["env"])

        self.capture.unlink()
        for host in ("localhost", "db.external.invalid", "203.0.113.44"):
            with self.subTest(host=host):
                result = self.run_guard(("-X", "-c", "SELECT 1"), LOCAL_TEST_DATABASE_URL=LOCAL_URL.replace("127.0.0.1", host))
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.captured_calls())
                self.assert_no_credentials_in_output(result)

    def test_connection_override_psql_options_are_rejected(self):
        for option in (("-h", "203.0.113.44"), ("--hostaddr=203.0.113.44",), ("--service", "hostile"), ("-d", "production")):
            with self.subTest(option=option):
                result = self.run_guard(option)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.captured_calls())
                self.assert_no_credentials_in_output(result)

    def test_connection_override_bundles_long_abbreviations_and_positional_targets_are_rejected(self):
        hostile_arguments = (
            ("-Xh203.0.113.44",),
            ("-Xdproduction",),
            ("--dbna=production",),
            ("--", "production"),
            ("production",),
        )
        for arguments in hostile_arguments:
            with self.subTest(arguments=arguments):
                result = self.run_guard(arguments)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.captured_calls())
                self.assert_no_credentials_in_output(result)

    def test_expected_database_cannot_create_an_arbitrary_database_allowlist(self):
        code = (
            "import os; from guard_local_test_database import validate_local_database_url; "
            "print(validate_local_database_url(os.environ['LOCAL_TEST_DATABASE_URL'], "
            "expected_database='production'))"
        )
        environment = self.base_environment(
            LOCAL_TEST_DATABASE_URL="postgresql://fake_user:fake-pass@127.0.0.1:5432/production",
            PYTHONPATH=str(TESTS_DIR),
        )
        result = subprocess.run(
            [sys.executable, "-c", code], env=environment, capture_output=True,
            text=True, timeout=10, check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assert_no_credentials_in_output(result)

    def test_psql_failure_redacts_url_and_credentials_while_preserving_status(self):
        self.capture.with_suffix(".fail").touch()
        url = "postgresql://fake_user:fake-pass@127.0.0.1:5432/diarium_test_guard"
        code = """
import os
from guard_local_test_database import run_psql_for_url
try:
    run_psql_for_url(os.environ['LOCAL_TEST_DATABASE_URL'], ['-X', '-c', 'SELECT 1'])
except Exception as error:
    print(f'safe_error={error}')
    print(f'status={getattr(error, "returncode", None)}')
else:
    raise SystemExit('expected psql failure')
"""
        environment = self.base_environment(
            LOCAL_TEST_DATABASE_URL=url,
            PYTHONPATH=str(TESTS_DIR),
        )
        result = subprocess.run(
            [sys.executable, "-c", code], env=environment, capture_output=True,
            text=True, timeout=10, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("status=23", result.stdout)
        self.assertNotIn("fake-pass", result.stdout + result.stderr)
        self.assertNotIn(url, result.stdout + result.stderr)

        exec_result = self.run_guard(
            ("-X", "-c", "SELECT 1"), LOCAL_TEST_DATABASE_URL=url,
        )
        self.assertEqual(23, exec_result.returncode)
        self.assertNotIn("fake-pass", exec_result.stdout + exec_result.stderr)
        self.assertNotIn(url, exec_result.stdout + exec_result.stderr)

    def test_every_shell_runner_with_psql_sources_the_guard(self):
        for script in TESTS_DIR.rglob("*.sh"):
            if script.name == "guard_local_test_database.sh":
                continue
            text = script.read_text(encoding="utf-8")
            if "psql" in text:
                with self.subTest(script=script.name):
                    self.assertIn('source "$SCRIPT_DIR/guard_local_test_database.sh"', text)

    def test_non_test_migration_probe_uses_the_same_sanitized_psql_environment(self):
        probe = TESTS_DIR / "probe_non_test_migration_guard.py"
        url = "postgresql://fake_user:fake-pass@127.0.0.1:5432/postgres"
        result = subprocess.run(
            [sys.executable, str(probe)],
            env=self.base_environment(NON_TEST_GUARD_DATABASE_URL=url),
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        # The fake client cannot emit SQL guard errors; the probe stops at its
        # first expected-error assertion after exercising its guarded call.
        self.assertNotEqual(0, result.returncode)
        calls = self.captured_calls()
        self.assertEqual(1, len(calls))
        self.assertEqual(SAFE_ENV, calls[0]["env"])
        self.assertEqual(url, calls[0]["args"][1])
        self.assert_no_credentials_in_output(result)

    def test_non_test_migration_probe_rejects_uri_service_override(self):
        probe = TESTS_DIR / "probe_non_test_migration_guard.py"
        url = "postgresql://fake_user:fake-pass@127.0.0.1:5432/postgres?service=hostile"
        result = subprocess.run(
            [sys.executable, str(probe)],
            env=self.base_environment(NON_TEST_GUARD_DATABASE_URL=url),
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.captured_calls())
        self.assert_no_credentials_in_output(result)

    def test_concurrent_race_subcalls_use_the_sanitized_guarded_psql(self):
        race_script = TESTS_DIR / "race_cas_vs_move.sh"
        environment = self.base_environment(TMPDIR=str(self.directory))
        result = subprocess.run(
            ["bash", str(race_script)],
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        # The fake client has no database behavior, so the race assertion stops
        # after seeding and exercising both concurrent RPC calls plus its snapshot.
        calls = self.captured_calls()
        self.assertEqual(4, len(calls), result.stderr)
        for call in calls:
            self.assertEqual(SAFE_ENV, call["env"])
            self.assertEqual(LOCAL_URL, call["args"][1])
        concurrent_sql = [call["args"][-1] for call in calls if "-c" in call["args"]]
        self.assertTrue(any("move_daily_entry_v1" in sql for sql in concurrent_sql))
        self.assertTrue(any("save_daily_entry_v2" in sql for sql in concurrent_sql))
        self.assertNotEqual(0, result.returncode)
        self.assert_no_credentials_in_output(result)


if __name__ == "__main__":
    unittest.main(verbosity=2)
