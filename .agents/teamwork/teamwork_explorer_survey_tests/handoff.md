# Survey Report: Test Suite & Database Architecture (Requirements R1 - R5)

## 1. Observation

### 1.1 Database Schema & Migrations (`backend/src/main/resources/migration/`)
- **`V1__initial_schema.sql`**:
  - Line 47-59: Table `APP_USER` (`user_id INT IDENTITY(1,1) PRIMARY KEY`, `role_id INT NOT NULL`, `full_name`, `email`, `status`).
  - Line 35-41: Table `ROLE` (`role_name IN ('HEAD_TRAINER','VETERINARIAN','GROOM','HORSE_OWNER','CLUB_MANAGER')`).
  - Line 66-88: Table `HORSE` (`horse_id INT IDENTITY(1,1) PRIMARY KEY`, `owner_id INT NOT NULL FK -> APP_USER`, `status`, `activity_status` added in V15).
  - Line 101-115: Table `TRAINING_PLAN` (`plan_id INT PRIMARY KEY`, `horse_id INT FK -> HORSE`, `created_by INT FK -> APP_USER`, `status`).
  - Line 141-165: Table `TRAINING_SESSION` (`session_id INT PRIMARY KEY`, `phase_id INT FK -> PLAN_PHASE`, `horse_id INT FK -> HORSE`, `assigned_to INT FK -> APP_USER` (Groom), `evaluated_by INT FK -> APP_USER` (Head Trainer)).
- **`V12__notification.sql`**:
  - Line 21-41: Table `NOTIFICATION` (`notification_id INT PRIMARY KEY`, `user_id INT FK -> APP_USER`, `notif_type VARCHAR(40)`, `title NVARCHAR(150)`, `message NVARCHAR(500)`, `target_entity VARCHAR(30)`, `target_id INT`).
  - Line 37-40: `CONSTRAINT CK_NOTIF_target_pair CHECK ((target_entity IS NULL AND target_id IS NULL) OR (target_entity IS NOT NULL AND target_id IS NOT NULL))`.
- **`V34__training_review_answers.sql`**:
  - Line 92-95: Constraint `CK_NOTIF_target` permits `target_entity IS NULL OR target_entity IN ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT', 'HORSE_WITHDRAWAL', 'VET_CALL', 'TRAINING_PLAN')`.
- **`V37__training_flow_enhancements.sql`**:
  - Line 34-46:
    - Adds `APP_USER.trainer_id INT NULL` with constraint `FK_USER_trainer FOREIGN KEY (trainer_id) REFERENCES APP_USER(user_id)` to link `GROOM` to `HEAD_TRAINER`.
    - Adds `APP_USER.max_horses INT NULL DEFAULT 10` with constraint `CK_USER_max_horses CHECK (max_horses IS NULL OR max_horses BETWEEN 1 AND 100)`.
  - Line 50-57:
    - Adds `HORSE.trainer_id INT NULL` with constraint `FK_HORSE_trainer FOREIGN KEY (trainer_id) REFERENCES APP_USER(user_id)`.
  - Line 61-82:
    - Adds `TRAINING_SESSION.planned_duration INT NOT NULL DEFAULT 60` with check constraint (15..720 minutes).
    - Adds `TRAINING_SESSION.started_at`, `ended_at`, `early_stop_reason`, `review_status` ('NONE', 'PENDING', 'ARCHIVED_AUTO', 'REVIEWED'), `auto_reviewed_at`.

### 1.2 Test Execution Baseline
- Command: `cd backend; .\mvnw.cmd test`
- Tool output:
  ```
  [INFO] Running racehorse.backend.BusinessRulesTest
  [INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.09 s -- in racehorse.backend.BusinessRulesTest
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] Total time: 36.224 s
  ```
- Configuration (`backend/pom.xml` lines 116-125):
  Surefire plugin is configured specifically to include `**/BusinessRulesTest.java` with `-javaagent:.../mockito-core-5.23.0.jar -XX:+EnableDynamicAgentLoading`.
- Test architecture: Pure Mockito POJO tests without database dependencies. Fast execution (~10 seconds).

