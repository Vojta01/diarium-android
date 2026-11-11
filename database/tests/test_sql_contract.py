from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "save_daily_entry_v2_2026-11-10.sql"


class ConditionalSaveSqlContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.lower = cls.sql.lower()
        cls.code = re.sub(r"--[^\n]*", "", cls.lower)

    def test_adds_a_new_rpc_without_replacing_or_revoking_beta5_rpc(self):
        self.assertRegex(
            self.code,
            r"create\s+or\s+replace\s+function\s+public\.save_daily_entry_v2\s*\(",
        )
        legacy_mutations = re.findall(
            r"(?:create|alter|drop|revoke|grant)\s+[^;]*?public\.save_daily_entry\s*\(",
            self.code,
            flags=re.DOTALL,
        )
        self.assertEqual([], legacy_mutations, "the beta5 RPC must remain untouched")

    def test_uses_atomic_row_and_absent_key_guards_without_entry_upsert(self):
        self.assertIn("pg_advisory_xact_lock", self.lower)
        self.assertRegex(self.lower, r"for\s+update")
        self.assertIn("revision", self.lower)
        self.assertNotRegex(
            self.lower,
            r"on\s+conflict\s*\(\s*user_id\s*,\s*date\s*\)\s*do\s+update",
        )
        self.assertIn("unique_violation", self.lower)

    def test_request_receipts_are_claimed_before_data_locks_and_store_no_journal_payload(self):
        claim = self.lower.index("insert into public.entry_save_v2_receipts")
        key_lock = self.lower.index("pg_advisory_xact_lock")
        self.assertLess(claim, key_lock)
        self.assertIn("digest(", self.lower)
        self.assertIn("sha256", self.lower)
        self.assertIn("request_id", self.lower)
        self.assertIn("fingerprint", self.lower)
        self.assertIn("outcome", self.lower)
        receipt_columns = re.search(
            r"create\s+table\s+if\s+not\s+exists\s+public\.entry_save_v2_receipts\s*\((.*?)\);",
            self.lower,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(receipt_columns)
        self.assertNotIn("note text", receipt_columns.group(1))
        self.assertNotIn("payload jsonb", receipt_columns.group(1))

    def test_auth_is_explicit_and_service_role_is_not_enabled(self):
        self.assertIn("security definer", self.lower)
        self.assertRegex(self.lower, r"set\s+search_path\s*=\s*''")
        self.assertIn("auth.uid()", self.lower)
        self.assertIn("auth.jwt()", self.lower)
        self.assertRegex(self.lower, r"grant\s+execute\s+on\s+function[^;]+to\s+authenticated")
        self.assertRegex(self.lower, r"revoke\s+all\s+on\s+function[^;]+from\s+service_role")
        self.assertIn("'authenticated'", self.lower)

    def test_legacy_entry_fields_and_photo_scale_intents_are_supported(self):
        columns = (
            "mood", "mood_emoji", "sleep_quality", "stress", "activities", "habits",
            "gratitude", "note", "weather", "photo_path", "phone_screen_time",
            "phone_unlocks", "phone_top_apps", "ai_reflection", "scale_values",
        )
        for column in columns:
            with self.subTest(column=column):
                self.assertIn(column, self.lower)
        self.assertIn("clear_photo", self.lower)
        self.assertIn("clear_scale_ids", self.lower)
        self.assertIn("scale_entries", self.lower)
        self.assertIn("omitted", self.lower)

    def test_conflicts_are_envelopes_and_receipted(self):
        self.assertIn("'conflict'", self.lower)
        self.assertIn("'saved'", self.lower)
        self.assertIn("'replayed'", self.lower)
        self.assertIn("'stale_revision'", self.lower)
        self.assertIn("'insert_race'", self.lower)


if __name__ == "__main__":
    unittest.main(verbosity=2)
