# Racehorse-Training-Management-System
### Last Commited: `Oct 27, 2026` at `07:22` by `tqt195`

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
> #### `Oct 27, 2026`
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
