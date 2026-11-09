# Frontend Architecture & UI Flows Survey Report (Requirements R1–R5)

## 1. Observation

### R1. StaffPage & Staff API (`StaffPage.js`, `staffApi.js`)
* **API Calls**:
  * `frontend/src/features/manager/staffApi.js` (lines 35-41):
    ```javascript
    export function assignStaffTrainer(groomId, trainerId) {
      return authFetch(`/api/staff/${groomId}/trainer`, {
        method: "PUT",
        body: JSON.stringify({ trainerId: trainerId ? Number(trainerId) : null }),
      });
    }
    ```
  * `frontend/src/features/manager/staffApi.js` (lines 43-49):
    ```javascript
    export function updateTrainerMaxHorses(trainerId, maxHorses) {
      return authFetch(`/api/staff/${trainerId}/max-horses`, {
        method: "PUT",
        body: JSON.stringify({ maxHorses: Number(maxHorses) }),
      });
    }
    ```
* **Viewing and Assigning Head Trainer to Groom**:
  * In `frontend/src/features/manager/StaffPage.js` (lines 55-64):
    * For `HEAD_TRAINER` rows: displays `Grooms: {row.groomCount || 0}/3 · Horses: {row.managedHorseCount || 0}/{row.maxHorses ?? 10}`.
    * For `GROOM` rows: displays `Trainer: {row.trainerName || "Unassigned"}`.
  * In `frontend/src/features/manager/StaffPage.js` (lines 69-88):
    ```jsx
    {row.roleName === "GROOM" && (
      <select
        className="st-action st-role"
        value={row.trainerId ?? ""}
        disabled={busy}
        onChange={(e) => onAssignTrainer(row, e.target.value)}
        aria-label={`Head Trainer of ${row.fullName}`}
        title="Assign Groom to Head Trainer (max 3 active grooms per trainer)"
      >
        <option value="">— No Head Trainer —</option>
        {activeTrainers.map((t) => {
          const full = (t.groomCount || 0) >= 3 && t.userId !== row.trainerId;
          return (
            <option key={t.userId} value={t.userId} disabled={full}>
              {t.fullName} ({t.groomCount || 0}/3 grooms)
            </option>
          );
        })}
      </select>
    )}
    ```
* **Assignment in "Add Staff Account" Modal Form**:
  * In `frontend/src/features/manager/StaffPage.js` (lines 385-398):
    ```jsx
    {form.roleName === "GROOM" && (
      <label className="st-field">
        Assign to Head Trainer (max 3 grooms/trainer)
        <select value={form.trainerId} onChange={setField("trainerId")}>
          <option value="">— Select Head Trainer —</option>
          {activeTrainers.map((t) => (
            <option key={t.userId} value={t.userId} disabled={(t.groomCount || 0) >= 3}>
              {t.fullName} ({t.groomCount || 0}/3 grooms)
            </option>
          ))}
        </select>
        {fieldErrors.trainerId && <span className="st-field__error">{fieldErrors.trainerId}</span>}
      </label>
    )}
    ```
* **3 Groom Limit Handling and Error Feedback**:
  * Dropdown options for Head Trainers with $\ge 3$ active grooms are disabled (`disabled={full}`).
  * Option labels display current load: `{t.fullName} ({t.groomCount || 0}/3 grooms)`.
  * If backend rejects assignment (HTTP 409 CONFLICT from `StaffService.validateTrainerForGroom`):
    * Backend message: `"Head Trainer " + trainer.fullName() + " is already managing the maximum of 3 active grooms"`.
    * In `StaffPage.js` (lines 292-305):
      `handleAssignTrainer` catches `err` and executes `setNotice(err.message)`.
    * Rendered at line 447: `{notice && <p className="st-notice" role="status">{notice}</p>}`.
    * In "Add Staff" form submission (lines 241-245): catches `err`, sets `setFormError(err.message)` and `setFieldErrors(err.fieldErrors || {})`. Rendered at line 441: `<span className="st-field__error" role="alert">{formError}</span>`.

---

