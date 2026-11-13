# Racehorse-Training-Management-System
### Last Commited: `Nov 13, 2026` at `11:10` by `Pandapo274`

## Information
> ### Version: `-`
> ### Frontend: `ReactJS`
> ### Backend: `Java`
> ### Database: `SQLServer`

## Contributors
1. [ArichiAya](https://github.com/NekomataRin) 
2. [tqt195](https://github.com/tqt195)
3. [Pandapo274](https://github.com/Pandapo-274)
4. [tusandthatsall](https://github.com/tusandthatsall)
5. [DevilJack422](https://github.com/DevilJack422)

## Commit Rules
> #### Open your project folder and use this command to clone the project
```
git clone "https://github.com/Pandapo-274/Racehorse-Training-Management-System.git"
```
> #### After your coding session, please use these commands:
```
git pull [use when others pushed the files to the repo]
git add . [use when you have new files added]
git commit -am "[<GitHub Username>] <Date>-<Time> <Commit Message>"

Example: 
git add .
git commit -am "[NekomataRin] 20260929-0948 Initial App Commit"
```
> #### `README.md`
- Update the `Last Commited` line as the format shown above
- Add your `Changelog` in the `Changelogs` tab below

## Running Application
```
Backend: 
cd backend
.\mvnw spring-boot:run

Frontend:
cd frontend
npm start
```

## Changelogs
> ### `Nov 13, 2026`
- `[11:22]` `tqt195` - Fix layout on narrow screens (laptop 1366px, or a big screen at 125-150% zoom) and Flow 4 supplies: `Supplies` table no longer pushes the Actions / Last change column out of the card (amount and minimum in one `In stock` column, buttons wrap, Actions column sticks to the right); bring back `Count` (Groom counts feed and tools, Vet and Manager count everything); the supply `Edit` form now keeps the unit price (it used to wipe it); `Restock` no longer offers Unit / Area fields the backend ignored; `Races` keeps `Record result` visible with a sticky action column (shared class `hz-sticky-end`); owner dashboard `Training stats` no longer cut off between 1100 and 1225px. Add `docs/FLOW4_DEMO_SCRIPT.md` (Flow 4 demo script, test checklist, known issues) and `backend/dev/seed_flow4_demo.sql` (history / current / future data for the Flow 4 demo, safe to re-run)
- `[11:10]` `Pandapo274` -
System-Wide Sign Out Button Synchronization:
- Unified the Sign Out button and bottom profile card across all 5 user roles (Club Manager, Head Trainer, Veterinarian, Groom, Horse Owner) with identical SVG logout icon, font styling (15px / 600 weight), dimensions (44px height), padding (0 16px), gap (10px), and hover transitions to match the horse shell navigation.

- `[10:52]` `Pandapo274` -
UI Synchronization & Navigation Refinements:
- Prescription History Back Navigation: Shortened button label to "← Back" and directed navigation to the "Horse Medical Directory" tab (?tab=directory) instead of the default active prescriptions tab.
- Unified Sign Out Button: Synchronized the Sign out button across all Veterinarian tabs (Herd health, Vet calls, Examination, Recovery, Preventive care, Prescriptions) to identically match the icon, font weight, padding, and layout of Horse, Rations, and Supplies pages.

> ###  `Nov 12, 2026`
- `[23:56]` `DevilJack422` - Update training flow 
- `[08:39]` `tusandthatsall` -
Accounts
- V39 (APP_USER columns, no new table): sign-in locks for 15 minutes after
  5 wrong passwords in a row (429 with the unlock time). A correct sign-in,
  password reset, temporary password or re-activation clears the lock.
- V39 tokens_valid_after: "revoke all sessions" (password change or reset,
  role change, suspension) is stored in the DB, so a backend restart no
  longer brings old tokens back.
- Profile: changing the email requires the current password.
- Club Manager can correct an account's name / email / phone, set a
  temporary password (must be changed at next sign-in, ends all sessions),
  and create Horse Owner accounts at the desk.
- Suspending an owner cancels their open horse registration requests and
  tells the head trainers which plans / race entries are on hold.

Horses
- Club Manager can transfer a horse to another active owner. Refused while
  a training plan, race entry or withdrawal request awaits the current
  owner. Both owners are notified and the reason is audited.
- Club Manager can re-admit a withdrawn horse: free stall, active owner and
  the club age limit are checked.
- Parent picker: owners can only choose a pedigree record or one of their
  own horses as a new sire / dam. A horse's current parents always stay in
  the list.
- Grooms can no longer open withdrawn horses.
- GET /api/horse-requests is limited to owners and the manager.
- Owners can resubmit a rejected or cancelled registration with the form
  pre-filled.
- Owner dashboard counts only horses still at the club.
- Horse validation messages translated to English.


> #### `Nov 9, 2026`
- `[22:10]` `DevilJack422` - Fix some error notifications and sign out button. Added trainer assigned colum in horse table of manager
- `[01:23]` `DevilJack422` - Modified new training flow
> #### `Nov 6, 2026`
- `[12:45]` `Pandapo274` - Update Groom dashboard to display ongoing training sessions and refine responsive UI layout
- `[11:14]` `tqt195` - Responsive layout for the whole frontend (checked on every page at 1440 / 1280 / 1024 / 768 / 390 px): `Sign in`, `Register` and `Forgot password` no longer cut off on phones; on narrow screens the sidebar of the shared pages (horses, races, supplies, costs, audit log, staff, owner pages) and of the `Horse Owner` dashboard shrinks to an icon bar instead of disappearing; wide tables scroll inside their card instead of stretching the page; page headers grow instead of overflowing; card titles no longer squeeze into one word per line; owner hero card no longer overlaps the photo below 1100 px; `Races` note column, `Supplies` stock rows and trainer `Horses in your care` status column fixed; vet `Stable map` grid adapts to the width. e2e script updated for the new rules (owner approves plans, no sessions in a past hour, `STOP_SESSION` opens an incident report, false-alarm dismiss): 160/161 steps pass
- `[10:58]` `ArichiAya` - Fix `Costs and prize money` render issues on the `Horse Owner` dashboard
- `[10:24]` `ArichiAya` - Resolved `vet.css` and `VetDashboard.js` render issues
> #### `Nov 5, 2026`
- `[23:05]` `Pandapo274` - Complete recovery relapse rules, vitals threshold alert distinction, training session cancellation for Head Trainer, supply pricing & inventory deduction, and race approval flow:
  - `Vet Recovery`: Relapse only allows new active injuries incurred during light work/rating; prevent duplicate relapse spam; clean notice layout without premature line breaks.
  - `Groom & Trainer Vitals`: Distinguish Speed alert vs Heart Rate alert messages; clarify alert severity levels (LOW, MEDIUM, HIGH) over threshold ceilings.
  - `Training Schedule`: Head Trainer can cancel upcoming/scheduled sessions directly in `Edit schedule`.
  - `Treatment & Medical Supplies`: Deduct supplies/medicines from club inventory upon prescription; generate expense records with owner dispute flow (`V29`, `V30`, `V32`, `V33`).
  - `Races`: Manager race entry approval flow and test horse seeds (`V28`, `V31`).
- `[22:22]` `tqt195` - Add `Call vet` from alerts: on `Live session` the `Head Trainer` fills a form (reason, signs, urgency, stop the session now or not), sent once per alert (`POST /api/alerts/{id}/vet-call`, `V27__vet_call.sql`); the `Veterinarian` answers once on the new `Vet calls` page (`/veterinarian/vet-calls`): continue light work, treat on site (stops the session, medication required) or emergency stop (stops the session and opens an incident report the vet takes, so the horse is blocked from training); the trainer and `Groom` are notified; banner on `Herd health`; e2e script now covers vet calls (about 130 steps)
> #### `Nov 3, 2026`
- `[12:14]` `ArichiAya` - Fix `Flow 3` recovery with two incident reports: `Recovery` now closes the open reports too, so a healthy horse is no longer blocked from training
- `[11:21]` `tqt195` - Frontend for the `Flow 1-3` fixes: system times (registered, lock issued, audit log, alerts...) no longer show 7 hours early (`parseServerTime` / `formatDateTime` in `horseApi.js`); `Club Manager` edits registration code and stall in the new `Club records` card on the horse page; lock level renamed `No heavy work` (light work up to 1,200 m); `Recovery` page explains when notes are required (backend in `f9ff9c9` by `Pandapo274`)
- `[11:18]` `Pandapo274` - Fix `Flow 1-3` business rules (scheduling, locks, recovery,pedigree
- `[09:35]` `tusandthatsall` - Fix `Flow 4` and `Flow 5`
> #### `Oct 30, 2026`
- `[11:05]` `tqt195` - Frontend for the access and withdrawal fixes: `Sign out` now calls `/api/auth/logout` so the server revokes the token (it used to stay valid for up to 8 hours); withdrawal screens tell the owner and the `Club Manager` that approval also cancels upcoming stable tasks and race entries (backend in `7a211fd` by `PandaPo274`)
- `[10:25]` `Pandapo274` - Fix staff access check and withdrawal side effects
- `[10:25]` `tqt195` - `Permissions` page no longer shows API endpoints to the `Club Manager` (function names and role access only); the matrix is still read from `@RequireRole` on the backend
- `[10:15]` `tqt195` - Add operating costs and live Club overview for `Club Manager`: new `Costs` page (`/api/expenses`) to record, filter and delete feeding, veterinary, training and other costs per horse, which feed the owner cost report; `Club overview` now reads real data from `/api/manager/dashboard` (horses, active plans, training locks, operating cost this quarter, prize money this season, weekly workload, herd health, open items) instead of hard-coded numbers; dashboard search opens the horse list filtered
- `[09:59]` `tqt195` - Fix tests and build warnings, record horse weight: `Veterinarian` enters the weight (kg) on the examination form, saved as the horse's current weight (30-1500 kg, not for pedigree records); replace the broken CRA sample test with `horseApi.test.js` (20 tests for rules copied from the backend); remove the BOM from 52 frontend files and fix the last ESLint warning, so `npm run build` has no warnings
> #### `Oct 29, 2026`
- `[18:55]` `ArichiAya` - Frontend for Flow 4 and 5: Groom stable map with daily tasks, feeding, incident reports with photo and supplies; Head Trainer stable care and races; Vet rations; Manager supplies and race results; Owner race history
> #### `Oct 27, 2026`
- `[12:59]` `Pandapo274` - Backend for Flow 4 and 5: rations, daily care tasks, supplies with restock alerts, stable incident reports with photo, race entries, results and yearly performance (`V18__race_result_rules.sql`)
- `[12:58]` `Pandapo274` - Remove the old duplicate preventive reminder scheduler (its notices were rejected by `CK_NOTIF_target`); due-session highlight on `Schedule` now shows on every screen size
- `[11:30]` `Pandapo274` - Vet flow: daily preventive-care reminders (`V17__preventive_reminder_log.sql`, notification digest for Vet + owner notices), Vet Preventive Care and Prescription pages; Health board and Preventive Care board
- `[11:08]` `tqt195` - Add `Audit Log` page for `Club Manager` (`/manager/audit-log`, read-only `GET /api/audit-logs`): filters, 24-hour security summary, readable action names, denied rows highlighted
- `[10:53]` `tqt195` - Real club stats on landing and login pages (public `GET /api/public/club-stats`: horses in training, active plans, races this season)
- `[10:48]` `tqt195` - Frontend for incident and trial run: `Groom` stops a session when the horse is unwell, `Vet` incident queue (take case, examine with report), `Head Trainer` marks trial runs with video, new `Horse Owner` `Training` page (plan, schedule, remarks, videos); mail is off by default again
- `[10:12]` `DevilJack422` - Rename incident/trial-run migration to V16, drop suspended-account guard
> #### `Oct 26, 2026`
- `[08:44]` `DevilJack422` - Add incident stop-session flow, trial-run video, lock stops running session (V14)
- `[10:00]` `tqt195` - Add `Permissions` page for `Club Manager` (`/manager/permissions`, `GET /api/permissions`): read-only role x function matrix built from the `@RequireRole` rules the backend enforces, plus the full API endpoint list
- `[07:22]` `tqt195` - Add Horse withdrawls: `Horse Owner` requests to withdraw a horse from its profile (`Leave the club`), `Club Manager` approves or rejects on the new `Withdrawals` page; approval cancels scheduled sessions (grooms are notified), ends training plans, releases the stall and marks the horse `WITHDRAWN`. Withdrawn horses are hidden from training plans, the health board, preventive care and the groom schedule. Owners can also cancel a pending registration request. Migration `V15__horse_withdrawal.sql`
- `[06:47]` `tqt195` - Fix manager permission: `Club Manager` changes staff role (Head Trainer / Vet / Groom) on the `Staff` page, staff accounts get a temporary password that must be changed at first sign-in (`V14__staff_must_change_password.sql`), head trainers are notified when a suspended or re-roled groom still has sessions to reassign
> #### `Oct 23, 2026`
- `[14:06]` `ArichiAya` - Fix Register `RBAC` v1
- `[10:25]` `tqt195` - Fix register account page: `Password` and `Repeat password` fields no longer stick together or overflow the card
> #### `Oct 22, 2026`
- `[17:49]` `tqt195` - Login with Gmail Account Frontend: Google button on the login page (`@react-oauth/google`), outcome notices (`REGISTERED`, `LINKED`), `frontend/.env.example` with `REACT_APP_GOOGLE_CLIENT_ID`
- `[17:00]` `[DevilJack422]` - Modified .env.example
- `[16:50]` `[DevilJack422]` - Add V13 migration for google login, Complete backend google login function
- `[15:19]` `tusandthatsall` - Fix notification function
- `[12:34]` `tusandthatsall` - Add backend codes for notification function
- `[11:57]` `tqt195` - Add demo photos for 18 horses and 22 accounts (`frontend/public/seed/`) and `V11__demo_photos.sql` to attach them by registration code / username
- `[10:34]` `tqt195` - Update `Staff` Page using English (role group titles); move `StaffPage.js`, `staff.css`, `staffApi.js` into `features/manager`
- `[10:16]` `tqt195` - Merge `Staff` tab for `Club Manager` with `tusandthatsall`: keep `features/staff` (with backend `/api/staff`), remove duplicate `features/manager/StaffPage`; shared sidebar menu for every role (`features/navigation`); `Groom` page uses real training sessions (start session, send heart rate)
- `[07:59]` `tqt195` - Add `Horse Owner` Dashboard with real data (`GET /api/owner/dashboard`), new training stats icons and numbers; add horse photo upload for `Horse Owner` (`V9__horse_photo.sql`, `POST/DELETE /api/horses/{id}/photo`); `Training Plan` horse list now skips pedigree records and allows `MONITOR`; rename `Academy` to `Club` in all displayed text (enum `ACADEMY` of `record_type` is kept)
> #### `Oct 21, 2026`
- `[19:19]` `tusandthatsall` - Add V7_spread_ownership.sql 
> #### `Oct 20, 2026`
- `[10:50]` `ArichiAya` - Debug session - Refactor format for the WHOLE `Frontend` thing, wihtout touching any logic behind it. (need fixing avatars, feat. `tqt195`)
- `[10:03]` `ArichiAya` - Debug session - `Trainer` Dashboard `Navbar`
> #### `Oct 17, 2026`
- `[23:22]` `ArichiAya` - Implement and refine the workflows for `UC9` (Training Plan Builder), `UC10` (Schedule Assignment), `UC11` (Live Session Monitor), `UC12` (Session Evaluation), `UC13` (Health Status Board), and `UC15` (2D Injury Marker).
> #### `Oct 16, 2026`
- `[11:16]` `Pandapo274` - Add `UC9`, `UC10`
> #### `Oct 15, 2026`
- `[09:50]` `DevilJack422` - Add `UC11`, `UC12` and `UC13`
> #### `Oct 13, 2026`
- `[11:10]` `tusandthatsall` - Add `UC15` and Add `UC16`
- `[10:41]` `tusandthatsall` - Add `UC18` and Adjust `UC14`
- `[10:37]` `tusandthatsall` - Add `UC14`
> #### `Oct 10, 2026`
- `[08:51]` `tusandthatsall` - Fix Profile Picture Bug
> #### `Oct 9, 2026`
- `[19:27]` `ArichiAya` - Revamping `UC4` and `UC5` For Frontend, But Needed Fixing For Avatar Bug (feat. `tusandthatsall`) 
- `[11:04]` `tusandthatsall` - Add `V4__profile_avatar_and_reset_token.sql`
- `[10:29]` `tusandthatsall` - Add `V3_more_demo_data.sql`
- `[00:52]` `DevilJack422` - Add `UC5` Backend Password
- `[00:25]` `ArichiAya` - Checking Conflict And Fixing Conflict, Needing Re-Testing
- `[00:17]` `tqt195` - Update Landing Page
- `[00:08]` `DevilJack422` - Add `UC4` Backend Profile
> #### `Oct 8, 2026`
- `[23:51]` `DevilJack422` - Add `UC3` Backend Logout
- `[23:21]` `ArichiAya` - Setting Up `UC7` and `UC8` Frontend Pages (Testing And Verifying Needed)
- `[21:05]` `Pandapo274` - Add `UC7` `UC8` Horse Management and Vitals (Merge from feature/uc7-uc8-horse-backend)
> #### `Oct 4, 2026`
- `[18:16]` `ArichiAya` - Initiated Setting Up For `UC1` For Frontend (Testing Needed Soon)
- `[16:28]` `tusandthatsall` - Add `UC6`
- `[15:57]` `tusandthatsall` - Update `V1_initial_schema.sql` and `Add V2_demo_data.sql`
- `[15:04]` `DevilJack422` - Add `UC2` Add Login with JWT and RBAC
- `[14:24]` `DevilJack422` - Add `UC1` Register Account API (`POST /api/auth/register`)
- `[12:52]` `tqt195` - Update Horse Owner Dashboard
- `[11:38]` `tqt195` - Landing Page added
- `[10:58]` `tqt195` - 5 Role Dashboard
- `[8:57]` `tqt195` - Access denied page

> #### `Oct 2, 2026`
- `[11:30]` `Pandapo274` - Update `application.properties`
- `[11:23]` `tqt195` - Login page updated
- `[11:03]` `tqt195` - Login page added
- `[09:00]` `ArichiAya` - Database Test Initialization, Successfully Test On Local Machine

> #### `Sep 29, 2026`
- `[11:30]` `ArichiAya` - Setup Test for Database SQLServer
- `[11:02]` `ArichiAya` - Update `README.md`
- `[10:56]` `ArichiAya` - Setup and Test for both Frontend and Backend Project
