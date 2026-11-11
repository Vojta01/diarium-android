from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "entry_date_move_v1_2026-11-10.sql"
BASE_CAS = ROOT / "save_daily_entry_v2_2026-11-10.sql"
WORKFLOW = ROOT.parent / ".github" / "workflows" / "entry-date-move-postgres-tests.yml"


class EntryDateMoveSqlContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.lower = cls.sql.lower()
        cls.code = re.sub(r"--[^\n]*", "", cls.lower)
        cls.cas = BASE_CAS.read_text(encoding="utf-8").lower()
        cls.workflow = WORKFLOW.read_text(encoding="utf-8").lower()

    def test_transactional_script_uses_single_backslash_psql_meta_commands(self):
        lines = (ROOT / "tests" / "entry_date_move_v1_transactional.sql").read_text(
            encoding="utf-8"
        ).splitlines()
        self.assertEqual(lines[0], r"\set ON_ERROR_STOP on")
        self.assertEqual(
            lines[-1],
            r"\echo 'All atomic date-move PostgreSQL integration assertions passed.'",
        )

    def test_adds_a_separate_authenticated_rpc_without_changing_cas_save(self):
        self.assertRegex(
            self.code,
            r"create\s+or\s+replace\s+function\s+public\.move_daily_entry_v1\s*\(",
        )
        self.assertNotRegex(
            self.code,
            r"(?:create|alter|drop|revoke|grant)\s+[^;]*public\.save_daily_entry_v2\s*\(",
        )
        self.assertIn("public.save_daily_entry_v2", self.cas)

    def test_source_and_destination_tokens_are_checked_inside_one_transaction(self):
        for token in (
            "p_source_date", "p_source_id", "p_source_revision",
            "p_target_date", "p_target_expected_exists",
            "p_target_expected_id", "p_target_expected_revision",
            "p_confirm_overwrite", "p_request_id",
        ):
            with self.subTest(token=token):
                self.assertIn(token, self.code)
        self.assertIn("for update", self.code)
        self.assertIn("stale_source", self.code)
        self.assertIn("stale_target", self.code)
        self.assertIn("confirmation_required", self.code)
        self.assertIn("destination_exists", self.code)
        self.assertNotRegex(
            self.code,
            r"on\s+conflict\s*\(\s*user_id\s*,\s*date\s*\)\s*do\s+update",
        )

    def test_absent_date_keys_and_reversed_moves_use_shared_sorted_advisory_locks(self):
        self.assertIn("hashtextextended", self.code)
        self.assertIn("pg_advisory_xact_lock", self.code)
        self.assertRegex(self.code, r"if\s+v_source_lock\s*<\s*v_target_lock")
        base_key = re.search(
            r"hashtextextended\s*\(\s*v_actor::text\s*\|\|\s*':'\s*\|\|\s*pg_catalog\.to_char\(v_date,\s*'yyyy-mm-dd'\),\s*0\s*\)",
            self.cas,
        )
        move_key = re.search(
            r"hashtextextended\s*\(\s*v_actor::text\s*\|\|\s*':'\s*\|\|\s*pg_catalog\.to_char\(p_(?:source|target)_date,\s*'yyyy-mm-dd'\),\s*0\s*\)",
            self.code,
        )
        self.assertIsNotNone(base_key)
        self.assertIsNotNone(move_key)

    def test_request_receipt_is_bound_to_user_and_full_intent_before_date_locks(self):
        self.assertIn("entry_move_v1_receipts", self.code)
        self.assertIn("extensions.digest", self.code)
        self.assertIn("sha256", self.code)
        claim = self.code.index("insert into public.entry_move_v1_receipts")
        first_lock = self.code.index("pg_advisory_xact_lock")
        self.assertLess(claim, first_lock)
        self.assertIn("request_id was already used for a different request", self.code)
        self.assertIn("replayed", self.code)
        receipt = re.search(
            r"create\s+table\s+if\s+not\s+exists\s+public\.entry_move_v1_receipts\s*\((.*?)\);",
            self.code,
            re.DOTALL,
        )
        if receipt is None:
            self.fail("expected the move receipt table definition")
        self.assertNotIn("payload jsonb", receipt.group(1))
        self.assertNotIn("note text", receipt.group(1))

    def test_same_date_noop_requires_a_matching_destination_token(self):
        same_date = self.lower[self.lower.index("-- a same-date move"):]
        self.assertIn("if not p_target_expected_exists", same_date)
        self.assertIn("p_target_expected_id <> v_source.id", same_date)
        self.assertIn("p_target_expected_revision <> v_source.revision", same_date)
        self.assertIn("'destination_exists'", same_date)
        self.assertIn("'stale_target'", same_date)

    def test_owner_checks_rls_and_grants_are_narrow(self):
        self.assertIn("auth.uid()", self.code)
        self.assertIn("auth.jwt()", self.code)
        self.assertIn("v_actor", self.code)
        self.assertIn("security definer", self.code)
        self.assertRegex(self.code, r"set\s+search_path\s*=\s*''")
        self.assertIn("set row_security = on", self.code)
        self.assertIn("nobypassrls", self.code)
        self.assertIn("grant execute on function", self.code)
        self.assertRegex(self.code, r"grant\s+execute\s+on\s+function[^;]+to\s+authenticated")
        self.assertRegex(self.code, r"revoke\s+all\s+on\s+function[^;]+from\s+service_role")
        self.assertIn("user_id = auth.uid()", self.code)

    def test_workflow_is_branch_scoped_secret_free_and_uses_ephemeral_postgres(self):
        self.assertIn("on:", self.workflow)
        self.assertIn("push:", self.workflow)
        self.assertIn("branches:", self.workflow)
        self.assertIn("- ci/sql-cas-move-production-candidate-20261111", self.workflow)
        self.assertIn("permissions:", self.workflow)
        self.assertIn("contents: read", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertIn("image: postgres:16", self.workflow)
        self.assertIn("diarium_test_cas_move_candidate", self.workflow)
        self.assertNotIn("workflow_dispatch", self.workflow)
        self.assertNotIn("${{ runner.temp }}", self.workflow)
        self.assertNotIn("secrets.", self.workflow)

    def test_entry_scale_mirrors_and_photo_pointer_move_atomically(self):
        self.assertIn("delete from public.scale_entries", self.code)
        self.assertIn("update public.scale_entries", self.code)
        self.assertIn("update public.entries", self.code)
        self.assertIn("delete from public.entries", self.code)
        self.assertIn("photo_path", self.code)
        self.assertIn("scale_values", self.lower)
        self.assertIn("retained exactly", self.lower)
        self.assertNotIn("storage.objects", self.code)
        self.assertNotIn("http", self.code)

    def test_overwrite_receipt_hands_off_only_a_pending_actor_bound_photo_candidate(self):
        self.assertIn("photo_cleanup_candidate", self.code)
        self.assertIn("v_target.photo_path is distinct from v_moved.photo_path", self.code)
        self.assertIn("v_target_found", self.code)
        self.assertIn("'actor_id', v_actor", self.code)
        self.assertIn("'photo_path', v_target.photo_path", self.code)
        self.assertIn("'status', 'pending_validation'", self.code)
        self.assertIn("'safe_to_delete', false", self.code)
        self.assertIn("'uncertain_validation_action', 'retain_object'", self.code)
        self.assertIn("verify_storage_object_owner", self.code)
        self.assertIn("verify_no_remaining_entry_references", self.code)
        self.assertIn("v_outcome", self.code)
        self.assertNotRegex(self.code, r"delete\s+from\s+(?:storage\.|public\.storage)")

    def test_transactional_tests_cover_photo_handoff_and_identity_idempotency_edges(self):
        tests = (ROOT / "tests" / "entry_date_move_v1_transactional.sql").read_text(
            encoding="utf-8"
        ).lower()
        for behavior in (
            "shared-source-target.jpg",
            "cleanup-review.jpg",
            "target without photo",
            "rolled-back overwrite",
            "changed-intent request-id reuse",
            "delete/reinsert changes row identity",
            "identical pending cleanup receipt",
        ):
            with self.subTest(behavior=behavior):
                self.assertIn(behavior, tests)


if __name__ == "__main__":
    unittest.main(verbosity=2)
