# Forensic Audit & Integrity Verification Report (Milestone M1)

**Auditor Agent**: `teamwork_auditor_1`  
**Role**: Forensic Integrity Auditor, Critic, Specialist  
**Working Directory**: `d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_auditor_1\`  
**Target Milestone**: Milestone 1 (Backend Scoping Hardening, Two-Way Notifications, Automated Test Suite, and Horse List Polish)  
**Profile**: General Project  
**Verdict**: **CLEAN**

---

## 1. Observation

### 1.1 Scope of Audited Files
The audit covered all files modified or added as part of Milestone M1:
- `backend/src/main/java/racehorse/backend/staff/StaffService.java`
- `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`
- `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`
- `backend/src/main/java/racehorse/backend/session/SessionService.java`
- `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`
- `frontend/src/features/horse/HorseListPage.js`

### 1.2 Static Forensic Analysis
1. **Hardcoded Test Results & Facade Implementations**:
   - Inspected `StaffService.java` (lines 119–133):
     ```java
     if ("GROOM".equals(roleName) && trainer != null) {
         staff.updateTrainer(userId, trainer.userId());
         notifications.notifyUser(trainer.userId(), new Notice(
                 "GROOM_ASSIGNED",
                 "New groom assigned to your team",
                 "Club manager assigned groom " + fullName + " to your team.",
                 null, null));
         notifications.notifyUser(userId, new Notice(
                 "TRAINER_ASSIGNED",
                 "Assigned to Head Trainer",
                 "Club manager assigned you to Head Trainer " + trainer.fullName() + ".",
                 null, null));
     } else if ("HEAD_TRAINER".equals(roleName)) {
         staff.updateMaxHorses(userId, maxHorses);
     }
     ```
     Real repository updates and genuine `NotificationService.notifyUser` dispatches are executed. No bypass conditionals.
   - Inspected `TrainingSessionService.java` (lines 470–491):
     `checkTrainerManagesHorse` strictly verifies `horse.trainerId().equals(user.userId())` for `HEAD_TRAINER`, throwing `403 FORBIDDEN` for unassigned horses or horses belonging to another trainer.
     `validateGroomAssignee` verifies `repository.isGroomOfTrainer(groomId, user.userId())`, rejecting grooms outside the trainer's team with `400 BAD REQUEST`.
   - Inspected `TrainingPlanService.java` (lines 145–160, 168–172, 216–228):
     `get` and `list` strictly enforce scoping via `horseRepository.findHorseIdsByTrainer(user.userId())`.
   - Inspected `SessionService.java` (lines 72–75, 93–98, 117–122, 179–231):
     Injected `HorseRepository` and enforces trainer scoping on `list`, `get`, and `evaluate`.
     `processOverdueReviews()` genuinely calculates the 48-hour deadline, generates preliminary metrics reports, sends two-way notifications, and marks sessions `ARCHIVED_AUTO`.
   - Inspected `HorseListPage.js` (lines 95–101):
     ```javascript
     subtitle={
       user?.role === "HORSE_OWNER"
         ? "The horses registered in your name"
         : user?.role === "HEAD_TRAINER"
         ? "The horses assigned to your management"
         : "Every horse registered at the club"
     }
     ```
     Subtitles accurately align with user roles.

2. **Test Quality and Assertion Integrity in `BusinessRulesTest.java`**:
   - Static search for trivial assertions (`assertTrue(true)`, `assertEquals(1, 1)`, etc.) returned 0 matches.
   - Every assertion checks specific error messages, HTTP status codes, field error map keys, or Mockito `verify` invocations:
     - Line 1073–1074: `assertTrue(ex.getFieldErrors().containsKey("assignedTo"));`
     - Line 1094: `assertTrue(ex.getMessage().contains("assigned to Head Trainer Other Trainer"));`
     - Line 1109–1114: `assertEquals(2, result.size()); verify(horseRepo).findByTrainer(2); verify(horseRepo, never()).findAll();`
     - Line 1128: `assertTrue(ex.getMessage().contains("not been assigned to you by the Club Manager yet"));`
     - Line 1151–1154: `assertNotNull(resp); assertEquals(501, resp.sessionId()); verify(notifService).notifyUser(eq(5), argThat(n -> "TRAINING_SESSION_ASSIGNED".equals(n.type())));`
     - Line 1191–1193: Checks exact stream matching for authorized plans and exclusion of unauthorized plans (`noneMatch(p -> p.planId() == 2)`).
     - Lines 1261–1264: Validates two-way notifications (`HORSE_ASSIGNED_TO_TRAINER` to trainer and `HORSE_TRAINER_CHANGED` to owner) with matching entity and ID.
     - Lines 1314–1316: Validates two-way notifications (`GROOM_ASSIGNED` and `TRAINER_ASSIGNED`).
     - Lines 1368, 1384: Validates requirement of both heart rate and speed before early session termination.

3. **Pre-Populated Artifact Detection**:
   - Evaluated workspace using `find_by_name` for `*.log`, `*result*`, and `*output*`.
   - Found 0 pre-populated log files, 0 pre-populated output files, and only the standard schema migration `V18__race_result_rules.sql` for `*result*`. No fabricated verification logs exist.

### 1.3 Empirical Runtime Verification

1. **Backend Test Suite Run**:
   - Command: `cd backend; .\mvnw.cmd test`
   - Verbatim Tool Output:
     ```
     [INFO] Scanning for projects...
     [INFO] 
     [INFO] -------------------------< racehorse:backend >--------------------------
     [INFO] Building  0.0.1-SNAPSHOT
     [INFO]   from pom.xml
     [INFO] --------------------------------[ jar ]---------------------------------
     [INFO] 
     [INFO] --- resources:3.5.0:resources (default-resources) @ backend ---
     [INFO] Copying 1 resource from src\main\resources to target\classes
     [INFO] Copying 37 resources from src\main\resources to target\classes
     [INFO] 
     [INFO] --- compiler:3.15.0:compile (default-compile) @ backend ---
     [INFO] Nothing to compile - all classes are up to date.
     [INFO] 
     [INFO] --- resources:3.5.0:testResources (default-testResources) @ backend ---
     [INFO] Copying 1 resource from src\test\resources to target\test-classes
     [INFO] 
     [INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ backend ---
     [INFO] Nothing to compile - all classes are up to date.
     [INFO] 
     [INFO] --- surefire:3.2.5:test (default-test) @ backend ---
     [INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
     [INFO] 
     [INFO] -------------------------------------------------------
     [INFO]  T E S T S
     [INFO] -------------------------------------------------------
     [INFO] Running racehorse.backend.BusinessRulesTest
     [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.688 s -- in racehorse.backend.BusinessRulesTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  16.268 s
     [INFO] Finished at: 2026-11-08T20:59:38+07:00
     [INFO] ------------------------------------------------------------------------
     ```
   - Exit code: `0`.

2. **Frontend Production Build**:
   - Command: `cd frontend; npm run build`
   - Verbatim Tool Output:
     ```
     > racehorse-training-management@0.1.0 build
     > react-scripts build

     Creating an optimized production build...
     Compiled successfully.

     File sizes after gzip:

       199.26 kB  build\static\js\main.308e39c1.js
       32.79 kB   build\static\css\main.4d3fbe5c.css

     The project was built assuming it is hosted at /.
     You can control this with the homepage field in your package.json.

     The build folder is ready to be deployed.
     ```
   - Exit code: `0`.
   - Generated Artifact Verified: `frontend/build/static/js/main.308e39c1.js` (749,582 bytes, timestamp: 2026-11-08 21:00).

---

## 2. Logic Chain

1. **R1 (3-Groom Limit & Assignment Enforcement)**:
   - In `StaffService.java`, both `create` (lines 95–98, 119–130) and `assignTrainer` (lines 252–265) invoke `validateTrainerForGroom(trainerId, groomId)`.
   - `validateTrainerForGroom` counts active grooms and rejects assignment if `>= MAX_GROOMS_PER_TRAINER (3)` with HTTP 409 Conflict.
   - Tested by `clubManagerRegistersGroomWithHeadTrainerSuccessfully`, `clubManagerRegistersGroomWithHeadTrainerExceedingThreeThrowsConflict`, and `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`.
   - Logic is authentic and verified.

2. **R2 (Session Review Deadline & Scoping)**:
   - In `SessionService.java`, `processOverdueReviews()` identifies sessions exceeding 48h and archives them with preliminary metrics while sending notices.
   - In `list`, `get`, and `evaluate`, `SessionService` strictly limits access for `HEAD_TRAINER` using `horseRepository.findHorseIdsByTrainer(user.userId())`.
   - Tested by `sessionServiceListForHeadTrainerReturnsOnlyManagedHorseSessions` and `sessionServiceGetRejectsSessionOfHorseNotAssignedToHeadTrainer`.
   - Logic is authentic and verified.

3. **R3 (Session Duration, Early Stop & Trainer Scoping)**:
   - In `TrainingSessionService.java`, `validateSessionTimeAndDuration` enforces `MIN_DURATION_MINUTES (15)` and `MAX_DURATION_MINUTES (720)` (12 hours) and forbids sessions spanning past midnight.
   - `finish` requires both `peakHeartRate` and `peakVelocity` for early stops.
   - `checkTrainerManagesHorse` and `validateGroomAssignee` ensure Head Trainer can only assign their own horses and grooms.
   - Tested by `trainingSessionCreateWithUnassignedHorseThrows403`, `headTrainerCannotAssignGroomFromAnotherManagementTeam`, `groomCannotEndSessionEarlyWithoutHeartRateEvenIfSpeedIsReported`, and `trainingSessionCreateWithAssignedGroomAndAssignedHorseSucceeds`.
   - Logic is authentic and verified.

4. **R4 (Two-Way In-App Notifications)**:
   - Groom assignment dispatches `GROOM_ASSIGNED` to Head Trainer and `TRAINER_ASSIGNED` to Groom (`StaffService.java`).
   - Horse assignment dispatches `HORSE_ASSIGNED_TO_TRAINER` to Head Trainer and `HORSE_TRAINER_CHANGED` to Horse Owner (`HorseService.java`).
   - Verified via Mockito argument matchers in `staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications`, `horseServiceAssignTrainerEmitsTwoWayNotifications`, and `horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications`.
   - Logic is authentic and verified.

5. **Integrity Mode Assessment**:
   - Evaluated across Development, Demo, and Benchmark integrity levels:
     - No hardcoded test results detected (PASS).
     - No facade implementations detected (PASS).
     - No fabricated verification outputs detected (PASS).
     - No core logic borrowed from external solutions (PASS).
     - Standard frameworks used only as infrastructure; core domain rules implemented genuine from scratch (PASS).
   - Verdict across all modes: **CLEAN**.

---

## 3. Adversarial Review & Challenge Summary

**Overall risk assessment**: **LOW**

### 3.1 Tested Dimensions
- **Null Safety on Horse Assignment**:
  - Scenario: A horse has no trainer assigned (`trainerId == null`). Head Trainer attempts to create a session or plan.
  - Actual Behavior: `checkTrainerManagesHorse` catches `horse.trainerId() == null` and throws HTTP 403 Forbidden with message `"This horse has not been assigned to you by the Club Manager yet"`.
  - Pass/Fail: **PASS**.
- **Cross-Trainer Groom Assignment**:
  - Scenario: Head Trainer attempts to schedule a training session assigning a Groom managed by another trainer.
  - Actual Behavior: `validateGroomAssignee` checks `repository.isGroomOfTrainer(groomId, user.userId())` and rejects with HTTP 400 Bad Request (`assignedTo`: `"This Groom is not assigned to your management team by the Club Manager"`).
  - Pass/Fail: **PASS**.
- **Early Stop Metric Tampering**:
  - Scenario: Groom reports only speed or only heart rate, then tries to stop early.
  - Actual Behavior: `finish` checks `if (s.peakHeartRate() == null || s.peakVelocity() == null)` and throws HTTP 409 Conflict.
  - Pass/Fail: **PASS**.
- **Quota Bypass on Reassignment**:
  - Scenario: Club Manager reassigns a Groom to the same Head Trainer.
  - Actual Behavior: `excludeGroomId` is passed to `countActiveGroomsOfTrainer`, correctly preventing self-counting against the quota limit of 3.
  - Pass/Fail: **PASS**.

---

## 4. Caveats

No caveats. All production changes adhere strictly to the project architecture, write ownership boundaries, and requirements of `ORIGINAL_REQUEST.md`.

---

## 5. Conclusion

**Verdict**: **CLEAN**

All work products implemented in Milestone M1 are authentic, robust, free of hardcoded shortcuts or facades, and fully verified by dynamic test execution (102 tests passed, 0 failures, 0 errors, 0 skipped) and production frontend compilation.

---

## 6. Verification Method

To independently reproduce and verify this audit:
1. **Execute Backend Tests**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\backend"
   .\mvnw.cmd test
   ```
   *Expected Result*: `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0` with `BUILD SUCCESS` (exit code 0).
2. **Execute Frontend Build**:
   ```powershell
   cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\frontend"
   npm run build
   ```
   *Expected Result*: `Compiled successfully.` (exit code 0).
3. **Invalidation Conditions**:
   - Any test failure or test count deviation from 102.
   - Any compile failure during `npm run build`.
   - Permitting an unassigned horse or cross-trainer groom to be scheduled without error.
