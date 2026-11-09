# Project: Racehorse Training Management System — Training Flow Enhancements

## Architecture
- **Backend Architecture**: Spring Boot 3.3.4, Java 21, Spring Data JDBC with Microsoft SQL Server, JWT Authentication (`AuthInterceptor`).
  - `staff` module: `StaffController`, `StaffService`, `StaffRepository`, `StaffRow`. Manages staff accounts, Head Trainer assignment to Groom, 3-groom limit (`MAX_GROOMS_PER_TRAINER = 3`).
  - `horse` module: `HorseController`, `HorseService`, `HorseRepository`, `HorseResponse`. Manages horses, Head Trainer assignment to Horse, horse scoping per user role.
  - `training` module: `TrainingSessionController`, `TrainingSessionService`, `TrainingSessionRepository`, `TrainingPlanController`, `TrainingPlanService`. Manages sessions and plans, scopes available grooms and plans by Head Trainer.
  - `session` module: `SessionController`, `SessionService`, `SessionRepository`. Manages session evaluations and logs.
  - `notification` module: `NotificationController`, `NotificationService`, `NotificationRepository`, `Notice`. In-app two-way notifications.
- **Frontend Architecture**: React 18, React Router v6.
  - `features/manager/`: `StaffPage.js`, `staffApi.js`.
  - `features/horse/`: `HorseListPage.js`, `HorseDetailPage.js`, `HorseReviewPage.js`, `horseApi.js`.
  - `features/trainer/`: `TrainingPlanPage.js`, `SchedulePage.js`, `SessionEvaluationPage.js`.
  - `features/notification/`: `NotificationBell.js`, `notificationApi.js`.
- **Database Migrations**: Flyway migrations in `backend/src/main/resources/migration/` (`V1` through `V37__training_flow_enhancements.sql`).

---

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Head Trainer to Groom Assignment (StaffPage) | Club Manager assigns/updates Head Trainer for Groom with capacity display (`groomCount/3`) | M1 | ORIGINAL_REQUEST §R1 |
| F2 | 3-Groom Limit Enforcement | Strict enforcement of max 3 active Grooms per Head Trainer (HTTP 409 Conflict, UI disabled option & error notice) | M1 | ORIGINAL_REQUEST §R1 |
| F3 | Head Trainer Horse List Scoping | `GET /api/horses` filtered via `findByTrainer(userId)` for `HEAD_TRAINER`, returning only managed horses | M1 | ORIGINAL_REQUEST §R2 |
| F4 | Session Creation Groom Scoping | `GET /api/training-sessions/assignees` returns only Grooms managed by the Head Trainer | M1 | ORIGINAL_REQUEST §R3 |
| F5 | Session Creation Training Plan Scoping | `GET /api/training-plans?status=ACTIVE` returns only plans belonging to horses managed by the Head Trainer | M1 | ORIGINAL_REQUEST §R3 |
| F6 | Training Session & Plan Creation Validation Hardening | Strict server validation rejecting session or plan creation for unassigned horse or horse/groom of another trainer | M1 | ORIGINAL_REQUEST §R3 |
| F7 | Two-Way In-App Notification: Groom Assignment | Notifies Head Trainer (`GROOM_ASSIGNED`) and Groom (`TRAINER_ASSIGNED`) on assignment | M1 | ORIGINAL_REQUEST §R4 |
| F8 | Two-Way In-App Notification: Horse Assignment | Notifies Head Trainer (`HORSE_ASSIGNED_TO_TRAINER`) and Horse Owner (`HORSE_TRAINER_CHANGED`) on assignment | M1 | ORIGINAL_REQUEST §R4 |
| F9 | Frontend Polish & Subtitle Alignment | Update `HorseListPage.js` subtitle for Head Trainer | M1 | ORIGINAL_REQUEST §R2 |
| F10 | Automated Test Suite for R1-R4 Business Rules | Unit & integration tests in `BusinessRulesTest.java` covering F1-F8 | M1 | ORIGINAL_REQUEST §R5 |
| F11 | Final E2E Suite Verification & Adversarial Coverage Hardening | Full suite test run (`mvnw test` exit code 0), frontend build (`npm run build` exit code 0), and adversarial audit | M2 | ORIGINAL_REQUEST §R5 |

