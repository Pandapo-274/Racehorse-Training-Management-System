## 2026-11-08T13:36:31Z
You are a teamwork_worker.
Your working directory is: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_worker_m1\
Your role is: Full-Stack Engineer & Test Implementer (Milestone M1)

Authoritative Original Request:
Read ORIGINAL_REQUEST.md at: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\ORIGINAL_REQUEST.md

Project Specification:
Read PROJECT.md at: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\orchestrator_1\PROJECT.md

Explorer Reports:
- Backend Survey: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_backend\handoff.md
- Frontend Survey: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_frontend\handoff.md
- Tests & DB Survey: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_tests\handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Write Ownership (You own these files exclusively):
- `backend/src/main/java/racehorse/backend/staff/StaffService.java`
- `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`
- `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`
- `backend/src/main/java/racehorse/backend/session/SessionService.java`
- `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`
- `frontend/src/features/horse/HorseListPage.js`

Tasks to Implement:
1. Backend Hardening:
   - In `StaffService.create(...)`: When creating a Groom with `req.trainerId() != null`, emit two-way notifications: `GROOM_ASSIGNED` to `trainer.userId()` and `TRAINER_ASSIGNED` to `userId`.
   - In `TrainingSessionService.java`: In `validateGroomAssignee` and `checkTrainerManagesHorse`, remove the redundant `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` check so scoping strictly applies to all `HEAD_TRAINER` users.
   - In `TrainingPlanService.java`: Ensure `get` and `create` enforce that a `HEAD_TRAINER` can only view and create training plans for horses assigned to them (`horse.trainerId().equals(user.userId())`).
   - In `SessionService.java`: In `list(...)`, scope returned sessions for `HEAD_TRAINER` to their managed horses (`horseRepository.findHorseIdsByTrainer(user.userId())`).
2. Frontend Polish:
   - In `HorseListPage.js`: Update subtitle for `HEAD_TRAINER` so it displays "The horses assigned to your management" instead of "Every horse registered at the club".
3. Automated Tests in `BusinessRulesTest.java`:
   - Implement comprehensive tests covering all R1-R5 scenarios:
     a. `HorseService.list(TRAINER)` returns only assigned horses via `findByTrainer`.
     b. `TrainingSessionService.create` with unassigned horse throws 403.
     c. `TrainingSessionService.create` with assigned groom and assigned horse succeeds.
     d. `TrainingSessionService.listActiveGrooms(TRAINER)` returns only assigned grooms.
     e. `TrainingPlanService.list(null, null, TRAINER)` returns only managed horse plans.
     f. `TrainingPlanService.create` rejects horse not assigned to the Head Trainer.
     g. `HorseService.assignTrainer` emits two-way notifications (`HORSE_ASSIGNED_TO_TRAINER` and `HORSE_TRAINER_CHANGED`).
     h. `HorseService.updateIdentity` with trainer update emits two-way notifications.
     i. `StaffService.create` groom with trainer emits two-way notifications.
4. Verification:
   - Run backend tests: `cd backend; .\mvnw.cmd test` (ensure 100% pass, 0 failures, 0 errors).
   - Run frontend build: `cd frontend; npm run build` (ensure clean build, exit code 0).
   - Document commands, test output, and file diffs in your handoff report.


## 2026-11-08T13:46:50Z
**Context**: Milestone M1 Progress Check
**Content**: Heartbeat check: please update your progress.md with your current progress on backend hardening, frontend polish, and BusinessRulesTest test additions.
**Action**: Report current status and update progress.md.
