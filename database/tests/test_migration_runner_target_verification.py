from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "apply_conditional_save_move_candidate.sh"
PROJECT_REF = "abcdefghijklmnopqrst"
DATABASE = "diarium_test_target_probe"
HOST = f"db.{PROJECT_REF}.supabase.co"
PGPASS_SECRET = "fake-passfile-secret-never-print"
INHERITED_PASSWORD = "fake-inherited-password-never-print"
REDIRECTION_VARS = (
    "PGHOSTADDR",
    "PGPASSWORD",
    "PGSERVICE",
    "PGSERVICEFILE",
    "PGOPTIONS",
)


class MigrationRunnerTargetVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="migration-runner-test-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bin_dir = self.directory / "bin"
        self.bin_dir.mkdir()
        self.capture_file = self.directory / "psql-calls.jsonl"
        self.passfile = self.directory / "pgpass"
        self.passfile.write_text(
            f"{HOST}:5432:{DATABASE}:migration_role:{PGPASS_SECRET}\n",
            encoding="utf-8",
        )
        self.passfile.chmod(0o600)
        self.install_fake_psql()

    def install_fake_psql(self, actual_database: str = DATABASE, actual_user: str = "migration_role"):
        fake_psql = self.bin_dir / "psql"
        fake_psql.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            f"capture = {str(self.capture_file)!r}\n"
            "names = ('PGHOSTADDR', 'PGPASSWORD', 'PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS', 'PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER')\n"
            "record = {'args': sys.argv[1:], 'env': {name: os.environ.get(name) for name in names}, 'sslmode': os.environ.get('PGSSLMODE'), 'passfile': os.environ.get('PGPASSFILE')}\n"
            "with open(capture, 'a', encoding='utf-8') as stream:\n"
            "    stream.write(json.dumps(record) + '\\n')\n"
            "if '-c' in sys.argv[1:]:\n"
            f"    print({actual_database!r} if 'current_database' in sys.argv[sys.argv.index('-c') + 1] else {actual_user!r})\n",
            encoding="utf-8",
        )
        fake_psql.chmod(0o700)

    def base_environment(self) -> dict[str, str]:
        return {
            "PATH": f"{self.bin_dir}{os.pathsep}{os.defpath}",
            "HOME": str(self.directory),
            "PGHOST": HOST,
            "PGPORT": "5432",
            "PGDATABASE": DATABASE,
            "PGUSER": "migration_role",
            "PGSSLMODE": "verify-full",
            "PGPASSFILE": str(self.passfile),
            "EXPECTED_DATABASE": DATABASE,
            "EXPECTED_SUPABASE_PROJECT_REF": PROJECT_REF,
            "VERIFIED_BACKUP_REFERENCE": "ci-synthetic-not-a-backup",
            "APPROVED_CHANGE_WINDOW": "disposable-ci-only",
            "PGHOSTADDR": "203.0.113.44",
            "PGPASSWORD": INHERITED_PASSWORD,
            "PGSERVICE": "hostile-service-profile",
            "PGSERVICEFILE": "/nonexistent/hostile-service.conf",
            "PGOPTIONS": "-c application_name=hostile-routing-probe",
        }

    def run_runner(self, **overrides) -> subprocess.CompletedProcess[str]:
        environment = self.base_environment()
        environment.update(overrides)
        return subprocess.run(
            ["bash", str(RUNNER)],
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def captured_calls(self) -> list[dict]:
        if not self.capture_file.exists():
            return []
        return [json.loads(line) for line in self.capture_file.read_text().splitlines()]

    def assert_no_secret_output(self, result: subprocess.CompletedProcess[str]):
        combined = result.stdout + result.stderr
        self.assertNotIn(PGPASS_SECRET, combined)
        self.assertNotIn(INHERITED_PASSWORD, combined)

    def test_uses_only_verified_host_ssl_and_private_passfile_without_redirection_env(self):
        result = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.captured_calls()
        self.assertEqual(3, len(calls))
        for call in calls:
            for variable in REDIRECTION_VARS + ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER"):
                self.assertIsNone(call["env"][variable], variable)
            self.assertEqual("verify-full", call["sslmode"])
            self.assertEqual(str(self.passfile), call["passfile"])
            args = call["args"]
            self.assertEqual(HOST, args[args.index("--host") + 1])
            self.assertEqual("5432", args[args.index("--port") + 1])
            self.assertEqual("migration_role", args[args.index("--username") + 1])
            self.assertEqual(DATABASE, args[args.index("--dbname") + 1])
            self.assertIn("--no-password", args)
            self.assertNotIn("--password", args)
            self.assertNotIn(PGPASS_SECRET, args)
            self.assertNotIn(INHERITED_PASSWORD, args)
        self.assert_no_secret_output(result)

    def test_refuses_host_not_bound_to_the_expected_supabase_project_ref(self):
        result = self.run_runner(PGHOST="db.otherprojectref000000.supabase.co")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.captured_calls())
        self.assert_no_secret_output(result)

    def test_refuses_non_verifying_tls_mode(self):
        result = self.run_runner(PGSSLMODE="require")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.captured_calls())
        self.assert_no_secret_output(result)

    def test_refuses_wrong_server_database_before_applying_sql(self):
        self.install_fake_psql(actual_database="wrong_database")
        result = self.run_runner()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.captured_calls()))
        self.assert_no_secret_output(result)

    def test_refuses_wrong_authenticated_role_before_applying_sql(self):
        self.install_fake_psql(actual_user="unexpected_role")
        result = self.run_runner()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(self.captured_calls()))
        self.assert_no_secret_output(result)

    def test_refuses_pgpass_entry_that_does_not_exactly_match_the_target(self):
        self.passfile.write_text(
            f"*:5432:{DATABASE}:migration_role:{PGPASS_SECRET}\n",
            encoding="utf-8",
        )
        self.passfile.chmod(0o600)
        result = self.run_runner()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.captured_calls())
        self.assert_no_secret_output(result)


if __name__ == "__main__":
    unittest.main(verbosity=2)