---

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Training Flow Enhancements & Automated Tests | Implement backend scoping hardening (`StaffService`, `TrainingSessionService`, `TrainingPlanService`, `SessionService`), frontend subtitle polish (`HorseListPage.js`), and comprehensive automated tests in `BusinessRulesTest.java` | none | DONE |
| M2 | Final E2E Verification & Adversarial Hardening | Execute full test suite (`mvnw test`), verify zero regressions, verify clean frontend build (`npm run build`), conduct adversarial verification and forensic audit | M1 | DONE |

---

## Interface Contracts

### 1. Staff Management (`StaffService` / `StaffController`)
- `PUT /api/staff/{id}/trainer`: Body `{"trainerId": Integer}`
  - Actor role: `CLUB_MANAGER`
  - Validations: Groom exists and is active; Trainer exists, is active `HEAD_TRAINER`, and has `countActiveGroomsOfTrainer < 3`.
  - Errors: 403 Forbidden (non-manager), 404 Not Found, 409 Conflict (`"Head Trainer ... is already managing the maximum of 3 active grooms"`).
  - Side effects: Two-way notification (`GROOM_ASSIGNED` to trainer, `TRAINER_ASSIGNED` to groom).
- `POST /api/staff` (Groom creation):
  - If `trainerId` is provided, validate 3-groom limit and trigger two-way notifications.

### 2. Horse Scoping (`HorseService` / `HorseController`)
- `GET /api/horses`:
  - Header: `Authorization: Bearer <JWT>`
  - Role `HEAD_TRAINER`: calls `horseRepository.findByTrainer(user.userId())`.
  - Returns `List<HorseResponse>` scoped strictly to horses managed by the Head Trainer.

### 3. Training Session & Plan Scoping (`TrainingSessionService` / `TrainingPlanService`)
- `GET /api/training-sessions/assignees`:
  - Role `HEAD_TRAINER`: calls `repository.findActiveGroomsByTrainer(user.userId())`.
  - Returns only active grooms managed by the Head Trainer.
- `GET /api/training-plans?status=ACTIVE`:
  - Role `HEAD_TRAINER`: filters plans by `horseRepository.findHorseIdsByTrainer(user.userId())`.
- `POST /api/training-sessions`:
  - Validation: Horse must have `trainer_id == user.userId()`; Groom must have `trainer_id == user.userId()`.
  - Errors: 400 Bad Request with field error `assignedTo` if groom is not in trainer's team; 403 Forbidden if horse is unassigned or assigned to another trainer.

### 4. Two-Way In-App Notifications (`NotificationService`)
- Groom Assignment:
  - Trainer: `type = "GROOM_ASSIGNED"`, `targetEntity = null`, `targetId = null`
  - Groom: `type = "TRAINER_ASSIGNED"`, `targetEntity = null`, `targetId = null`
- Horse Assignment:
  - Trainer: `type = "HORSE_ASSIGNED_TO_TRAINER"`, `targetEntity = "HORSE"`, `targetId = horseId`
  - Owner: `type = "HORSE_TRAINER_CHANGED"`, `targetEntity = "HORSE"`, `targetId = horseId`

---

## Code Layout
- Exclusive Write Ownership for M1 Worker:
  - `backend/src/main/java/racehorse/backend/staff/StaffService.java`
  - `backend/src/main/java/racehorse/backend/training/TrainingSessionService.java`
  - `backend/src/main/java/racehorse/backend/training/TrainingPlanService.java`
  - `backend/src/main/java/racehorse/backend/session/SessionService.java`
  - `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`
  - `frontend/src/features/horse/HorseListPage.js`
