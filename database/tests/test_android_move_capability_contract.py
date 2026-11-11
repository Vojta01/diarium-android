"""Static contract checks for the Android move deployment gate."""

from pathlib import Path
import re
import unittest


MIGRATION = Path(__file__).resolve().parents[1] / "android_move_capability_v1_2026-11-11.sql"


class AndroidMoveCapabilityContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.normalized = re.sub(r"\s+", " ", cls.sql.lower())

    def test_capability_is_a_read_only_versioned_postgrest_function(self):
        self.assertRegex(
            self.normalized,
            r"create or replace function public\.android_move_capability_v1\(\) returns jsonb language sql stable security invoker set search_path = ''",
        )
        self.assertIn("'contract', 'diarium.android.move-capability.v1'", self.normalized)
        self.assertIn("'move_daily_entry_v1'", self.normalized)
        self.assertIn("notify pgrst, 'reload schema'", self.normalized)

    def test_true_requires_exact_rpc_signature_argument_names_and_authenticated_execute(self):
        self.assertIn(
            "public.move_daily_entry_v1(pg_catalog.date, pg_catalog.uuid, pg_catalog.int8, pg_catalog.date, pg_catalog.bool, pg_catalog.uuid, pg_catalog.int8, pg_catalog.bool, pg_catalog.uuid)",
            self.normalized,
        )
        for argument in (
            "p_source_date",
            "p_source_id",
            "p_source_revision",
            "p_target_date",
            "p_target_expected_exists",
            "p_target_expected_id",
            "p_target_expected_revision",
            "p_confirm_overwrite",
            "p_request_id",
        ):
            self.assertIn(f"'{argument}'", self.normalized)
        self.assertIn("p.prosecdef", self.normalized)
        self.assertIn("p.prokind = 'f'", self.normalized)
        self.assertIn("p.prorettype = 'pg_catalog.jsonb'::pg_catalog.regtype", self.normalized)
        self.assertIn("has_function_privilege( 'authenticated', p.oid, 'execute'", self.normalized)
        self.assertRegex(self.normalized, r"coalesce\( .*?, false \)")

    def test_capability_rpc_is_not_public_or_anon_executable(self):
        self.assertIn(
            "revoke all on function public.android_move_capability_v1() from public, anon, service_role",
            self.normalized,
        )
        self.assertIn(
            "grant execute on function public.android_move_capability_v1() to authenticated",
            self.normalized,
        )

    def test_migration_has_no_data_writes(self):
        uncommented = re.sub(r"--[^\n]*", "", self.sql)
        self.assertNotRegex(uncommented, r"(?i)\b(insert\s+into|update\s+\w|delete\s+from)\b")


if __name__ == "__main__":
    unittest.main()
