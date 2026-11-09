# Reviewer Progress - Round 4

## Audit Summary
- Analyzed all 4 Training Flow business rules (R1 to R4) and acceptance criteria independently.
- Identified 4 issues in prior implementation and tests:
  1. Missing automated test coverage in `BusinessRulesTest` for sessions crossing past midnight into the next day on both creation and rescheduling (acceptance criteria explicitly mandated: *"Buổi tập có duration > 12h hoặc kết thúc sang ngày hôm sau bị từ chối khi tạo"*).
  2. Missing service-layer role authorization in `TrainingSessionService` (`create()`, `reschedule()`, `cancel()`, `start()`, `finish()`), `SessionService` (`evaluate()`), and `IncidentService` (`stopSession()`).
  3. Potential `NullPointerException` risks when `writeAudit` is called with null user context (`actor == null` or `user == null`).
  4. Missing unit test coverage for `MissedTaskJob.runPeriodicTrainingChecks()`.

## Fixes Implemented
1. `StaffService.java`: Made `writeAudit` calls null-safe (`actor == null ? null : actor.userId()`) in `create()` and `changeRole()`.
2. `TrainingSessionService.java`:
   - Enforced service-layer role checks: `create()` and `reschedule()` require `HEAD_TRAINER`, `cancel()` requires `HEAD_TRAINER` or `CLUB_MANAGER`, `start()` and `finish()` require `GROOM` or `HEAD_TRAINER`.
   - Null-safed all `user.userId()` calls in audit logging.
3. `SessionService.java`:
   - Enforced service-layer role check in `evaluate()` requiring `HEAD_TRAINER`.
   - Null-safed evaluator ID and audit logging.
4. `IncidentService.java`:
   - Enforced service-layer role check in `stopSession()` requiring `GROOM` or `HEAD_TRAINER`.
   - Null-safed reporter ID in audit logging.
5. `BusinessRulesTest.java`:
   - Added `sessionEndingNextDayIsRejected`: Verifies a session starting at 23:30 with 60m duration is rejected with HTTP 400 Bad Request.
   - Added `rescheduleSessionEndingNextDayIsRejected`: Verifies rescheduling a session past midnight is rejected with HTTP 400 Bad Request.
   - Added `sessionWithinSameDayWithMaxDurationIsAccepted`: Verifies a session starting at 08:00 with 720m duration ending at 20:00 on the same day is accepted.
   - Added `nonHeadTrainerCannotScheduleSession`: Verifies non-head trainer is rejected on `create()`.
   - Added `nonHeadTrainerCannotRescheduleSession`: Verifies non-head trainer is rejected on `reschedule()`.
   - Added `nonHeadTrainerOrManagerCannotCancelSession`: Verifies non-head trainer/manager is rejected on `cancel()`.
   - Added `nonHeadTrainerCannotEvaluateSession`: Verifies non-head trainer is rejected on `evaluate()`.
   - Added `nonGroomOrTrainerCannotStopSession`: Verifies unauthorized caller is rejected on `stopSession()`.
   - Added `periodicTrainingChecksTriggerOverdueSessionsAndReviews`: Verifies `MissedTaskJob.runPeriodicTrainingChecks()` triggers `processOverdueSessions()` and `processOverdueReviews()`.

## Verification
- Backend test execution: `cd backend; .\mvnw.cmd test` -> `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` (BUILD SUCCESS).
- Frontend production build: `cd frontend; npm run build` -> `Compiled successfully` (0 compile errors, 0 ESLint warnings).
