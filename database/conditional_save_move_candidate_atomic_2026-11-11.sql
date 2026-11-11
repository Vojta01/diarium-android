-- psql-only atomic entry point. Run through apply_conditional_save_move_candidate.sh.
-- Individual migration files are fragments, not independently atomic entry points.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL idle_in_transaction_session_timeout = '30s';

CREATE TEMP TABLE diarium_production_migration_approval (
  expected_database text NOT NULL,
  verified_backup_reference text NOT NULL,
  approved_change_window text NOT NULL,
  confirmation text NOT NULL
) ON COMMIT DROP;
INSERT INTO pg_temp.diarium_production_migration_approval VALUES (
  :'expected_database', :'verified_backup_reference',
  :'approved_change_window', :'confirmation'
);

\ir save_daily_entry_v2_2026-11-10.sql
\ir entry_date_move_v1_2026-11-10.sql
\ir android_move_capability_v1_2026-11-11.sql

COMMIT;
\echo 'Atomic conditional-save/date-move migration committed.'