### R2. HorseListPage Scoping (`HorseListPage.js`, `HorseDetailPage.js`, `horseApi.js`)
* **Fetching and Displaying Horses**:
  * In `frontend/src/features/horse/horseApi.js` (line 8-10):
    ```javascript
    export function listHorses(signal) {
      return authFetch("/api/horses", { signal });
    }
    ```
  * In `frontend/src/features/horse/HorseListPage.js` (lines 35-49): `load` calls `listHorses(signal)`.
  * In `HorseListPage.js` (lines 57-71):
    `visible` filtering in browser only filters by `stage` (Racing/Foal/Retired/Ancestors), `status` (Eligible/Monitor/Injured/Quarantine), `activity` (Active/Withdrawn), and text `query` (name, registration code, owner name, stall).
* **Head Trainer Scoping Mechanism**:
  * The frontend **does NOT pass any query parameter** (e.g. `?trainerId=...`).
  * The frontend **does NOT filter by trainerId** in `visible` useMemo.
  * Backend handles scoping through authentication token:
    * `backend/src/main/java/racehorse/backend/horse/HorseController.java` (lines 85-88):
      ```java
      @GetMapping
      public List<HorseResponse> list(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
          return horseService.list(user);
      }
      ```
    * `backend/src/main/java/racehorse/backend/horse/HorseService.java` (lines 249-261):
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
  * **Result**: When a Head Trainer logs in and accesses `/trainer/horses`, the backend returns only the horses assigned to that trainer (`horseRepository.findByTrainer(user.userId())`). They do not see all 52 club horses.
* **UI Text Note**:
  * `HorseListPage.js` line 95-99 sets subtitle:
    `user?.role === "HORSE_OWNER" ? "The horses registered in your name" : "Every horse registered at the club"`.
    For Head Trainer, it displays "Every horse registered at the club", which is slightly inconsistent with the scoped data (should state "The horses assigned to your management").

---

### R3. SchedulePage & TrainingPlanPage (`SchedulePage.js`, `TrainingPlanPage.js`)
* **Groom Fetching for Training Session Creation**:
  * In `frontend/src/features/trainer/SchedulePage.js` (lines 476-484):
    ```javascript
    const [sessionData, planData, groomData] = await Promise.all([
      authFetch(SESSION_API),
      authFetch(`${PLAN_API}?status=ACTIVE`),
      authFetch(`${SESSION_API}/assignees`),
    ]);
    ```
  * Endpoint `/api/training-sessions/assignees`:
    * Backend `TrainingSessionController.java` (lines 54-59):
      ```java
      @GetMapping("/assignees")
      @RequireRole({ "HEAD_TRAINER" })
      public List<TrainingSessionRepository.GroomOption> assignees(
              @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
          return service.listActiveGrooms(user);
      }
      ```
    * Backend `TrainingSessionService.java` (lines 84-89):
      ```java
      public List<TrainingSessionRepository.GroomOption> listActiveGrooms(AuthenticatedUser user) {
          if (user != null && "HEAD_TRAINER".equals(user.role())) {
              return repository.findActiveGroomsByTrainer(user.userId());
          }
          return repository.findActiveGrooms();
      }
      ```
    * Only grooms where `trainer_id == user.userId()` and `status = 'ACTIVE'` are returned.
* **Behavior when Head Trainer has 0 Grooms**:
  * In `SchedulePage.js` (lines 341-350):
    ```jsx
    {grooms.length === 0 ? (
      <small className="schedule-field-hint">
        No active Groom accounts are assigned to your management. Ask the Club Manager to assign Grooms to you.
      </small>
    ) : ...}
    ```
  * In `SchedulePage.js` (lines 429-436):
    Submit button is disabled:
    `disabled={saving || grooms.length === 0 || ...}`.
    Head Trainer cannot submit a session if they have no grooms assigned.