### 1.3 Test Coverage in `BusinessRulesTest.java`
- **Requirement R1 (Club Manager assigns Groom to Head Trainer <= 3)**:
  - Line 620-637: `clubManagerAssignsGroomToHeadTrainerSuccessfully()` verifies that assigning groom #5 to trainer #2 succeeds when current active grooms count is 2.
  - Line 639-654: `clubManagerAssigningFourthGroomToHeadTrainerIsRejected()` verifies that assigning groom #5 to trainer #2 throws `ApiException` (409) with message `"maximum of 3 active grooms"` when current active grooms count is 3.
  - Line 656-665: `headTrainerCannotSelfAssignGrooms()` verifies that Head Trainer self-assigning throws `ApiException` (403) `"Only Club Manager can assign Grooms"`.
  - Line 667-689: `clubManagerRegistersGroomWithHeadTrainerSuccessfully()` verifies groom creation with assigned trainer succeeds when count is 2.
  - Line 691-707: `clubManagerRegisteringGroomWithHeadTrainerAtLimitIsRejected()` verifies groom creation with assigned trainer throws 409 when count is 3.
  - Line 709-736: `nonManagerCannotRegisterStaff`, `nonManagerCannotChangeStaffStatus`, `nonManagerCannotChangeStaffRole`.
  - Line 738-770: `clubManagerUpdatesHeadTrainerHorseQuotaSuccessfully`, `clubManagerLoweringHorseQuotaBelowCurrentlyManagedCountIsRejected`, `nonManagerCannotUpdateTrainerHorseQuota`.
- **Requirement R2 (Head Trainer horse list scoping)**:
  - In `HorseService.java` lines 256-259:
    ```java
    if ("HEAD_TRAINER".equals(user.role())) {
        return horseRepository.findByTrainer(user.userId());
    }
    ```
  - In `BusinessRulesTest.java`: **Zero occurrences** of `findByTrainer` or `horseService.list(TRAINER)`. There is **NO** test verifying Head Trainer only receives assigned horses.
- **Requirement R3 (Head Trainer session creation groom & plan scoping)**:
  - Line 994-1009: `headTrainerCannotScheduleSessionForHorseAssignedToAnotherTrainer()` verifies scheduling session for horse assigned to another trainer throws 403 (`"assigned to Head Trainer Other Trainer"`).
  - Line 1035-1053: `headTrainerCannotAssignGroomFromAnotherManagementTeam()` verifies assigning groom not belonging to trainer throws 400 with field error `assignedTo` (`"not assigned to your management team"`).
  - Line 1056-1073: `headTrainerCannotRescheduleSessionForHorseAssignedToAnotherTrainer()` verifies rescheduling session for horse assigned to another trainer throws 403.
  - Missing in `BusinessRulesTest.java`:
    - No test for scheduling session with unassigned horse (`horse.trainerId() == null`) throwing 403.
    - No test for successful session creation with valid assigned horse and assigned groom.
    - No test for `TrainingSessionService.listActiveGrooms(TRAINER)` calling `findActiveGroomsByTrainer`.
    - No test for `TrainingPlanService.list(null, null, TRAINER)` filtering plans by `findHorseIdsByTrainer`.
    - No test for `TrainingPlanService.create(...)` rejecting horse of another trainer or unassigned horse.
- **Requirement R4 (Two-way in-app notifications for Groom & Horse assignment)**:
  - Groom assignment notifications:
    - Line 635-636 in `clubManagerAssignsGroomToHeadTrainerSuccessfully`:
      ```java
      verify(notifications).notifyUser(eq(2), argThat(n -> "GROOM_ASSIGNED".equals(n.type())));
      verify(notifications).notifyUser(eq(5), argThat(n -> "TRAINER_ASSIGNED".equals(n.type())));
      ```
  - Horse assignment notifications:
    - In `HorseService.java` lines 151-173: `notifyTrainerChange` emits `HORSE_ASSIGNED_TO_TRAINER` to Trainer and `HORSE_TRAINER_CHANGED` to Owner.
    - In `BusinessRulesTest.java`: **Zero occurrences** of `HORSE_ASSIGNED_TO_TRAINER` or `HORSE_TRAINER_CHANGED`. There is **NO** test verifying horse assignment emits notifications to Trainer and Horse Owner.

---

## 2. Logic Chain

1. **Database Schema Readiness**:
   - V37 added `APP_USER.trainer_id` (Groom -> Head Trainer), `APP_USER.max_horses` (Head Trainer quota), `HORSE.trainer_id` (Horse -> Head Trainer), and `TRAINING_SESSION.planned_duration`.
   - V12/V34 provide table `NOTIFICATION` with `CK_NOTIF_target` constraint allowing target entities `HORSE`, `TRAINING_PLAN`, etc., or `NULL` with `target_id = NULL`.
   - Therefore, the database schema already completely supports all relationships and data persistence needed for R1 through R4 without requiring new schema migrations.

2. **Test Infrastructure Capabilities**:
   - `pom.xml` configures Maven Surefire to target `BusinessRulesTest.java`.
   - Running `./mvnw.cmd test` passes 89 tests in ~10 seconds.
   - All tests use Mockito mocks for repositories (`StaffRepository`, `HorseRepository`, `TrainingSessionRepository`, `TrainingPlanRepository`, `NotificationService`).
   - Therefore, any new tests added directly to `BusinessRulesTest.java` will be immediately picked up and executed cleanly without external infrastructure dependencies.

