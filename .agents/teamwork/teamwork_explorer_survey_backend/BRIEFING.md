# BRIEFING — 2026-11-08T13:33:00Z

## Mission
Backend codebase survey for requirements R1-R5 (Groom-Head Trainer assignment & 3 groom limit, Horse list scoping for Head Trainer, Training session creation scoping for Grooms/Plans, Two-way in-app notifications).

## 🔒 My Identity
- Archetype: teamwork_explorer
- Roles: Codebase Researcher (Backend Architecture & APIs)
- Working directory: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_backend\
- Original parent: 0fd2387d-f487-4436-94ee-1139465dee4b
- Milestone: Survey & Discovery Phase

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Produce structured findings and handoff report in handoff.md
- Update progress.md regularly

## Current Parent
- Conversation ID: 0fd2387d-f487-4436-94ee-1139465dee4b
- Updated: 2026-11-08T13:32:56Z

## Investigation State
- **Explored paths**:
  - `backend/src/main/resources/migration/V37__training_flow_enhancements.sql`
  - `backend/src/main/java/racehorse/backend/staff/`: `StaffRow.java`, `StaffRepository.java`, `StaffService.java`, `StaffController.java`
  - `backend/src/main/java/racehorse/backend/horse/`: `HorseResponse.java`, `HorseIdentityRequest.java`, `HorseRepository.java`, `HorseService.java`, `HorseController.java`
  - `backend/src/main/java/racehorse/backend/training/`: `TrainingPlanService.java`, `TrainingPlanController.java`, `TrainingPlanRepository.java`, `TrainingSessionService.java`, `TrainingSessionController.java`, `TrainingSessionRepository.java`
  - `backend/src/main/java/racehorse/backend/session/`: `SessionService.java`, `SessionController.java`, `SessionRepository.java`
  - `backend/src/main/java/racehorse/backend/notification/`: `NotificationService.java`, `NotificationRepository.java`, `Notice.java`, `NotificationRow.java`, `NotificationController.java`
  - `backend/src/main/java/racehorse/backend/horserequest/`: `HorseRequestService.java`, `ReviewRequest.java`
  - `backend/src/test/java/racehorse/backend/BusinessRulesTest.java` (89 tests executed, 0 failures, missing coverage for R1-R4)
- **Key findings**:
  1. R1: Groom-Head Trainer relationship modeled via `APP_USER.trainer_id`. 3-Groom limit enforced in `StaffService.validateTrainerForGroom(...)` throwing `HttpStatus.CONFLICT` (409). Handled by `PUT /api/staff/{id}/trainer`.
  2. R2: Head Trainer horse scoping is handled in `HorseService.list(...)` via `horseRepository.findByTrainer(user.userId())`. AuthInterceptor resolves `HEAD_TRAINER` role and populates `authUser`. Note that `SessionService.list(...)` lacks trainer-based scoping.
  3. R3: `GET /api/training-sessions/assignees` returns only Grooms of trainer via `TrainingSessionRepository.findActiveGroomsByTrainer(...)`. `TrainingPlanService.list(...)` filters active plans to trainer's horses. `TrainingSessionService.create` checks `validateGroomAssignee` and `checkTrainerManagesHorse`. Redundant `horseRepository.findActiveTrainer(...).isPresent()` guard poses loophole/fragility.
  4. R4: Groom assignment two-way notifications (`GROOM_ASSIGNED` to trainer, `TRAINER_ASSIGNED` to groom) exist in `StaffService.assignTrainer` (missing in `create`). Horse assignment two-way notifications (`HORSE_ASSIGNED_TO_TRAINER` to trainer, `HORSE_TRAINER_CHANGED` to owner) exist in `HorseService.assignTrainer`, `updateIdentity`, and `HorseRequestService.approve`.
  5. R5: 0 automated tests in `BusinessRulesTest.java` cover R1-R4 new rules. Need to add tests for R1-R4 scenarios.
- **Unexplored areas**: None, survey complete.

## Key Decisions Made
- Synthesize all evidence and file line references into handoff.md according to the 5-component report standard.

## Artifact Index
- handoff.md — Final investigation report
- progress.md — Liveness and progress tracker
- DISPATCH.md — Incoming parent dispatches