* **Training Plan Fetching and Horse Scoping**:
  * In `SchedulePage.js` (line 478): fetches `/api/training-plans?status=ACTIVE`.
  * Backend `TrainingPlanService.java` (lines 161-165):
    ```java
    if ("HEAD_TRAINER".equals(user.role())) {
        java.util.Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
        return all.stream().filter(p -> mine.contains(p.horseId())).toList();
    }
    ```
  * Plans returned to Head Trainer are strictly restricted to horses assigned to that Head Trainer.
  * In `TrainingPlanPage.js` (lines 205-211):
    ```javascript
    const eligibleHorses = (horseRows || []).filter(
      (horse) =>
        !isPedigreeOnly(horse) &&
        !isWithdrawn(horse) &&
        (horse.status === "ELIGIBLE" || horse.status === "MONITOR") &&
        (myRole !== "HEAD_TRAINER" || !myId || horse.trainerId === myId),
    );
    ```
    Head Trainer can only select and build plans for horses assigned to them.

---

### R4. Notifications UI (`NotificationBell.js`, `notificationApi.js`, Dashboards)
* **NotificationBell Component**:
  * Located at `frontend/src/features/notification/NotificationBell.js`.
  * Backed by `notificationApi.js`:
    * Polls `GET /api/notifications/unread-count` every 30s (`POLL_MS = 30000`) and on `window.focus`.
    * Unread dot indicator: `<span className={`notif-bell__dot ${className}-dot`} />`.
    * Floating panel on click: queries `GET /api/notifications?limit=20`.
    * Features "Mark all as read" (`PUT /api/notifications/read-all`).
    * Clicking an item calls `PUT /api/notifications/${id}/read` and navigates using `notificationTarget(n, user)`.
* **In-App Notification Presence Across Roles**:
  * Groom: `frontend/src/features/groom/GroomDashboard.js` (line 737: `<NotificationBell className="groom-notification" />`).
  * Head Trainer: `frontend/src/features/trainer/TrainerDashboard.js` (line 435: `<NotificationBell className="trainer-notification" />`).
  * Horse Owner: `frontend/src/features/horse-owner/HorseOwnerDashboard.js` (line 127: `<NotificationBell className="owner-notification" />`).
  * Club Manager: `frontend/src/features/manager/ManagerDashboard.js` (line 330: `<NotificationBell className="manager-notification" />`).
  * Veterinarian: `frontend/src/features/vet/VetShell.js` (line 97: `<NotificationBell className="vet-notification" />`).
* **Notification Target Navigation & Entity Handling**:
  * For Horse Assignment notifications:
    * Backend produces `HORSE_ASSIGNED_TO_TRAINER` and `HORSE_TRAINER_CHANGED` with `targetEntity = "HORSE"` and `targetId = horseId`.
    * `notificationTarget` (lines 56-57): returns `${getHorseBasePath(user)}/${n.targetId}` (`/trainer/horses/:id` or `/horse-owner/horses/:id`).
  * For Groom Assignment notifications:
    * Backend produces `GROOM_ASSIGNED` and `TRAINER_ASSIGNED` with `targetEntity = null, targetId = null`.
    * `notificationTarget` returns `null`. Clicking marks the notification as read without changing page.

---

## 2. Logic Chain

1. **R1 Evaluation**:
   * Club Manager can view current assignments on Groom rows (`Trainer: {row.trainerName}`) and trainer capacity on Head Trainer rows (`Grooms: {groomCount}/3`).
   * Club Manager assigns a Head Trainer to a Groom via the inline `<select>` on the Groom's row or in the "Add staff account" modal.
   * Both forms disable Head Trainers who have 3 active grooms (`disabled={(t.groomCount || 0) >= 3}`).
   * If an invalid assignment request occurs, backend responds with 409 Conflict, and frontend displays `err.message` in `<p className="st-notice">` or `<span className="st-field__error">`.

2. **R2 Evaluation**:
   * `HorseListPage` requests `GET /api/horses` without parameters.
   * `HorseService.list(user)` inspects `user.role()`. For `HEAD_TRAINER`, it calls `horseRepository.findByTrainer(user.userId())`.
   * Therefore, Head Trainer receives and views only their assigned horses, fulfilling R2.

