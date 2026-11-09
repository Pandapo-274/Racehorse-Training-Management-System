# Review & Adversarial Challenge Report — Milestone M1

**Reviewer**: `teamwork_reviewer_2`  
**Role**: Code Reviewer & Verification Specialist (Reviewer 2 / Adversarial Critic)  
**Date**: 2026-11-08  
**Working Directory**: `d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_reviewer_2\`  
**Target Verdict**: **APPROVE**  

---

## 1. Observation

### 1.1 Direct Inspection of Implementation Artifacts

1. **`StaffService.java` (`backend/src/main/java/racehorse/backend/staff/StaffService.java`)**:
   - Lines 42–43: `public static final int MAX_GROOMS_PER_TRAINER = 3;`
   - Lines 302–315: Method `validateTrainerForGroom(int trainerId, Integer excludeGroomId)` validates:
     - Target user exists, has role `"HEAD_TRAINER"`, and status `"ACTIVE"`.
     - `int currentGrooms = staff.countActiveGroomsOfTrainer(trainerId, excludeGroomId);`
     - If `currentGrooms >= MAX_GROOMS_PER_TRAINER`, throws `ApiException(HttpStatus.CONFLICT, "Head Trainer " + trainer.fullName() + " is already managing the maximum of " + MAX_GROOMS_PER_TRAINER + " active grooms")`.
   - Lines 119–130 (`create`): Emits two-way notifications upon Groom account creation with an assigned Head Trainer:
     - To Trainer: `new Notice("GROOM_ASSIGNED", "New groom assigned to your team", "Club manager assigned groom " + fullName + " to your team.", null, null)`
     - To Groom: `new Notice("TRAINER_ASSIGNED", "Assigned to Head Trainer", "Club manager assigned you to Head Trainer " + trainer.fullName() + ".", null, null)`
   - Lines 252–267 (`assignTrainer`): Emits identical two-way notifications when Club Manager assigns or reassigns an existing Groom to a Head Trainer.
   - Lines 166–168 (`setStatus`): Validates trainer capacity when reactivating a suspended Groom (`"ACTIVE".equals(status) && "GROOM".equals(target.roleName()) && target.trainerId() != null`).
   - Line 71, 149, 188, 243, 280: Enforces `if (actor != null && !"CLUB_MANAGER".equals(actor.role())) throw new ApiException(HttpStatus.FORBIDDEN, ...)` across all administrative mutations.

2. **`TrainingSessionService.java` (`backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`)**:
   - Lines 84–89 (`listActiveGrooms`): Restricts groom listing for `HEAD_TRAINER` to `repository.findActiveGroomsByTrainer(user.userId())`.
   - Lines 470–480 (`checkTrainerManagesHorse`):
     - If `horse.trainerId() != null && !horse.trainerId().equals(user.userId())`: throws 403 `FORBIDDEN` `"This horse is assigned to Head Trainer ..."`.
     - If `horse.trainerId() == null`: throws 403 `FORBIDDEN` `"This horse has not been assigned to you by the Club Manager yet"`.
   - Lines 482–491 (`validateGroomAssignee`):
     - If `!repository.isGroomOfTrainer(groomId, user.userId())`: adds field error `"assignedTo": "This Groom is not assigned to your management team by the Club Manager"`.
   - Lines 313–318 (`list`): Filters session responses for `HEAD_TRAINER` using `horseRepository.findHorseIdsByTrainer(user.userId())`.
   - Lines 355 & 362 (`reschedule`): Enforces `checkTrainerManagesHorse` and `validateGroomAssignee`.

3. **`TrainingPlanService.java` (`backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`)**:
   - Lines 216–228 (`checkTrainerManagesHorse`): Rejects unassigned horses or horses assigned to another trainer, adding field error `"horseId"` on `create` (causing 400 `BAD_REQUEST`) or throwing 403 `FORBIDDEN` on `changeStatus`.
   - Lines 153–159 (`get`): Rejects `HEAD_TRAINER` access if `horse.trainerId() == null || !horse.trainerId().equals(user.userId())` with 403 `FORBIDDEN`.
   - Lines 168–172 (`list`): Filters summaries for `HEAD_TRAINER` using `horseRepository.findHorseIdsByTrainer(user.userId())`.

4. **`SessionService.java` (`backend/src/main/java/racehorse/backend/session/SessionService.java`)**:
   - Lines 47–57: Injected `HorseRepository` via Spring `@Autowired`.
   - Lines 72–75 (`list`): Filters sessions for `HEAD_TRAINER` using `horseRepository.findHorseIdsByTrainer(user.userId())`.
   - Lines 93–98 (`get`): Rejects access for `HEAD_TRAINER` if session horse is not managed by them (403 `FORBIDDEN`).
   - Lines 117–122 (`evaluate`): Rejects evaluation for `HEAD_TRAINER` if session horse is not managed by them (403 `FORBIDDEN`).
   - Lines 179–230 (`processOverdueReviews`): Dispatches automated preliminary report with Groom metrics to owner and marks review `ARCHIVED_AUTO` after 48h deadline.

5. **`HorseService.java` (`backend/src/main/java/racehorse/backend/horse/HorseService.java`)**:
   - Lines 256–260 (`list`): Returns `horseRepository.findByTrainer(user.userId())` when `user.role()` is `"HEAD_TRAINER"`.
   - Lines 144–173 (`notifyTrainerChange`): Sends `HORSE_ASSIGNED_TO_TRAINER` to new trainer and `HORSE_TRAINER_CHANGED` to owner on horse assignment in both `updateIdentity` and `assignTrainer`.

6. **`HorseListPage.js` (`frontend/src/features/horse/HorseListPage.js`)**:
   - Lines 95–101: Correctly displays `"The horses assigned to your management"` as subtitle for `HEAD_TRAINER`.

### 1.2 Independent Test & Build Execution Outputs

- **Backend Test Run** (`cd backend; .\mvnw.cmd test`):
  ```
  [INFO] Running racehorse.backend.BusinessRulesTest
  [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.673 s -- in racehorse.backend.BusinessRulesTest
  [INFO] 
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  23.162 s
  ```
  - Exact command: `cd backend; .\mvnw.cmd test`
  - Exit code: 0
  - Total test count: 102 (13 newly added tests for R1–R4 scoping and notifications; all passing).

- **Frontend Production Build** (`cd frontend; npm run build`):
  ```
  Creating an optimized production build...
  Compiled successfully.
  ```
  - Exact command: `cd frontend; npm run build`
  - Exit code: 0
  - Zero syntax, lint, or bundling errors.

---

## 2. Logic Chain

1. **R1: Groom Management Limit & Club Manager Assignment**:
   - In `StaffService`, `validateTrainerForGroom` strictly queries `staff.countActiveGroomsOfTrainer(trainerId, excludeGroomId)`. If count >= 3, it rejects with HTTP 409 Conflict.
   - Tested in `clubManagerAssigningFourthGroomToHeadTrainerIsRejected` and `clubManagerRegisteringGroomWithHeadTrainerAtLimitIsRejected`. Both assert the 409 conflict and verify no database mutations occur.
   - Self-assignment is blocked: only `CLUB_MANAGER` can invoke assignment methods (verified via `headTrainerCannotSelfAssignGrooms`).

2. **R2: Horse List Scoping for Head Trainer**:
   - In `HorseService.list`, `HEAD_TRAINER` role unconditionally invokes `horseRepository.findByTrainer(user.userId())` instead of returning the entire registry.
   - Tested in `horseServiceListForHeadTrainerReturnsOnlyAssignedHorsesViaFindByTrainer`.
   - In `SessionService.list`, sessions are strictly filtered against `findHorseIdsByTrainer(user.userId())` (tested in `sessionServiceListForHeadTrainerReturnsOnlyManagedHorseSessions`).

3. **R3: Groom & Training Plan Scoping for Session Creation**:
   - `TrainingSessionService.listActiveGrooms` returns only grooms assigned to the trainer via `findActiveGroomsByTrainer` (tested in `trainingSessionListActiveGroomsForHeadTrainerReturnsOnlyAssignedGrooms`).
   - `TrainingSessionService.create` validates both horse assignment (403 if unassigned or belonging to another trainer) and groom assignment (400 if groom is not in trainer's team). Tested in `trainingSessionCreateWithUnassignedHorseThrows403`, `headTrainerCannotScheduleSessionForHorseAssignedToAnotherTrainer`, and `headTrainerCannotAssignGroomFromAnotherManagementTeam`.
   - `TrainingPlanService.list` and `TrainingPlanService.get` restrict summaries and detail views to managed horses (tested in `trainingPlanListForHeadTrainerReturnsOnlyManagedHorsePlans`, `trainingPlanCreateRejectsHorseNotAssignedToHeadTrainer`, `trainingPlanCreateRejectsUnassignedHorseForHeadTrainer`, and `trainingPlanGetRejectsHorseNotAssignedToHeadTrainer`).

4. **R4: Two-Way In-App Notifications**:
   - Groom assignment: Emits `GROOM_ASSIGNED` to Head Trainer and `TRAINER_ASSIGNED` to Groom (tested in `clubManagerAssignsGroomToHeadTrainerSuccessfully`, `clubManagerRegistersGroomWithHeadTrainerSuccessfully`, and `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`).
   - Horse assignment: Emits `HORSE_ASSIGNED_TO_TRAINER` to Head Trainer and `HORSE_TRAINER_CHANGED` to Horse Owner (tested in `horseServiceAssignTrainerEmitsTwoWayNotifications` and `horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications`).

5. **R5: Verification & Integrity Check**:
   - Source code examination reveals NO hardcoded return values, NO facade patterns, NO test skips.
   - All tests execute actual service logic with mocked repository boundaries, asserting on error status codes, error field keys, error messages, and Mockito `verify` invocations.
   - Both backend and frontend compile with 0 errors.

---

## 3. Adversarial Review & Stress-Testing

| # | Attack Scenario / Hypothesis | Blast Radius | Mitigation / Code Defense | Result |
|---|-----------------------------|--------------|---------------------------|--------|
| **C1** | Head Trainer attempts to schedule a session using an unassigned horse (`trainerId == null`). | Unauthorized session creation on club horses. | `TrainingSessionService.checkTrainerManagesHorse` unconditionally throws 403 `FORBIDDEN` if `horse.trainerId() == null`. | PASS |
| **C2** | Head Trainer attempts to assign a Groom managed by another Head Trainer. | Groom cross-assignment without manager approval. | `TrainingSessionService.validateGroomAssignee` checks `repository.isGroomOfTrainer(groomId, user.userId())` and returns field error 400. | PASS |
| **C3** | Club Manager reactivates a suspended groom whose assigned Head Trainer has since reached the 3-groom capacity. | Breach of 3-groom ceiling. | `StaffService.setStatus` checks `"ACTIVE".equals(status) && "GROOM".equals(target.roleName()) && target.trainerId() != null` and invokes `validateTrainerForGroom(target.trainerId(), target.userId())`, throwing 409 Conflict if at limit. | PASS |
| **C4** | Head Trainer attempts to inspect or evaluate a session belonging to another trainer's horse via direct ID URL. | IDOR / Information disclosure across trainers. | `SessionService.get` and `evaluate` check `findHorseIdsByTrainer(user.userId()).contains(s.horseId())` and throw 403 `FORBIDDEN`. | PASS |
| **C5** | Session duration is specified to span past midnight (e.g. 23:45 with 30 min duration). | Inconsistent session date calculation and multi-day schedule drift. | `validateSessionTimeAndDuration` checks `!endTime.toLocalDate().equals(day)` and throws 400 Bad Request with `"Session duration cannot cross into the next day"`. | PASS |
| **C6** | Groom attempts early termination without having recorded heart rate or speed. | Premature termination without required physiological metrics. | `TrainingSessionService.finish` validates `s.peakHeartRate() == null || s.peakVelocity() == null` and throws 409 Conflict. | PASS |

### Integrity Attestation:
- No hardcoded test responses or facade methods were found.
- No shortcuts or workarounds bypassing business logic were introduced.
- Build and test commands were independently executed in clean PowerShell sessions.

---

## 4. Caveats

- **No caveats**. All business rules specified in `ORIGINAL_REQUEST.md` (R1 through R5) and `PROJECT.md` have been fully implemented, reviewed, and independently tested.

---

## 5. Conclusion

**Verdict: APPROVE**

The implementation in Milestone M1 is clean, robust, and completely adheres to the architectural design and security requirements:
1. Strict 3-groom limit per Head Trainer is enforced across creation, assignment, and status reactivation.
2. Head Trainer horse and session lists are scoped exclusively to assigned horses.
3. Session and plan creation strictly prevent unauthorized horse and groom usage.
4. Two-way notifications are dispatched for all groom and horse assignment events.
5. Automated test suite has 102 passing tests, and frontend build passes cleanly.

---

## 6. Verification Method

To independently reproduce this verification:

1. **Execute Backend Test Suite**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\backend"
   .\mvnw.cmd test
   ```
   *Expected Output*: `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0` with `BUILD SUCCESS`.

2. **Execute Frontend Production Build**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\frontend"
   npm run build
   ```
   *Expected Output*: `Compiled successfully.` (exit code 0).
