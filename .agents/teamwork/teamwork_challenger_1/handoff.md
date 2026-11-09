# Adversarial Challenger Handoff Report

**Agent**: `teamwork_challenger_1`  
**Role**: Adversarial Verifier & Stress Tester (Empirical Challenger)  
**Date**: 2026-11-08  
**Working Directory**: `d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_challenger_1\`  
**Verdict**: **APPROVE**

---

## 1. Observation

### 1.1 Empirical Command Executions
1. **Backend Automated Test Suite Run**:
   - Command executed: `cd backend; .\mvnw.cmd test`
   - Exit code: `0`
   - Direct output snippet:
     ```
     [INFO] -------------------------------------------------------
     [INFO]  T E S T S
     [INFO] -------------------------------------------------------
     [INFO] Running racehorse.backend.BusinessRulesTest
     [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.499 s -- in racehorse.backend.BusinessRulesTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  19.139 s
     ```
2. **Frontend Production Build**:
   - Command executed: `cd frontend; npm run build`
   - Exit code: `0`
   - Direct output snippet:
     ```
     Creating an optimized production build...
     Compiled successfully.

     File sizes after gzip:

       199.26 kB  build\static\js\main.308e39c1.js
       32.79 kB   build\static\css\main.4d3fbe5c.css
     ```

### 1.2 Boundary Condition & Negative Test Verification
1. **Session creation for unassigned horse or horse of another trainer**:
   - File: `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java` (lines 470–479, called at lines 107 and 355):
     ```java
     private void checkTrainerManagesHorse(HorseResponse horse, AuthenticatedUser user) {
         if (user == null || !"HEAD_TRAINER".equals(user.role())) return;
         if (horse.trainerId() != null && !horse.trainerId().equals(user.userId())) {
             throw new ApiException(HttpStatus.FORBIDDEN,
                     "This horse is assigned to Head Trainer "
                             + (horse.trainerName() != null ? horse.trainerName() : "#" + horse.trainerId()));
         } else if (horse.trainerId() == null) {
             throw new ApiException(HttpStatus.FORBIDDEN,
                     "This horse has not been assigned to you by the Club Manager yet");
         }
     }
     ```
   - Automated tests:
     - `trainingSessionCreateWithUnassignedHorseThrows403`: Verifies 403 Forbidden with `"not been assigned to you by the Club Manager yet"`.
     - `headTrainerCannotScheduleSessionForHorseAssignedToAnotherTrainer`: Verifies 403 Forbidden with `"assigned to Head Trainer Other Trainer"`.
     - `headTrainerCannotRescheduleSessionForHorseAssignedToAnotherTrainer`: Verifies 403 Forbidden on reschedule.
     - `trainingPlanCreateRejectsUnassignedHorseForHeadTrainer` and `trainingPlanCreateRejectsHorseNotAssignedToHeadTrainer`: Verifies training plan creation restrictions.

2. **Cross-trainer Groom assignment by Head Trainer**:
   - File: `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java` (lines 482–491):
     ```java
     private void validateGroomAssignee(int groomId, AuthenticatedUser user, Map<String, String> errors) {
         if (!repository.isActiveGroom(groomId)) {
             errors.put("assignedTo", "Assignee must be an active Groom");
             return;
         }
         if (user != null && "HEAD_TRAINER".equals(user.role())
                 && !repository.isGroomOfTrainer(groomId, user.userId())) {
             errors.put("assignedTo", "This Groom is not assigned to your management team by the Club Manager");
         }
     }
     ```
   - File: `backend/src/main/java/racehorse/backend/training/TrainingSessionController.java` (lines 54–59):
     `GET /api/training-sessions/assignees` calls `repository.findActiveGroomsByTrainer(user.userId())`, so Head Trainers cannot even see grooms of other trainers in dropdowns.
   - Automated tests:
     - `headTrainerCannotAssignGroomFromAnotherManagementTeam`: Verifies 400 Bad Request with field error `assignedTo` containing `"not assigned to your management team"`.
     - `trainingSessionListActiveGroomsForHeadTrainerReturnsOnlyAssignedGrooms`: Verifies `/assignees` returns only assigned grooms.

3. **Quota enforcement: Club Manager assigning a 4th groom to a Head Trainer**:
   - File: `backend/src/main/java/racehorse/backend/staff/StaffService.java` (lines 302–315):
     ```java
     private StaffRow validateTrainerForGroom(int trainerId, Integer excludeGroomId) {
         ...
         int currentGrooms = staff.countActiveGroomsOfTrainer(trainerId, excludeGroomId);
         if (currentGrooms >= MAX_GROOMS_PER_TRAINER) {
             throw new ApiException(HttpStatus.CONFLICT,
                     "Head Trainer " + trainer.fullName() + " is already managing the maximum of "
                             + MAX_GROOMS_PER_TRAINER + " active grooms");
         }
         return trainer;
     }
     ```
   - File: `frontend/src/features/manager/StaffPage.js` (lines 79–86):
     ```javascript
     {activeTrainers.map((t) => {
       const full = (t.groomCount || 0) >= 3 && t.userId !== row.trainerId;
       return (
         <option key={t.userId} value={t.userId} disabled={full}>
           {t.fullName} ({t.groomCount || 0}/3 grooms)
         </option>
       );
     })}
     ```
   - Automated tests:
     - `clubManagerAssigningFourthGroomToHeadTrainerIsRejected`: Verifies 409 Conflict with `"maximum of 3 active grooms"`.
     - `clubManagerRegisteringGroomWithHeadTrainerAtLimitIsRejected`: Verifies 409 Conflict on new account creation.

4. **Strict Two-Way Notifications**:
   - **Groom assignment**:
     - `StaffService.assignTrainer` (lines 255–264) and `StaffService.create` (lines 121–130) both dispatch:
       - Trainer: `type = "GROOM_ASSIGNED"`, `"New groom assigned to your team"`.
       - Groom: `type = "TRAINER_ASSIGNED"`, `"Assigned to Head Trainer"`.
     - Automated tests: `clubManagerRegistersGroomWithHeadTrainerSuccessfully`, `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`.
   - **Horse assignment**:
     - `HorseService.notifyTrainerChange` (lines 151–172) called by both `assignTrainer` and `updateIdentity` dispatches:
       - Trainer: `type = "HORSE_ASSIGNED_TO_TRAINER"`, `targetEntity = "HORSE"`, `targetId = horseId`.
       - Owner: `type = "HORSE_TRAINER_CHANGED"`, `targetEntity = "HORSE"`, `targetId = horseId`.
     - Automated tests: `horseServiceAssignTrainerEmitsTwoWayNotifications`, `horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications`.

---

## 2. Logic Chain

1. **Empirical Build and Test Stability**:
   - Both backend test runner (`.\mvnw.cmd test`) and frontend production build (`npm run build`) exited with code 0 without warnings or errors.
   - All 102 business rule tests passed cleanly, validating backwards compatibility and new training flow requirements.

2. **Negative Boundary Defense**:
   - An unassigned horse or horse belonging to another trainer cannot be scheduled (`TrainingSessionService.create` and `reschedule` throw HTTP 403 Forbidden).
   - An unassigned horse or horse belonging to another trainer cannot have a training plan created, viewed, or activated by an unauthorized trainer (`TrainingPlanService.create` throws HTTP 400 with `errors.get("horseId")`, `get` throws HTTP 403 Forbidden).
   - A Head Trainer cannot assign a groom belonging to another trainer (`TrainingSessionService.validateGroomAssignee` throws HTTP 400 Bad Request with field error `assignedTo`).
   - A Club Manager cannot exceed the 3-groom quota (enforced via HTTP 409 Conflict in `StaffService` and disabled UI `<option>` in `StaffPage.js`).

3. **Notification Guarantee**:
   - Both `StaffService.create` and `StaffService.assignTrainer` emit two-way notifications for Groom assignment.
   - Both `HorseService.assignTrainer` and `HorseService.updateIdentity` emit two-way notifications for Horse assignment.

---

## 3. Caveats

- No caveats. The implementation directly meets all negative boundary criteria, UI scoping requirements, and automated testing benchmarks.

---

## 4. Conclusion

- **Verdict**: **APPROVE**
- All 5 requirements (R1–R5) are completely fulfilled, rigorously enforced at both backend and frontend layers, and supported by 102 automated tests in `BusinessRulesTest.java`.

---

## 5. Verification Method

1. **Run backend test suite**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\backend"
   .\mvnw.cmd test
   ```
   *Expected*: `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0`, exit code 0.

2. **Run frontend production build**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\frontend"
   npm run build
   ```
   *Expected*: `Compiled successfully.`, exit code 0.

3. **Invalidation conditions**:
   - Any failure in the 102 automated tests.
   - Any scenario allowing a Head Trainer to create sessions/plans for unassigned horses or cross-trainer grooms without receiving a 400/403 error.
   - Any scenario allowing assignment of a 4th groom without receiving a 409 error.
