# Milestone M1 Implementation & Verification Handoff Report

**Agent**: `teamwork_worker_m1`  
**Role**: Full-Stack Engineer & Test Implementer (Milestone M1)  
**Date**: 2026-11-08  
**Working Directory**: `d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_worker_m1\`

---

## 1. Observation

### 1.1 Backend Implementation Hardening
1. **`StaffService.java` (`backend/src/main/java/racehorse/backend/staff/StaffService.java`)**:
   - In `create(...)` (lines 119–133), when creating a groom with `trainer != null`, previously only `staff.updateTrainer(userId, trainer.userId())` was executed without notifications.
   - Emits two-way notifications upon Groom account creation with an assigned Head Trainer:
     - `notifications.notifyUser(trainer.userId(), new Notice("GROOM_ASSIGNED", "New groom assigned to your team", "Club manager assigned groom " + fullName + " to your team.", null, null))`
     - `notifications.notifyUser(userId, new Notice("TRAINER_ASSIGNED", "Assigned to Head Trainer", "Club manager assigned you to Head Trainer " + trainer.fullName() + ".", null, null))`
2. **`TrainingSessionService.java` (`backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`)**:
   - In `checkTrainerManagesHorse` (lines 470–479): Removed redundant `&& horseRepository.findActiveTrainer(user.userId()).isPresent()`. If `horse.trainerId() == null`, throws `ApiException(HttpStatus.FORBIDDEN, "This horse has not been assigned to you by the Club Manager yet")` for all `HEAD_TRAINER` users.
   - In `validateGroomAssignee` (lines 482–491): Removed redundant `&& horseRepository.findActiveTrainer(user.userId()).isPresent()`. If `!repository.isGroomOfTrainer(groomId, user.userId())`, places error `"assignedTo": "This Groom is not assigned to your management team by the Club Manager"`.
3. **`TrainingPlanService.java` (`backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`)**:
   - In `get(...)` (lines 145–160): Added `HEAD_TRAINER` scoping check verifying `horse.trainerId() != null && horse.trainerId().equals(user.userId())`, throwing `ApiException(HttpStatus.FORBIDDEN, "This plan belongs to a horse not assigned to you")` if unauthorized.
   - In `checkTrainerManagesHorse` (lines 215–228): Removed redundant `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` check so unassigned horses are rejected unconditionally for `HEAD_TRAINER`.
4. **`SessionService.java` (`backend/src/main/java/racehorse/backend/session/SessionService.java`)**:
   - Injected `HorseRepository` via constructor and Spring `@Autowired`.
   - In `list(...)` (lines 62–73): Added `HEAD_TRAINER` filter using `horseRepository.findHorseIdsByTrainer(user.userId())` so only sessions for horses managed by that trainer are returned.
   - In `get(...)` and `evaluate(...)` (lines 94–120): Protected against viewing or evaluating sessions belonging to horses of other trainers.

### 1.2 Frontend Polish
1. **`HorseListPage.js` (`frontend/src/features/horse/HorseListPage.js`)**:
   - Lines 95–101: Updated `subtitle` expression so that for `user?.role === "HEAD_TRAINER"`, it displays `"The horses assigned to your management"` instead of the generic `"Every horse registered at the club"`.

### 1.3 Test Suite Execution in `BusinessRulesTest.java`
- Added 13 new test scenarios covering all R1–R5 specifications:
  - `horseServiceListForHeadTrainerReturnsOnlyAssignedHorsesViaFindByTrainer`
  - `trainingSessionCreateWithUnassignedHorseThrows403`
  - `trainingSessionCreateWithAssignedGroomAndAssignedHorseSucceeds`
  - `trainingSessionListActiveGroomsForHeadTrainerReturnsOnlyAssignedGrooms`
  - `trainingPlanListForHeadTrainerReturnsOnlyManagedHorsePlans`
  - `trainingPlanCreateRejectsHorseNotAssignedToHeadTrainer`
  - `trainingPlanCreateRejectsUnassignedHorseForHeadTrainer`
  - `trainingPlanGetRejectsHorseNotAssignedToHeadTrainer`
  - `horseServiceAssignTrainerEmitsTwoWayNotifications`
  - `horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications`
  - `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`
  - `sessionServiceListForHeadTrainerReturnsOnlyManagedHorseSessions`
  - `sessionServiceGetRejectsSessionOfHorseNotAssignedToHeadTrainer`
  - Plus updated `readyToSchedule(...)` and `clubManagerRegistersGroomWithHeadTrainerSuccessfully` to assert two-way notifications.

- Backend test run output (`cd backend; .\mvnw.cmd test`):
  ```
  [INFO] Running racehorse.backend.BusinessRulesTest
  [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.411 s -- in racehorse.backend.BusinessRulesTest
  [INFO] 
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  19.091 s
  ```
- Frontend build output (`cd frontend; npm run build`):
  ```
  Creating an optimized production build...
  Compiled successfully.

  File sizes after gzip:

    199.26 kB (+27 B)  build\static\js\main.308e39c1.js
    32.79 kB           build\static\css\main.4d3fbe5c.css
  ```

---

## 2. Logic Chain

1. **R1: Staff Management & Groom Assignment**:
   - `StaffService.create` previously did not emit two-way notifications when a groom was registered with a `trainerId`. Now it dispatches `GROOM_ASSIGNED` to the Head Trainer and `TRAINER_ASSIGNED` to the Groom, aligning with `StaffService.assignTrainer`.
   - Verified via `clubManagerRegistersGroomWithHeadTrainerSuccessfully` and `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`.

2. **R2: Horse Listing Scoping for Head Trainer**:
   - `HorseService.list(user)` routes `HEAD_TRAINER` to `horseRepository.findByTrainer(user.userId())`.
   - Verified via `horseServiceListForHeadTrainerReturnsOnlyAssignedHorsesViaFindByTrainer`.
   - `HorseListPage.js` UI now displays "The horses assigned to your management" as subtitle for `HEAD_TRAINER`.
   - `SessionService.list` now filters session lists using `horseRepository.findHorseIdsByTrainer(user.userId())`. Verified via `sessionServiceListForHeadTrainerReturnsOnlyManagedHorseSessions`.

3. **R3: Session & Plan Scoping for Head Trainer**:
   - Removing `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` ensures that scoping is never bypassed when active trainer lookup is empty or unmocked.
   - `TrainingSessionService.create` strictly checks horse ownership (403 if unassigned or belonging to another trainer) and groom assignment (400 if groom is not in trainer's team). Verified via `trainingSessionCreateWithUnassignedHorseThrows403` and `trainingSessionCreateWithAssignedGroomAndAssignedHorseSucceeds`.
   - `TrainingSessionService.listActiveGrooms` returns only active grooms managed by that trainer. Verified via `trainingSessionListActiveGroomsForHeadTrainerReturnsOnlyAssignedGrooms`.
   - `TrainingPlanService.list` and `get` restrict plan summaries and detailed plans to horses managed by the trainer. Verified via `trainingPlanListForHeadTrainerReturnsOnlyManagedHorsePlans`, `trainingPlanCreateRejectsHorseNotAssignedToHeadTrainer`, `trainingPlanCreateRejectsUnassignedHorseForHeadTrainer`, and `trainingPlanGetRejectsHorseNotAssignedToHeadTrainer`.

4. **R4: Two-Way In-App Notifications**:
   - Groom assignment emits `GROOM_ASSIGNED` and `TRAINER_ASSIGNED` in both `assignTrainer` and `create`.
   - Horse assignment emits `HORSE_ASSIGNED_TO_TRAINER` (to Trainer) and `HORSE_TRAINER_CHANGED` (to Owner).
   - Verified via `horseServiceAssignTrainerEmitsTwoWayNotifications`, `horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications`, and `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`.

5. **R5: Automated Verification**:
   - Test suite expanded from 89 tests to 102 tests.
   - Zero test failures, zero test errors, zero skipped tests.
   - Clean compilation of both frontend and backend.

---

## 3. Caveats

- No caveats. All changes strictly adhere to write ownership boundaries and minimal change principles.

---

## 4. Conclusion

Milestone M1 objectives are 100% complete:
1. Backend scoping and notification hardening implemented in `StaffService`, `TrainingSessionService`, `TrainingPlanService`, and `SessionService`.
2. Frontend subtitle polished in `HorseListPage.js`.
3. Comprehensive automated test suite implemented in `BusinessRulesTest.java` (102 tests, all passing).
4. Full build verification executed with exit code 0 on both backend (`mvnw test`) and frontend (`npm run build`).

---

## 5. Verification Method

To independently verify the implementation:
1. **Backend Test Suite Execution**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\backend"
   .\mvnw.cmd test
   ```
   *Expected Result*: `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0` with `BUILD SUCCESS` (exit code 0).
2. **Frontend Production Build**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\frontend"
   npm run build
   ```
   *Expected Result*: `Compiled successfully.` (exit code 0).
3. **Invalidation Conditions**:
   - If `.\mvnw.cmd test` yields fewer than 102 tests or any failures/errors.
   - If `npm run build` fails with syntax or compiler errors.
   - If unassigned horses can be scheduled or planned by a Head Trainer without being rejected.
