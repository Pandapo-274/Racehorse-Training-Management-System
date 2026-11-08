# Reviewer Handoff - Round 4

## Summary of Changes
- Hardened role authorization across service boundaries: `TrainingSessionService`, `SessionService`, and `IncidentService` now strictly validate caller roles (`HEAD_TRAINER`, `GROOM`, `CLUB_MANAGER`).
- Prevented potential `NullPointerException` crashes by ensuring all `user.userId()` / `actor.userId()` accesses in `writeAudit` are null-safe.
- Added 9 new automated unit/integration tests to `BusinessRulesTest.java` (expanding suite from 80 to 89 tests), verifying:
  1. Rejection of sessions crossing midnight into the next day during creation.
  2. Rejection of sessions crossing midnight into the next day during rescheduling.
  3. Acceptance of 12-hour max duration sessions when finishing on the same day.
  4. Role rejection for non-Head Trainer attempting to create a training session.
  5. Role rejection for non-Head Trainer attempting to reschedule a training session.
  6. Role rejection for non-Head Trainer/Club Manager attempting to cancel a training session.
  7. Role rejection for non-Head Trainer attempting to evaluate a training session.
  8. Role rejection for unauthorized caller attempting to stop a training session.
  9. Scheduled execution of `MissedTaskJob.runPeriodicTrainingChecks()` triggering both overdue session completion and review archiving.

## Verification
- `cd backend; .\mvnw.cmd test` -> Tests run: 89, Failures: 0, Errors: 0, Skipped: 0.
- `cd frontend; npm run build` -> Compiled successfully (0 compile errors, 0 ESLint warnings).

## Open Issues / Risk Ledger
- `Minor Robustness Risk`: Database-level transaction isolation during high concurrent scheduling relies on database locking to prevent race conditions between validation and insertion.
- `Minor Robustness Risk`: Flyway migration `V37` must be applied to the target Microsoft SQL Server instance in deployment.
