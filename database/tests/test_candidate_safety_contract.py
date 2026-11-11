from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = {
    "conditional-save": ROOT / "save_daily_entry_v2_2026-11-10.sql",
    "date-move": ROOT / "entry_date_move_v1_2026-11-10.sql",
    "move-capability": ROOT / "android_move_capability_v1_2026-11-11.sql",
}
OPERATOR_APPROVAL_FIELDS = (
    "diarium_production_migration_approval",
    "expected_database",
    "verified_backup_reference",
    "approved_change_window",
    "i_have_verified_backup_and_approve_this_database",
)


class ProductionCandidateSafetyContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = {name: path.read_text(encoding="utf-8") for name, path in MIGRATIONS.items()}
        cls.code = {
            name: re.sub(r"--[^\n]*", "", text).lower()
            for name, text in cls.sql.items()
        }
        cls.normalized = {
            name: re.sub(r"\s+", " ", text)
            for name, text in cls.code.items()
        }

    def test_every_migration_fails_closed_outside_disposable_test_databases(self):
        for name, sql in self.normalized.items():
            with self.subTest(migration=name):
                for required in (
                    "current_database() !~ '^diarium_test_",
                    "pg_temp.diarium_production_migration_approval",
                    "set_config('lock_timeout', '5s', true)",
                ) + OPERATOR_APPROVAL_FIELDS:
                    self.assertTrue(required in sql, f"{name} is missing {required}")

    def test_conditional_save_avoids_table_rewrite_and_scans(self):
        sql = self.normalized["conditional-save"]
        self.assertIn("add column if not exists revision bigint not null default 1", sql)
        self.assertIn("server_version_num", sql)
        self.assertNotRegex(
            sql,
            r"update public\.entries set revision = 1 where revision is null",
        )
        self.assertNotIn("validate constraint entries_revision_v2_positive", sql)

    def test_legacy_beta5_rpc_is_not_changed_or_revoked(self):
        sql = self.code["conditional-save"]
        legacy_mutations = re.findall(
            r"(?:create|alter|drop|revoke|grant)\s+[^;]*?public\.save_daily_entry\s*\(",
            sql,
            flags=re.DOTALL,
        )
        self.assertEqual([], legacy_mutations)

    def test_move_owner_has_only_required_rls_scoped_table_access(self):
        sql = self.normalized["date-move"]
        for required in (
            "alter function public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) owner to diarium_entry_move_owner",
            "grant select, update, delete on table public.entries to diarium_entry_move_owner",
            "grant select, update, delete on table public.scale_entries to diarium_entry_move_owner",
            "grant usage on schema public, auth, extensions to diarium_entry_move_owner",
            "nobypassrls",
            "user_id = auth.uid()",
            "revoke all on function public.move_daily_entry_v1",
        ):
            self.assertIn(required, sql)

    def test_move_capability_is_invoker_only_and_authenticated_only(self):
        sql = self.normalized["move-capability"]
        self.assertIn("security invoker", sql)
        self.assertIn(
            "revoke all on function public.android_move_capability_v1() from public, anon, service_role",
            sql,
        )
        self.assertIn(
            "grant execute on function public.android_move_capability_v1() to authenticated",
            sql,
        )
    def test_beta5_compatibility_is_exercised_before_cutoff(self):
        test = (ROOT / "tests" / "beta5_compatibility.sql").read_text(encoding="utf-8").lower()
        fixture = (ROOT / "tests" / "prepare_fixture.sql").read_text(encoding="utf-8").lower()
        for behavior in (
            "public.save_daily_entry(",
            "beta5 legacy update",
            "revision",
            "proacl",
            "rollback",
        ):
            self.assertIn(behavior, test)
        self.assertIn("create function public.save_daily_entry(payload jsonb)", fixture)
        self.assertNotIn("\\ir ../save_daily_entry_v2_2026-11-10.sql", fixture)

    def test_postgres_runner_covers_rerun_rollback_compatibility_and_three_race_suites(self):
        runner = (ROOT / "tests" / "run_candidate_postgres_tests.sh").read_text(encoding="utf-8")
        cas_runner = (ROOT / "tests" / "run_postgres_tests.sh").read_text(encoding="utf-8")
        move_runner = (ROOT / "tests" / "run_entry_date_move_tests.sh").read_text(encoding="utf-8")
        coverage = runner + cas_runner + move_runner
        for path in (
            "race_save_daily_entry_v2.sh",
            "race_entry_date_move_v1.sh",
            "race_cas_vs_move.sh",
            "probe_non_test_migration_guard.py",
            "capability_false_test.sql",
            "capability_privileges.sql",
        ):
            self.assertIn(path, coverage)
        self.assertGreaterEqual(cas_runner.count("save_daily_entry_v2_2026-11-10.sql"), 2)
        self.assertIn("beta5_compatibility.sql", cas_runner)
        self.assertIn("Failure rollback left unexpected candidate state", cas_runner)

    def test_operator_plan_requires_explicit_transaction_and_backup_metadata(self):
        plan = (ROOT / "CONDITIONAL_SAVE_MOVE_CANDIDATE_2026-11-11.md").read_text(encoding="utf-8").lower()
        for behavior in (
            "on commit drop",
            "verified_backup_reference",
            "approved_change_window",
            "rollback",
            "access exclusive",
            "do not revoke the legacy rpc",
        ):
            self.assertIn(behavior, plan)


if __name__ == "__main__":
    unittest.main(verbosity=2)
