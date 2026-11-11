# Integrated Android + SQL candidate (test-only)

This branch combines the Android candidate at `3d3baeb37c5eb015ae5f44b6fac9d50ac8509790` with the reviewed SQL candidate at `a8a3752d092f2bee4d152b92048c38ae0538f13a`. Their common base is `8fadf58b223cad310640a72644aa67d0112d0cab`.

## Deliberate conflict resolution

Both candidates add `database/android_move_capability_v1_2026-11-11.sql` with different contents. The integrated tree takes that file byte-for-byte from SQL candidate `a8a3752d092f2bee4d152b92048c38ae0538f13a`, not from Android candidate `3d3baeb37c5eb015ae5f44b6fac9d50ac8509790`. The selected SQL version includes the transaction-local production-approval guard required by the three-file atomic migration wrapper and is the exact version covered by the reviewed SQL test candidate. The Android candidate's version lacked that guard. The other overlapping path, `database/tests/test_android_move_capability_contract.py`, is byte-identical between the two candidates.

All other SQL candidate migration, fixture, assertion, and race-test files are retained from the exact SQL candidate. Android application sources, tests, owner-scoped recovery, OAuth behavior, signing configuration, version name/code, and `buildSrc` are unchanged from the exact Android candidate.

## CI boundary

`.github/workflows/integrated-android-sql-candidate-ci.yml` runs only on this named CI branch. Its PostgreSQL 16 service uses trust authentication only inside the disposable GitHub-hosted service and synthetic `diarium_test_*` database. The second database URL used to test the non-test-database guard points to that same disposable service's built-in `postgres` database. No repository secrets, production database, or production credentials are used. The connection-guard tests contain only synthetic fake-user/fake-password fixtures to verify redaction against loopback test URLs.

The workflow runs the reviewed SQL candidate's PostgreSQL 16 migration, rollback, compatibility, grants, and three race suites alongside the full Android unit-test suite and `buildSrc` tests. Both jobs verify that checkout `HEAD` equals the event's `GITHUB_SHA`. It does not assemble or publish an APK, create a release/tag, or deploy anything. No production migration or release is authorized by this branch.