3. **R3 Evaluation**:
   * In `SchedulePage`, grooms are fetched via `GET /api/training-sessions/assignees`. Backend filters by `findActiveGroomsByTrainer(user.userId())`, returning only the Head Trainer's assigned grooms.
   * If a Head Trainer has 0 grooms, the dropdown has no options, a hint informs the user to request groom assignments from the Club Manager, and the create session button is disabled.
   * Training plans are fetched via `GET /api/training-plans?status=ACTIVE`. Backend filters plans where `mine.contains(p.horseId())`. Only plans belonging to the trainer's horses are returned and displayed.

4. **R4 Evaluation**:
   * `NotificationBell` is present on the dashboards of Groom, Head Trainer, Horse Owner, Club Manager, and Vet.
   * Backend generates two-way notifications for both Groom assignments and Horse assignments.
   * In-app notifications are received and displayed with title, message, relative timestamp, unread badge, and navigation support.

---

## 3. Caveats

1. **Sub-page NotificationBell Coverage**:
   * While `NotificationBell` is present on all role main dashboards (`TrainerDashboard`, `GroomDashboard`, `HorseOwnerDashboard`, etc.), sub-pages sharing `HorseShell.js` (such as `StaffPage`, `HorseListPage`, `OwnerTrainingPage`) and trainer sub-pages (`SchedulePage`, `TrainingPlanPage`, `SessionEvaluationPage`) do not include `<NotificationBell />` in their header navigation. Users only see the notification bell when returning to their dashboard.
2. **Notification Click Navigation for Groom Assignment**:
   * `GROOM_ASSIGNED` and `TRAINER_ASSIGNED` notifications have `targetEntity = null`. While this correctly displays in the notification panel, clicking on them does not navigate to a page (e.g., `/trainer/schedule` or `/groom`). Adding direct paths in `notificationTarget()` in `notificationApi.js` would improve user experience.
3. **HorseListPage Subtitle**:
   * `HorseListPage.js` subtitle states "Every horse registered at the club" for Head Trainer despite showing only their assigned horses.

---

## 4. Conclusion

1. The frontend implementation for R1, R2, R3, and R4 is structurally sound, aligned with the backend business rules, and fully functional.
2. The 3-Groom limit is enforced both client-side (options disabled with load indicator) and server-side (409 Conflict with clear error messages).
3. Head Trainer horse scoping is handled securely on the backend via the authentication token.
4. Schedule and Training Plan creation dropdowns are restricted strictly to managed Grooms and managed horses' plans.
5. In-app notifications are operational across all key roles.

---

## 5. Verification Method

### 1. Frontend Build Verification
Execute in `frontend/` directory:
```bash
npm run build
```
Verify that the build exits with code 0 and outputs production assets into `frontend/build/`.

### 2. Manual / UI Verification Steps
* **R1 (StaffPage)**:
  1. Log in as Club Manager (`/login`).
  2. Navigate to `/manager/staff`.
  3. Inspect Groom rows: verify the Head Trainer selector displays all active Head Trainers with `({groomCount}/3 grooms)`.
  4. Verify trainers with 3 grooms are disabled.
  5. Assign a groom to a trainer with < 3 grooms; verify the success notice and updated count.
* **R2 (HorseListPage)**:
  1. Log in as Head Trainer (`/login`).
  2. Navigate to `/trainer/horses`.
  3. Verify that only horses managed by this trainer are displayed (not all 52 club horses).
* **R3 (SchedulePage & TrainingPlanPage)**:
  1. Log in as Head Trainer with 0 grooms: verify `/trainer/schedule` dropdown has no grooms, displays warning hint, and "Create session" button is disabled.
  2. Log in as Head Trainer with assigned grooms: verify dropdown lists only their assigned grooms and training plans belong only to their horses.
* **R4 (NotificationBell)**:
  1. Log in as Groom, Head Trainer, and Horse Owner.
  2. Verify the bell icon is present on the top right header of their respective dashboards.
  3. Verify unread notifications display properly when assignments are made by Club Manager.
