# Backend Architecture & APIs Survey Report (R1 - R5)

**Agent**: `teamwork_explorer_survey_backend`  
**Role**: Codebase Researcher (Backend Architecture & APIs)  
**Date**: 2026-11-08  
**Working Directory**: `d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_backend\`  

---

## 1. Observation

### 1.1 Requirement R1: Staff Management, Groom Assignment, and 3-Groom Limit Enforcement
Directly inspected files:
- `backend/src/main/resources/migration/V37__training_flow_enhancements.sql` (lines 34–46):
  ```sql
  IF COL_LENGTH('APP_USER', 'trainer_id') IS NULL
      ALTER TABLE APP_USER ADD trainer_id INT NULL;
  IF OBJECT_ID('FK_USER_trainer', 'F') IS NULL
      ALTER TABLE APP_USER ADD CONSTRAINT FK_USER_trainer
          FOREIGN KEY (trainer_id) REFERENCES APP_USER(user_id);
  ```
- `backend/src/main/java/racehorse/backend/staff/StaffRow.java` (lines 31–40):
  - Record fields: `Integer trainerId`, `String trainerName`, `int groomCount`, `int managedHorseCount`, `Integer maxHorses`.
- `backend/src/main/java/racehorse/backend/staff/StaffRepository.java`:
  - Lines 49–54: Subquery in `SELECT_STAFF` calculates `groom_count`:
    ```sql
    (SELECT COUNT(*) FROM APP_USER g
     JOIN ROLE gr ON gr.role_id = g.role_id
     WHERE g.trainer_id = u.user_id AND gr.role_name = 'GROOM' AND g.status = 'ACTIVE') AS groom_count,
    ```
  - Lines 183–197: `countActiveGroomsOfTrainer(int trainerId, Integer excludeUserId)`:
    ```sql
    SELECT COUNT(*) FROM APP_USER g
    JOIN ROLE r ON r.role_id = g.role_id
    WHERE g.trainer_id = :trainerId
      AND r.role_name = 'GROOM'
      AND g.status = 'ACTIVE'
      AND (:excludeId IS NULL OR g.user_id <> :excludeId)
    ```
  - Lines 200–205: `updateTrainer(int groomId, Integer trainerId)`:
    ```sql
    UPDATE APP_USER SET trainer_id = :trainerId WHERE user_id = :id
    ```
- `backend/src/main/java/racehorse/backend/staff/StaffService.java`:
  - Line 43: `public static final int MAX_GROOMS_PER_TRAINER = 3;`
  - Lines 231–264: `assignTrainer(int groomId, Integer trainerId, AuthenticatedUser actor, String ip)`:
    - Verifies `actor.role().equals("CLUB_MANAGER")`.
    - Verifies groom exists and has `roleName == "GROOM"`.
    - When `trainerId != null`, calls `validateTrainerForGroom(trainerId, groomId)`:
      - Lines 292–305: Checks trainer is active `HEAD_TRAINER`, queries `staff.countActiveGroomsOfTrainer(trainerId, excludeGroomId)`.
      - If `currentGrooms >= MAX_GROOMS_PER_TRAINER`:
        ```java
        throw new ApiException(HttpStatus.CONFLICT,
            "Head Trainer " + trainer.fullName() + " is already managing the maximum of "
            + MAX_GROOMS_PER_TRAINER + " active grooms");
        ```
      - Emits two-way notifications (`GROOM_ASSIGNED` to trainer, `TRAINER_ASSIGNED` to groom).
  - Lines 119–121 (`create` method): When creating a groom with `req.trainerId() != null`, validates limit and sets `staff.updateTrainer(userId, trainer.userId())`, but **omits two-way notifications**.
- `backend/src/main/java/racehorse/backend/staff/StaffController.java`:
  - Lines 80–89:
    ```java
    @PutMapping("/{id}/trainer")
    @RequireRole({"CLUB_MANAGER"})
    public StaffRow assignTrainer(@PathVariable int id, @RequestBody TrainerAssignRequest request, ...)
    ```

### 1.2 Requirement R2: Horse Listing Scoping for Head Trainer
Directly inspected files:
- `backend/src/main/java/racehorse/backend/auth/AuthInterceptor.java` (lines 36–87):
  - Extracts Bearer token, parses JWT into `AuthenticatedUser` (`userId`, `username`, `role`), verifies database active status & role match, sets `req.setAttribute("authUser", user)`.
- `backend/src/main/java/racehorse/backend/horse/HorseController.java` (lines 85–88):
  ```java
  @GetMapping
  public List<HorseResponse> list(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
      return horseService.list(user);
  }
  ```
- `backend/src/main/java/racehorse/backend/horse/HorseService.java` (lines 249–260):
  ```java
  public java.util.List<HorseResponse> list(AuthenticatedUser user) {
      if ("HORSE_OWNER".equals(user.role())) {
          return horseRepository.findByOwner(user.userId());
      }
      if ("GROOM".equals(user.role())) {
          return horseRepository.findActive();
      }
      // V37: Head Trainer chỉ thấy những ngựa Club Manager đã giao cho mình quản lý.
      if ("HEAD_TRAINER".equals(user.role())) {
          return horseRepository.findByTrainer(user.userId());
      }
      return horseRepository.findAll();
  }
  ```
- `backend/src/main/java/racehorse/backend/horse/HorseRepository.java` (lines 88–94):
  ```java
  public List<HorseResponse> findByTrainer(int trainerId) {
      return jdbc.sql(SELECT_HORSE + " WHERE h.trainer_id = :trainerId ORDER BY h.horse_name")
              .param("trainerId", trainerId)
              .query(HorseRepository::mapHorse)
              .list();
  }
  ```
- Scoping gap identified:
  - In `backend/src/main/java/racehorse/backend/session/SessionService.java` (lines 49–62) & `SessionRepository.java` (lines 62–79): `GET /api/sessions` (used by `SessionEvaluationPage.js`) filters only for `HORSE_OWNER`. There is **no filtering for `HEAD_TRAINER`**, exposing all sessions of all horses in the club to any Head Trainer.

### 1.3 Requirement R3: Training Session Creation Scoping
Directly inspected files:
- Available Grooms:
  - `backend/src/main/java/racehorse/backend/training/TrainingSessionController.java` (lines 54–59):
    ```java
    @GetMapping("/assignees")
    @RequireRole({ "HEAD_TRAINER" })
    public List<TrainingSessionRepository.GroomOption> assignees(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user)
    ```
  - `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java` (lines 84–89):
    ```java
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms(AuthenticatedUser user) {
        if (user != null && "HEAD_TRAINER".equals(user.role())) {
            return repository.findActiveGroomsByTrainer(user.userId());
        }
        return repository.findActiveGrooms();
    }
    ```
  - `backend/src/main/java/racehorse/backend/training/TrainingSessionRepository.java` (lines 336–350):
    Queries `APP_USER` where `role_name = 'GROOM' AND status = 'ACTIVE' AND trainer_id = :trainerId`.
- Available Plans:
  - `backend/src/main/java/racehorse/backend/training/TrainingPlanController.java` (lines 44–50): `GET /api/training-plans?status=ACTIVE`.
  - `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java` (lines 157–167):
    ```java
    if ("HEAD_TRAINER".equals(user.role())) {
        java.util.Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
        return all.stream().filter(p -> mine.contains(p.horseId())).toList();
    }
    ```
- Validation on Session Creation / Rescheduling:
  - `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`:
    - Lines 107 & 355: `checkTrainerManagesHorse(horse, user)`
    - Lines 115 & 362: `validateGroomAssignee(r.assignedTo(), user, errors)`
    - Lines 482–492:
      ```java
      private void validateGroomAssignee(int groomId, AuthenticatedUser user, Map<String, String> errors) {
          if (!repository.isActiveGroom(groomId)) {
              errors.put("assignedTo", "Assignee must be an active Groom");
              return;
          }
          if (user != null && "HEAD_TRAINER".equals(user.role())
                  && horseRepository.findActiveTrainer(user.userId()).isPresent()
                  && !repository.isGroomOfTrainer(groomId, user.userId())) {
              errors.put("assignedTo", "This Groom is not assigned to your management team by the Club Manager");
          }
      }
      ```
    - Lines 470–480:
      ```java
      private void checkTrainerManagesHorse(HorseResponse horse, AuthenticatedUser user) {
          if (user == null || !"HEAD_TRAINER".equals(user.role())) return;
          if (horse.trainerId() != null && !horse.trainerId().equals(user.userId())) {
              throw new ApiException(HttpStatus.FORBIDDEN, "This horse is assigned to Head Trainer ...");
          } else if (horse.trainerId() == null && horseRepository.findActiveTrainer(user.userId()).isPresent()) {
              throw new ApiException(HttpStatus.FORBIDDEN, "This horse has not been assigned to you by the Club Manager yet");
          }
      }
      ```
- Vulnerability / Fragility identified:
  - In `validateGroomAssignee` and `checkTrainerManagesHorse`, checking `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` causes the restriction to be skipped if `findActiveTrainer` is empty (such as in unit tests where `findActiveTrainer` is unmocked). Because `user.role()` is already confirmed as `"HEAD_TRAINER"`, checking `findActiveTrainer.isPresent()` is a redundant condition that creates a bypass risk.
  - In `TrainingPlanService.java` (lines 145–155: `get`, lines 170–180: `deleteDraft`), plan access is not scoped by trainer, allowing any Head Trainer to read or delete draft plans of horses belonging to other trainers.

### 1.4 Requirement R4: Two-Way In-App Notifications
Directly inspected files:
- Structure:
  - `backend/src/main/java/racehorse/backend/notification/Notice.java` (record: `type`, `title`, `message`, `targetEntity`, `targetId`).
  - `backend/src/main/java/racehorse/backend/notification/NotificationRepository.java`:
    - `insertForUser(int userId, Notice n)` inserts into `NOTIFICATION` table with `read_at = NULL`.
- Flow A: Groom Assigned to Head Trainer:
  - `backend/src/main/java/racehorse/backend/staff/StaffService.java` (lines 245–254):
    ```java
    notifications.notifyUser(trainer.userId(), new Notice(
            "GROOM_ASSIGNED",
            "New groom assigned to your team",
            "Club manager assigned groom " + groom.fullName() + " to your team.",
            null, null));
    notifications.notifyUser(groomId, new Notice(
            "TRAINER_ASSIGNED",
            "Assigned to Head Trainer",
            "Club manager assigned you to Head Trainer " + trainer.fullName() + ".",
            null, null));
    ```
    Verified: Emits two-way notification to both Head Trainer and Groom on `PUT /api/staff/{id}/trainer`.
- Flow B: Horse Assigned to Head Trainer:
  - `backend/src/main/java/racehorse/backend/horse/HorseService.java` (lines 144–173, `notifyTrainerChange`):
    - When `newId != null`:
      ```java
      notifications.notifyUser(newId, new Notice(
              "HORSE_ASSIGNED_TO_TRAINER",
              "New horse assigned to your management",
              "The club manager assigned " + horseLabel + " to your training management.",
              Notice.HORSE, after.horseId()));
      ```
    - To Horse Owner:
      ```java
      notifications.notifyUser(after.ownerId(), new Notice(
              "HORSE_TRAINER_CHANGED",
              "Head trainer assigned to your horse",
              "Your horse " + horseLabel + " is now managed by Head Trainer " + after.trainerName() + ".",
              Notice.HORSE, after.horseId()));
      ```
    - Triggered during:
      1. `HorseService.assignTrainer(...)` (line 135)
      2. `HorseService.updateIdentity(...)` (line 109)
      3. `HorseRequestService.approve(...)` (lines 140–142 calls `horseService.assignTrainer`)
    Verified: Emits two-way notification to both Head Trainer and Horse Owner on horse assignment.

### 1.5 Requirement R5: Test Suite Status
- Command executed:
  `cd backend; .\mvnw.cmd test -Dtest=BusinessRulesTest`
- Result:
  `[INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.25 s`
- Observation:
  - In `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`, there are **0 test cases** for:
    1. Club Manager assigning Groom to Head Trainer with 3-groom limit enforcement (<= 3 succeeds, 4th throws `ApiException(HttpStatus.CONFLICT)`).
    2. Head Trainer horse scoping (`HorseService.list(TRAINER)` returning only assigned horses).
    3. Head Trainer session creation validation (rejection of unassigned Groom or Groom belonging to another trainer; rejection of horse belonging to another trainer or unassigned horse).
    4. Two-way notification verification for both Groom assignment and Horse assignment flows.

---

## 2. Logic Chain

1. **R1 Limit Enforcement Logic**:
   - `StaffRepository.countActiveGroomsOfTrainer` filters on `g.trainer_id = :trainerId AND gr.role_name = 'GROOM' AND g.status = 'ACTIVE'`.
   - `StaffService.validateTrainerForGroom` is called on both `assignTrainer` and when re-activating a suspended Groom (`setStatus("ACTIVE")`).
   - If count >= 3, an `ApiException(HttpStatus.CONFLICT)` (HTTP 409) is thrown with a descriptive message.
   - Conclusion: The 3-groom limit is correctly enforced at service level, throwing HTTP 409 CONFLICT.

2. **R2 Scoping Logic**:
   - `HorseController.list` delegates to `HorseService.list(user)`.
   - When `user.role().equals("HEAD_TRAINER")`, `HorseService.list` calls `horseRepository.findByTrainer(user.userId())`, which filters SQL by `h.trainer_id = :trainerId`.
   - However, in `SessionService.list` (`/api/sessions`), there is no check for `HEAD_TRAINER`, returning all sessions across the club.
   - Conclusion: Scoping is implemented for `/api/horses`, but `/api/sessions` requires scoping for `HEAD_TRAINER`.

3. **R3 Session Scoping Logic**:
   - `GET /api/training-sessions/assignees` invokes `findActiveGroomsByTrainer(user.userId())`, returning only Grooms assigned to the requesting Head Trainer.
   - `GET /api/training-plans?status=ACTIVE` invokes `findHorseIdsByTrainer(user.userId())` and filters out all plans for horses not managed by the Head Trainer.
   - In `TrainingSessionService.create` and `reschedule`:
     - `validateGroomAssignee` verifies `isGroomOfTrainer(groomId, trainerId)`.
     - `checkTrainerManagesHorse` verifies `horse.trainerId().equals(user.userId())`.
   - However, the conditional guard `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` in both checks creates a risk where validation is skipped if trainer lookup returns empty.
   - Conclusion: Scoping works, but the redundant `findActiveTrainer.isPresent()` condition should be removed to ensure unconditional security.

4. **R4 Notification Logic**:
   - Groom assignment: `StaffService.assignTrainer` dispatches `GROOM_ASSIGNED` to `trainer.userId()` and `TRAINER_ASSIGNED` to `groomId`. Both use `null, null` as target entity/id.
   - Horse assignment: `HorseService.notifyTrainerChange` dispatches `HORSE_ASSIGNED_TO_TRAINER` to `trainerId` and `HORSE_TRAINER_CHANGED` to `ownerId`, targeting `Notice.HORSE` with `horseId`.
   - Conclusion: Two-way notification mechanisms are fully implemented in the assignment methods, though `StaffService.create` lacks notifications when `trainerId` is supplied during initial groom creation.

5. **R5 Verification Logic**:
   - Current 89 tests in `BusinessRulesTest.java` verify legacy flows (medical locks, care tasks, race results, expired sessions).
   - Because no tests currently instantiate `StaffService.assignTrainer`, `HorseService.findByTrainer`, or the new scoping validations, new test cases must be added to satisfy acceptance criteria.

---

## 3. Caveats

1. **Front-End Interoperability**: While the backend API `/api/training-sessions/assignees` correctly returns only assigned Grooms, if a Head Trainer has 0 Grooms assigned, `assignees` returns an empty list, and session creation is blocked with `"Assignee must be an active Groom"` or `"This Groom is not assigned to your management team"`. Frontend UI handles this with a hint.
2. **Session Evaluation Scoping**: `SessionService.java` is used for session reviews and currently lacks a `HEAD_TRAINER` check in `list()` and `evaluate()`. Adding `mine.contains(s.horseId())` in `SessionService` will prevent cross-trainer evaluations.

---

## 4. Conclusion

The core backend architecture for R1 through R4 is largely in place (introduced in V37 migration and related service classes). However, three specific backend enhancements and one major testing task are required:

1. **StaffService Fix**:
   - In `StaffService.create`: Trigger two-way notifications when a Groom is created with `trainerId != null`.
2. **Scoping Hardening**:
   - In `TrainingSessionService` and `TrainingPlanService`: Remove redundant `&& horseRepository.findActiveTrainer(user.userId()).isPresent()` from `validateGroomAssignee` and `checkTrainerManagesHorse` so unassigned horses/grooms are strictly blocked.
   - In `SessionService.list`: Add `HEAD_TRAINER` scoping so that `/api/sessions` returns only sessions for horses managed by the Head Trainer.
   - In `SessionService.evaluate`: Ensure the session's horse is managed by the evaluating Head Trainer.
3. **Comprehensive Test Suite**:
   - Add unit tests in `BusinessRulesTest.java` covering:
     - 3-groom limit enforcement (assigning 1st, 2nd, 3rd succeeds; 4th throws 409 CONFLICT).
     - Head Trainer horse scoping (`HorseService.list`).
     - Training session groom and horse scoping (`TrainingSessionService.create`).
     - Two-way in-app notifications for both Groom and Horse assignments.

### Exact Files Requiring Modification/Extension:
1. `backend/src/main/java/racehorse/backend/staff/StaffService.java`
2. `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`
3. `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`
4. `backend/src/main/java/racehorse/backend/session/SessionService.java`
5. `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`

---

## 5. Verification Method

To independently verify the backend codebase:
1. **Compilation Check**:
   ```powershell
   cd backend
   .\mvnw.cmd test-compile
   ```
   *Expected*: `BUILD SUCCESS` (exit code 0).
2. **Existing Test Suite**:
   ```powershell
   cd backend
   .\mvnw.cmd test -Dtest=BusinessRulesTest
   ```
   *Expected*: `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` (exit code 0).
3. **Code Inspection**:
   - Check `StaffService.java:292–305` for `MAX_GROOMS_PER_TRAINER` and `HttpStatus.CONFLICT`.
   - Check `HorseService.java:256–259` for `findByTrainer(user.userId())`.
   - Check `TrainingSessionService.java:84–89` for `findActiveGroomsByTrainer`.
   - Check `StaffService.java:245–254` and `HorseService.java:144–173` for two-way `notifications.notifyUser`.
4. **Invalidation Conditions**:
   - If `assignTrainer` allows a 4th groom to be assigned to a Head Trainer.
   - If `/api/horses` returns all club horses when called with a Head Trainer JWT.
   - If `BusinessRulesTest` fails when new tests for R1–R4 are introduced.