3. **Current Test Suite vs R1-R5 Requirements Evaluation**:
   - R1 (Groom management by Head Trainer <= 3): fully covered with positive (2 -> 3), negative (3 -> 4 rejection), permission (403 for non-manager), and registration-time quota enforcement.
   - R2 (Horse list scoping for Head Trainer): entirely missing in `BusinessRulesTest.java`. Although `HorseService.list(TRAINER)` has the logic, no test asserts that `findByTrainer` is called or that unauthorized horses are excluded.
   - R3 (Session creation groom and plan scoping): partially covered (negative checks for other trainer's horse and other trainer's groom exist). Missing: unassigned horse rejection, positive creation test, groom dropdown scoping test (`listActiveGrooms`), and training plan scoping tests (`TrainingPlanService.list` and `create`).
   - R4 (Two-way in-app notifications): Groom assignment two-way notification is verified. Horse assignment two-way notification is completely unverified.

---

## 3. Caveats

1. **Integration vs Unit Testing**:
   `BusinessRulesTest.java` runs mock-based unit tests. It does not execute live T-SQL queries against Microsoft SQL Server. The queries themselves were verified via database migrations (`V37__training_flow_enhancements.sql`) and repository code reviews.
2. **`BackendApplicationTests.java`**:
   `BackendApplicationTests` contains only `@Test void contextLoads() {}`. It is excluded by `pom.xml` surefire configuration to avoid requiring a live SQL Server instance during unit testing.
3. **TrainingPlanService.get Scoping**:
   In `TrainingPlanService.get(int planId, AuthenticatedUser user)`, checking for `HEAD_TRAINER` is currently absent (it only checks `HORSE_OWNER`). When implementing R3, adding this check will ensure that direct URL navigation or API calls to a plan of another trainer's horse returns 403.

---

## 4. Conclusion

The database schema and backend service implementations for R1 through R4 are largely in place. The test suite is fast, reliable, and uses a clean Mockito pattern.
However, for Requirement R5, there are specific, critical test gaps that must be implemented:
1. **R2 Test Gap**: Unit test for `HorseService.list(TRAINER)` verifying only assigned horses are returned via `horseRepo.findByTrainer(2)`.
2. **R3 Test Gaps**:
   - Unit test for `TrainingSessionService.create` with unassigned horse (`horse.trainerId() == null`) throwing 403.
   - Unit test for `TrainingSessionService.create` successful creation with assigned groom and assigned horse.
   - Unit test for `TrainingSessionService.listActiveGrooms(TRAINER)` returning only assigned grooms.
   - Unit test for `TrainingPlanService.list(null, null, TRAINER)` returning only plans for managed horses.
   - Unit test for `TrainingPlanService.create` verifying rejection when horse is not assigned to the Head Trainer.
3. **R4 Test Gaps**:
   - Unit test for `HorseService.assignTrainer` verifying two-way notifications sent to Head Trainer (`HORSE_ASSIGNED_TO_TRAINER`) and Horse Owner (`HORSE_TRAINER_CHANGED`).
   - Unit test for `HorseService.updateIdentity` with trainer update verifying two-way notifications.
   - Unit test for reassigning a horse from Trainer A to Trainer B verifying notification to Trainer B (assigned), Trainer A (unassigned), and Owner.

---

## 5. Verification Method

### 5.1 Test Execution Command
To independently verify the test suite compilation and execution:
```powershell
cd "d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\backend"
.\mvnw.cmd test
```
**Expected baseline output**:
- `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

### 5.2 Files to Inspect for Verification
1. `backend/src/main/resources/migration/V37__training_flow_enhancements.sql` (schema alterations for `APP_USER.trainer_id`, `HORSE.trainer_id`, `APP_USER.max_horses`).
2. `backend/src/test/java/racehorse/backend/BusinessRulesTest.java` (lines 620-770 for staff tests, lines 958-1073 for trainer/horse session tests).
3. `backend/src/main/java/racehorse/backend/horse/HorseService.java` (lines 140-173 for `notifyTrainerChange`, lines 256-259 for `list`).
4. `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java` (lines 83-89 for `listActiveGrooms(user)`, lines 470-492 for `checkTrainerManagesHorse` and `validateGroomAssignee`).
5. `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java` (lines 157-167 for `list`, lines 210-222 for `checkTrainerManagesHorse`).

### 5.3 Invalidation Conditions
- If running `.\mvnw.cmd test` results in compiler errors or test failures.
- If `HORSE_ASSIGNED_TO_TRAINER` notification fails database constraint `CK_NOTIF_target` (it will not, since `HORSE` is explicitly permitted).
- If any change causes regressions in the 89 existing test cases.
