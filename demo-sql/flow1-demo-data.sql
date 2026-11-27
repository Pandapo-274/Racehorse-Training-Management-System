/* =============================================================================
   Flow 1 DEMO data: horses and user accounts (main, migrations up to V59).

   Run in SSMS against the RTMS database right before the presentation, with
   the backend already started once on the current main (all migrations applied).
   Safe to run again: it puts every demo account, horse, session and care task
   back to the start of the script, so a rehearsal can be followed by the real demo.

   The happy case follows ONE horse, Morning Star: its rejected request is sent
   again with photo, stay and training dates, approved, signed, paid with payOS,
   and received with code, stall, Head Trainer Ha and a body condition score.
   River Song (stay ending in about 10 days, a confirmed race entry) and Golden Hour
   (stay ended, not renewed) show the end of a stay and the race rule. Quang is full
   (3/3 horses, 3/3 grooms), so he is hidden wherever a Head Trainer is chosen.

   Run it LESS THAN 4 HOURS before the demo: the running sessions last 5 hours,
   after that the backend closes them by itself and the backup steps
   (groom / Head Trainer leaving with a session running) have nothing to stop.

   Usernames lan.pn / minh.td / ha.vt / quang.hd / phuc.hv / khoa.lv / dung.nt (an.nv is only
   for the backup step), registration codes DM-*, stalls Z-*. Password of every
   demo account: 123456. Seed account used as-is: hoa.tm (Club Manager).

   HISTORY  Old Glory left the club (approved withdrawal), two pedigree records,
            Morning Star's registration rejected two weeks ago,
            yesterday's reviewed session and finished feeding.
   CURRENT  Ha, a Head Trainer with no horses yet (Morning Star's, Step 1.2 / 1.4);
            Lan's three horses at the club, all trained by Head Trainer Quang;
            Minh's stallion; a pending request; Phuc (Quang's groom) is in the
            middle of a session with Little Spark and has care tasks for today;
            Khoa is training Golden Hour right now (backup steps);
            khoa.lv already has 4 wrong sign-ins in a row.
   FUTURE   Phuc's sessions later today, tomorrow and the day after; a training
            plan for River Song starting next week, still waiting for Lan's
            approval (blocks the sale of River Song).
   ============================================================================= */
-- The database the backend uses (spring.datasource.url ... databaseName=rtms).
-- Without this, SSMS often runs the script in "master", where APP_USER does not exist.
USE rtms;
GO

SET NOCOUNT ON;
SET XACT_ABORT ON;

IF OBJECT_ID('APP_USER', 'U') IS NULL
    THROW 50899, N'APP_USER not found: this is not the RTMS database. Pick the database the backend uses.', 1;

IF COL_LENGTH('APP_USER', 'locked_until') IS NULL OR COL_LENGTH('APP_USER', 'tokens_valid_after') IS NULL
   OR COL_LENGTH('APP_USER', 'trainer_id') IS NULL OR COL_LENGTH('TRAINING_SESSION', 'review_status') IS NULL
   OR COL_LENGTH('HORSE_REQUEST', 'sire_id') IS NULL OR COL_LENGTH('INCIDENT_REPORT', 'handover_by') IS NULL
    THROW 50910, N'Migrations are missing (V45, V46): start the backend on main with V45 and V46 once, then run this script.', 1;

IF COL_LENGTH('HORSE_REQUEST', 'photo_url') IS NULL OR OBJECT_ID('HORSE_CONTRACT', 'U') IS NULL
   OR COL_LENGTH('HORSE_REQUEST', 'training_start') IS NULL
   OR COL_LENGTH('HORSE_REQUEST', 'insurer_name') IS NULL
   OR COL_LENGTH('RACE_REGISTRATION', 'withdraw_advice') IS NULL
   OR COL_LENGTH('RACE', 'organizer') IS NULL
   OR OBJECT_ID('STALL', 'U') IS NULL
   OR COL_LENGTH('HORSE_CONTRACT', 'takes_over_contract_id') IS NULL
   OR COL_LENGTH('HORSE_WITHDRAWAL', 'leave_on') IS NULL
   OR OBJECT_ID('OWNER_CONTACT', 'U') IS NULL
    THROW 50911, N'Migrations V48..V63 are missing: start the backend on main once, then run this script.', 1;

-- Stalls the demo uses (Z-02..Z-04, Z-06 now; Z-07, Z-08, Z-09 typed live).
-- If another horse already stands in one of them, stop and say which, instead of
-- the bare "duplicate key ... UX_HORSE_stall" error.
DECLARE @taken NVARCHAR(1000) = (
    SELECT STRING_AGG(CONCAT(horse_name, N' (', registration_code, N') in ', stall_no), N', ')
    FROM HORSE
    WHERE stall_no IN ('Z-02','Z-03','Z-04','Z-06','Z-07','Z-08','Z-09')
      AND registration_code NOT LIKE 'DM-%'
      -- a horse of Lan / Minh left by a rehearsal (Morning Star) is removed below
      AND owner_id NOT IN (SELECT u.user_id FROM APP_USER u WHERE u.username IN ('lan.pn', 'minh.td')));
IF @taken IS NOT NULL
BEGIN
    DECLARE @msg NVARCHAR(2048) = N'Demo stalls are taken by other horses: ' + @taken
        + N'. Move those horses, or replace Z- with another unused barn letter in this script and the demo doc.';
    THROW 50913, @msg, 1;
END

BEGIN TRAN;

DECLARE @pw      VARCHAR(100) = '$2b$10$W0zKT4yUrmErFfpx9KvfN.QSGp0t7eA3ueZpY8hnJjsiwfJy.fQ66'; -- 123456
DECLARE @manager INT = (SELECT user_id FROM APP_USER WHERE username = 'hoa.tm');
IF @manager IS NULL
    THROW 50911, N'Seed account hoa.tm not found.', 1;

-- Local clock for session times (the backend compares them with its local time),
-- UTC for started_at / ended_at / completed_at (the backend writes those in UTC).
DECLARE @now   DATETIME2 = GETDATE();
DECLARE @today DATE      = CAST(GETDATE() AS DATE);
DECLARE @utc   DATETIME2 = SYSUTCDATETIME();

/* ---------------------------------------------------------------- 0. reset */

-- The account created in the backup step: rename the old one so the name is free again.
UPDATE APP_USER
SET username = LEFT(username + '.old' + CAST(user_id AS VARCHAR(10)), 50),
    email    = LEFT('old' + CAST(user_id AS VARCHAR(10)) + '.' + email, 100),
    status   = 'SUSPENDED'
WHERE username = 'an.nv';

DECLARE @dmOwners TABLE (user_id INT PRIMARY KEY);
INSERT @dmOwners SELECT user_id FROM APP_USER WHERE username IN ('lan.pn', 'minh.td');
-- Horses left over from an older version of this script (Silver Comet, Thunder Bay)
-- are removed below with the rest of the DM horses that are not in the list.
DECLARE @dmHorses TABLE (horse_id INT PRIMARY KEY);
-- Also every horse of Lan / Minh: Morning Star is received live with the suggested TM- code.
INSERT @dmHorses SELECT horse_id FROM HORSE
                 WHERE registration_code LIKE 'DM-%' OR owner_id IN (SELECT user_id FROM @dmOwners);
DECLARE @dmSessions TABLE (session_id INT PRIMARY KEY);
INSERT @dmSessions SELECT session_id FROM TRAINING_SESSION WHERE horse_id IN (SELECT horse_id FROM @dmHorses);

-- Alerts, vet calls or incident reports on a DM session come from a rehearsal of
-- another flow. Stop with a clear message instead of a foreign-key error halfway.
IF EXISTS (SELECT 1 FROM THRESHOLD_ALERT WHERE session_id IN (SELECT session_id FROM @dmSessions))
   OR EXISTS (SELECT 1 FROM VET_CALL WHERE session_id IN (SELECT session_id FROM @dmSessions))
   OR EXISTS (SELECT 1 FROM INCIDENT_REPORT WHERE session_id IN (SELECT session_id FROM @dmSessions))
    THROW 50912, N'Alerts, vet calls or incident reports exist for DM training sessions (made outside the Flow 1 demo). Delete them, or restore the database, then run this script again.', 1;

DELETE FROM TRAINING_SESSION WHERE session_id IN (SELECT session_id FROM @dmSessions);
DELETE FROM DAILY_TASK       WHERE horse_id IN (SELECT horse_id FROM @dmHorses);
-- V62: a withdrawal points at the contract it ended, so it goes before the contracts.
DELETE FROM HORSE_WITHDRAWAL WHERE horse_id IN (SELECT horse_id FROM @dmHorses);
-- V49: contracts point at the requests, so they go first (a rehearsal leaves Morning Star's).
UPDATE HORSE_CONTRACT SET renews_contract_id = NULL, takes_over_contract_id = NULL
WHERE owner_id IN (SELECT user_id FROM @dmOwners) OR horse_id IN (SELECT horse_id FROM @dmHorses);
DELETE FROM HORSE_CONTRACT
WHERE owner_id IN (SELECT user_id FROM @dmOwners) OR horse_id IN (SELECT horse_id FROM @dmHorses);
-- Race entries of the demo horses and the demo race (point 14).
DELETE FROM RACE_REGISTRATION
WHERE horse_id IN (SELECT horse_id FROM @dmHorses)
   OR race_id IN (SELECT race_id FROM RACE WHERE race_name = N'Tracen Autumn Mile');
DELETE FROM RACE WHERE race_name = N'Tracen Autumn Mile';
-- V63: the demo owners' people are seeded again below.
DELETE FROM OWNER_CONTACT    WHERE owner_id IN (SELECT user_id FROM @dmOwners);
DELETE FROM HORSE_REQUEST    WHERE owner_id IN (SELECT user_id FROM @dmOwners)
                                OR horse_id IN (SELECT horse_id FROM @dmHorses);
-- V17: the nightly care-reminder job logs every horse it reminded about (Morning Star too).
DELETE FROM PREVENTIVE_REMINDER_LOG WHERE horse_id IN (SELECT horse_id FROM @dmHorses);
DELETE FROM PLAN_PHASE       WHERE plan_id IN (SELECT plan_id FROM TRAINING_PLAN
                                               WHERE horse_id IN (SELECT horse_id FROM @dmHorses));
DELETE FROM TRAINING_PLAN    WHERE horse_id IN (SELECT horse_id FROM @dmHorses);
UPDATE HORSE SET sire_id = NULL, dam_id = NULL, trainer_id = NULL
WHERE horse_id IN (SELECT horse_id FROM @dmHorses);
-- Horses created during the demo (Morning Star, received with a TM- or DM- code).
DELETE FROM HORSE
WHERE horse_id IN (SELECT horse_id FROM @dmHorses)
  AND registration_code NOT IN ('DM-02','DM-04','DM-05','DM-06','DM-07','DM-P1','DM-P2');

/* ------------------------------------------------------- 0b. the demo barn
   V60 lists the club's stalls (barns A, B, C). The demo horses live in barn Z,
   so it goes on the stable plan too. Morning Star gets the first free stall
   the receive form suggests (barn A first). */
;WITH z AS (SELECT TOP (10) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS k FROM sys.all_objects)
INSERT INTO STALL (stall_no, barn, stall_number)
SELECT 'Z-' + RIGHT('0' + CAST(k AS VARCHAR(3)), 2), 'Z', k FROM z
WHERE NOT EXISTS (SELECT 1 FROM STALL s WHERE s.stall_no = 'Z-' + RIGHT('0' + CAST(z.k AS VARCHAR(3)), 2));

/* ------------------------------------------------------------- 1. accounts */
DECLARE @users TABLE (username VARCHAR(50), role_name VARCHAR(50), full_name NVARCHAR(100),
                      email VARCHAR(100), phone VARCHAR(20), failed INT);
INSERT @users VALUES
  ('lan.pn',   'HORSE_OWNER',  N'Pham Ngoc Lan',   'lan.pn@tracen.test',   '0912000101', 0),
  ('minh.td',  'HORSE_OWNER',  N'Tran Duc Minh',   'minh.td@tracen.test',  '0912000102', 0),
  -- Morning Star's Head Trainer at approval (1.2); suspended in 1.4.
  ('ha.vt',    'HEAD_TRAINER', N'Vu Thi Ha',       'ha.vt@tracen.test',    '0912000106', 0),
  ('quang.hd', 'HEAD_TRAINER', N'Hoang Duc Quang', 'quang.hd@tracen.test', '0912000104', 0),
  ('phuc.hv',  'GROOM',        N'Hoang Van Phuc',  'phuc.hv@tracen.test',  '0912000105', 0),
  -- CURRENT: 4 wrong passwords already, so one more locks him live in Step 2.
  ('khoa.lv',  'GROOM',        N'Le Van Khoa',     'khoa.lv@tracen.test',  '0912000103', 4),
  -- Quang's third groom: his team is full, so the Staff page hides him (point 5).
  ('dung.nt',  'GROOM',        N'Nguyen Thi Dung', 'dung.nt@tracen.test',  '0912000107', 0);

UPDATE u
SET role_id = r.role_id, password_hash = @pw, full_name = v.full_name, email = v.email, phone = v.phone,
    status = 'ACTIVE', must_change_password = 0, avatar_url = NULL, google_sub = NULL,
    failed_login_count = v.failed, locked_until = NULL, tokens_valid_after = NULL,
    trainer_id = NULL, max_horses = CASE WHEN v.role_name = 'HEAD_TRAINER' THEN 10 ELSE NULL END
FROM APP_USER u JOIN @users v ON v.username = u.username JOIN ROLE r ON r.role_name = v.role_name;

INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone, status, failed_login_count, max_horses)
SELECT r.role_id, v.username, @pw, v.full_name, v.email, v.phone, 'ACTIVE', v.failed,
       CASE WHEN v.role_name = 'HEAD_TRAINER' THEN 10 ELSE NULL END
FROM @users v JOIN ROLE r ON r.role_name = v.role_name
WHERE NOT EXISTS (SELECT 1 FROM APP_USER u WHERE u.username = v.username);

DECLARE @lan   INT = (SELECT user_id FROM APP_USER WHERE username = 'lan.pn');
DECLARE @minh  INT = (SELECT user_id FROM APP_USER WHERE username = 'minh.td');
DECLARE @quang INT = (SELECT user_id FROM APP_USER WHERE username = 'quang.hd');
DECLARE @phuc  INT = (SELECT user_id FROM APP_USER WHERE username = 'phuc.hv');
DECLARE @khoa  INT = (SELECT user_id FROM APP_USER WHERE username = 'khoa.lv');
DECLARE @dung  INT = (SELECT user_id FROM APP_USER WHERE username = 'dung.nt');

-- Quang's team: Phuc, Khoa and Dung (3/3, full). Ha has no team.
UPDATE APP_USER SET trainer_id = @quang WHERE user_id IN (@phuc, @khoa, @dung);
-- Quang manages River Song, Golden Hour and Little Spark: with a limit of 3 he is full,
-- so only Ha is offered to Lan (preferred trainer) and to the manager (receive).
UPDATE APP_USER SET max_horses = 3 WHERE user_id = @quang;

/* --------------------------------------------------------------- 2. horses */
DECLARE @horses TABLE (code VARCHAR(30), owner_id INT, horse_name NVARCHAR(100), gender VARCHAR(10),
                       dob DATE, color NVARCHAR(50), weight DECIMAL(6,2), stall VARCHAR(20),
                       record_type VARCHAR(20), withdrawn_at DATETIME2, trainer_id INT);
INSERT @horses VALUES
  -- HISTORY
  ('DM-02', @lan,  N'Old Glory',    'GELDING',  '1999-03-20', N'Chestnut', 455.0, NULL,   'ACADEMY',  '2024-12-01', NULL),
  ('DM-P1', @minh, N'Old Monarch',  'STALLION', '2006-05-01', N'Bay',      NULL,  NULL,   'PEDIGREE', NULL,         NULL),
  ('DM-P2', @minh, N'Lady Grace',   'MARE',     '2008-02-14', N'Bay',      NULL,  NULL,   'PEDIGREE', NULL,         NULL),
  -- CURRENT: Lan's horses, all with Head Trainer Quang
  ('DM-04', @lan,  N'River Song',   'FILLY',    '2022-05-21', N'Bay',      450.0, 'Z-02', 'ACADEMY',  NULL,         @quang),
  ('DM-05', @lan,  N'Golden Hour',  'FILLY',    '2023-04-02', N'Chestnut', 431.0, 'Z-03', 'ACADEMY',  NULL,         @quang),
  ('DM-06', @lan,  N'Little Spark', 'COLT',     '2022-09-30', N'Bay',      466.0, 'Z-04', 'ACADEMY',  NULL,         @quang),
  ('DM-07', @minh, N'Night Rider',  'STALLION', '2014-01-17', N'Black',    512.0, 'Z-06', 'ACADEMY',  NULL,         NULL);

UPDATE h
SET owner_id = v.owner_id, horse_name = v.horse_name, breed = 'Thoroughbred', horse_gender = v.gender,
    date_of_birth = v.dob, color = v.color, weight = v.weight, stall_no = NULL, status = 'ELIGIBLE',
    record_type = v.record_type,
    activity_status = CASE WHEN v.withdrawn_at IS NULL THEN 'ACTIVE' ELSE 'WITHDRAWN' END,
    withdrawn_at = v.withdrawn_at, sire_id = NULL, dam_id = NULL, photo_url = NULL,
    trainer_id = v.trainer_id, updated_at = SYSUTCDATETIME()
FROM HORSE h JOIN @horses v ON v.code = h.registration_code;

INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender, date_of_birth,
                   color, weight, stall_no, status, record_type, activity_status, withdrawn_at, trainer_id)
SELECT v.owner_id, v.horse_name, v.code, 'Thoroughbred', v.gender, v.dob, v.color, v.weight, NULL,
       'ELIGIBLE', v.record_type, CASE WHEN v.withdrawn_at IS NULL THEN 'ACTIVE' ELSE 'WITHDRAWN' END,
       v.withdrawn_at, v.trainer_id
FROM @horses v
WHERE NOT EXISTS (SELECT 1 FROM HORSE h WHERE h.registration_code = v.code);

UPDATE h SET stall_no = v.stall
FROM HORSE h JOIN @horses v ON v.code = h.registration_code
WHERE v.stall IS NOT NULL;

DECLARE @glory  INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DM-02');
DECLARE @river  INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DM-04');
DECLARE @spark  INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DM-06');

/* ------------------------------------------- 3. HISTORY: past withdrawal */
INSERT INTO HORSE_WITHDRAWAL (horse_id, requested_by, reason, status, created_at,
                              reviewed_by, reviewed_at, review_note, released_stall,
                              cancelled_sessions, ended_plans)
VALUES (@glory,  @lan, N'Retired after his last season',             'APPROVED', '2024-11-25',
        @manager, '2024-12-01', N'Approved', 'Z-08', 0, 0);

/* ------------------------- 4. FUTURE: plan starting next week, needs Lan */
INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
VALUES (@river, @quang, N'River Song - winter conditioning',
        DATEADD(DAY, 7, @today), DATEADD(DAY, 70, @today), 'PENDING_APPROVAL');

-- One phase, so Lan's plan-approval screen shows real content if anyone opens it.
INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                        sessions_per_week, heart_rate_ceiling, start_date, end_date)
VALUES (SCOPE_IDENTITY(), 1, N'Base conditioning', 1600, N'MODERATE', 'TURF', 4, 190,
        DATEADD(DAY, 7, @today), DATEADD(DAY, 70, @today));

/* ------- 5. Little Spark's running plan: Phuc's work (backup: groom suspended) */
INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
VALUES (@spark, @quang, N'Little Spark - sprint block',
        DATEADD(DAY, -14, @today), DATEADD(DAY, 42, @today), 'ACTIVE');
DECLARE @sparkPlan INT = SCOPE_IDENTITY();

INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                        sessions_per_week, heart_rate_ceiling, start_date, end_date)
VALUES (@sparkPlan, 1, N'Speed work', 1200, N'HIGH', 'DIRT', 4, 200,
        DATEADD(DAY, -14, @today), DATEADD(DAY, 42, @today));
DECLARE @sparkPhase INT = SCOPE_IDENTITY();

DECLARE @tomorrow7 DATETIME2 = DATEADD(HOUR, 7, CAST(DATEADD(DAY, 1, @today) AS DATETIME2));

INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance,
                              planned_duration, actual_distance, duration_minutes, avg_heart_rate,
                              peak_heart_rate, recovery_heart_rate, peak_velocity, evaluation, remarks,
                              evaluated_by, status, started_at, ended_at, review_status)
VALUES
  -- HISTORY: yesterday, done and reviewed by Quang
  (@sparkPhase, @spark, @phuc, DATEADD(HOUR, 7, CAST(DATEADD(DAY, -1, @today) AS DATETIME2)), 1200,
   60, 1200, 55, 168, 186, 112, 15.80, 'GOOD', N'Clean break, held the pace to the line',
   @quang, 'COMPLETED', DATEADD(DAY, -1, @utc), DATEADD(MINUTE, 55, DATEADD(DAY, -1, @utc)), 'REVIEWED'),
  -- CURRENT: Phuc started this one 20 minutes ago (5-hour window)
  (@sparkPhase, @spark, @phuc, DATEADD(MINUTE, -20, @now), 1200,
   300, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   NULL, 'RUNNING', DATEADD(MINUTE, -20, @utc), NULL, 'NONE'),
  -- FUTURE: later today, tomorrow and the day after
  (@sparkPhase, @spark, @phuc, DATEADD(HOUR, 2, @now), 1200,
   60, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'SCHEDULED', NULL, NULL, 'NONE'),
  (@sparkPhase, @spark, @phuc, @tomorrow7, 1200,
   60, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'SCHEDULED', NULL, NULL, 'NONE'),
  (@sparkPhase, @spark, @phuc, DATEADD(DAY, 1, @tomorrow7), 1400,
   60, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'SCHEDULED', NULL, NULL, 'NONE');

/* ------ 5b. Golden Hour: Khoa is training it now (backup: Quang suspended) */
DECLARE @golden INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DM-05');
INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
VALUES (@golden, @quang, N'Golden Hour - base fitness',
        DATEADD(DAY, -7, @today), DATEADD(DAY, 49, @today), 'ACTIVE');
INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                        sessions_per_week, heart_rate_ceiling, start_date, end_date)
VALUES (SCOPE_IDENTITY(), 1, N'Base fitness', 1000, N'LIGHT', 'TURF', 3, 185,
        DATEADD(DAY, -7, @today), DATEADD(DAY, 49, @today));
DECLARE @goldenPhase INT = SCOPE_IDENTITY();
INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance,
                              planned_duration, status, started_at, review_status)
VALUES (@goldenPhase, @golden, @khoa, DATEADD(MINUTE, -15, @now), 1000,
        300, 'RUNNING', DATEADD(MINUTE, -15, @utc), 'NONE');

/* ---------------------------------------- 6. care tasks for Little Spark */
INSERT INTO DAILY_TASK (horse_id, task_type, assigned_to, task_date, is_completed, completed_at, completed_by, note)
VALUES
  -- HISTORY: yesterday's feeding, done
  (@spark, 'FEEDING',  @phuc, DATEADD(DAY, -1, @today), 1, DATEADD(DAY, -1, @utc), @phuc, N'Morning feed'),
  -- CURRENT: today's feeding and stall cleaning, not done yet
  (@spark, 'FEEDING',  @phuc, @today, 0, NULL, NULL, N'Evening feed'),
  (@spark, 'CLEANING', @phuc, @today, 0, NULL, NULL, N'Stall Z-04');

/* ------------------------------------------ 6b. the owners' people (V63)
   Clause 8: the second person is picked from the owner's own list, the insurer from
   the club's list (V63 seeds Bao Viet Insurance, 1900 558899). */
INSERT INTO OWNER_CONTACT (owner_id, full_name, phone, relationship) VALUES
  (@lan,  N'Pham Van Long', '0912000202', N'brother'),
  (@minh, N'Tran Thi Mai',  '0912000201', N'sister');

/* -------------------------------------------------- 7. registration requests */
/* V48/V49 (after the review): a club-horse request carries weight, height, aptitude,
   the length of the stay and a photo. Blue Lagoon uses a seeded photo (a grey horse,
   matching its description) so the manager can approve it and send the contract. */
INSERT INTO HORSE_REQUEST (owner_id, horse_name, breed, horse_gender, date_of_birth, color, note,
                           record_type, status, created_at, reviewed_by, reviewed_at, review_note,
                           photo_url, weight, height_cm, surface, distance_pref, running_style,
                           preferred_start, training_weeks, contract_months,
                           training_start, training_end,
                           insurer_name, insurer_phone, emergency_name, emergency_phone)
VALUES
  -- HISTORY: rejected two weeks ago; Lan sends it again in Step 1 (photo, dates, insurer added live)
  (@lan, N'Morning Star', 'Thoroughbred', 'FILLY', NULL, N'Bay', N'Bought at the spring yearling sale',
   'ACADEMY', 'REJECTED', DATEADD(DAY, -15, @utc), @manager, DATEADD(DAY, -14, @utc),
   N'Date of birth is missing. Add it and send the request again',
   NULL, 455.0, 157.0, 'TURF', 'MEDIUM', 'STALKER', NULL, 4, 1, NULL, NULL,
   NULL, NULL, NULL, NULL),
  -- CURRENT: complete, waiting in the manager's queue (backup if Step 1 fails live)
  (@minh, N'Blue Lagoon', 'Thoroughbred', 'COLT', '2023-07-11', N'Grey', NULL,
   'ACADEMY', 'PENDING', DATEADD(HOUR, -6, @utc), NULL, NULL, NULL,
   '/seed/horses/TM-0298.webp', 468.0, 158.5, 'TURF', 'MEDIUM', 'FRONT', @today, 4, 1,
   @today, DATEADD(DAY, 28, @today),
   N'Bao Viet Insurance', '1900 558899', N'Tran Thi Mai', '0912000201'),
  -- CURRENT: the photo upload failed, so the manager cannot approve it (Step 2, bad case)
  (@minh, N'Sea Breeze', 'Arabian', 'MARE', '2021-04-02', N'Grey', N'Photo to follow',
   'ACADEMY', 'PENDING', DATEADD(HOUR, -3, @utc), NULL, NULL, NULL,
   NULL, 430.0, 152.0, 'DIRT', 'LONG', 'STALKER', @today, 4, 1,
   @today, DATEADD(DAY, 28, @today),
   N'Bao Viet Insurance', '1900 558899', N'Tran Thi Mai', '0912000201');

/* ------------------------------- 7b. stays of Lan's horses (points 4 and 14)
   River Song: 1-month stay ending in about 10 days (warning colour, renew button).
   Golden Hour: 1-month stay that ended two days ago and was not renewed (EXPIRED):
   no new training plan, no new race entry, listed under "Ended - follow up".
   The approved request behind each contract is part of the history. */
DECLARE @terms NVARCHAR(MAX) = N'Seeded for the Flow 1 demo: a 1-month boarding contract with Tracen Club, '
    + N'signed online and paid with payOS before the demo. Contracts sent from the app carry the full text '
    + N'(fee, insurance, injury risk, training commitment, renewal and end of stay).';
DECLARE @termsSha CHAR(64) = LOWER(CONVERT(CHAR(64), HASHBYTES('SHA2_256', @terms), 2));
DECLARE @riverStart DATE = DATEADD(MONTH, -1, DATEADD(DAY, 10, @today));
DECLARE @riverEnd   DATE = DATEADD(MONTH, 1, @riverStart);
DECLARE @goldStart  DATE = DATEADD(MONTH, -1, DATEADD(DAY, -2, @today));
DECLARE @goldEnd    DATE = DATEADD(MONTH, 1, @goldStart);

INSERT INTO HORSE_REQUEST (owner_id, horse_name, breed, horse_gender, date_of_birth, color, note,
                           record_type, status, created_at, reviewed_by, reviewed_at, review_note, horse_id,
                           weight, height_cm, surface, distance_pref, running_style,
                           preferred_start, training_weeks, contract_months, training_start, training_end,
                           insurer_name, insurer_phone, emergency_name, emergency_phone)
VALUES
  (@lan, N'River Song', 'Thoroughbred', 'FILLY', '2022-05-21', N'Bay', NULL,
   'ACADEMY', 'APPROVED', DATEADD(DAY, -3, CAST(@riverStart AS DATETIME2)), @manager,
   DATEADD(DAY, -2, CAST(@riverStart AS DATETIME2)), N'Approved', @river,
   450.0, 160.0, 'TURF', 'MEDIUM', 'STALKER', @riverStart, 4, 1, @riverStart, DATEADD(DAY, 28, @riverStart),
   N'Bao Viet Insurance', '1900 558899', N'Pham Van Long', '0912000202'),
  (@lan, N'Golden Hour', 'Thoroughbred', 'FILLY', '2023-04-02', N'Chestnut', NULL,
   'ACADEMY', 'APPROVED', DATEADD(DAY, -3, CAST(@goldStart AS DATETIME2)), @manager,
   DATEADD(DAY, -2, CAST(@goldStart AS DATETIME2)), N'Approved', @golden,
   431.0, 155.0, 'TURF', 'SPRINT', 'FRONT', @goldStart, 4, 1, @goldStart, DATEADD(DAY, 28, @goldStart),
   N'Bao Viet Insurance', '1900 558899', N'Pham Van Long', '0912000202');

INSERT INTO HORSE_CONTRACT (request_id, horse_id, owner_id, months, training_weeks, monthly_fee, discount_pct,
                            total_amount, planned_start, start_date, end_date, training_start, training_end,
                            terms_snapshot, terms_sha256, status, sent_by, sent_at, respond_by,
                            signed_name, signed_at, signed_ip, signed_sha256,
                            paid_at, paid_amount, payment_ref, received_by,
                            insurer_name, insurer_phone, emergency_name, emergency_phone)
SELECT r.request_id, r.horse_id, @lan, 1, 4, 1000, 0, 1000, r.preferred_start,
       r.preferred_start, CASE WHEN r.horse_id = @river THEN @riverEnd ELSE @goldEnd END,
       r.training_start, r.training_end,
       @terms, @termsSha, CASE WHEN r.horse_id = @river THEN 'ACTIVE' ELSE 'EXPIRED' END,
       @manager, r.reviewed_at, DATEADD(DAY, 7, CAST(r.reviewed_at AS DATE)),
       N'Pham Ngoc Lan', DATEADD(HOUR, 2, r.reviewed_at), '127.0.0.1', @termsSha,
       DATEADD(HOUR, 3, r.reviewed_at), 1000, N'DEMO-SEED', @manager,
       r.insurer_name, r.insurer_phone, r.emergency_name, r.emergency_phone
FROM HORSE_REQUEST r
WHERE r.owner_id = @lan AND r.status = 'APPROVED' AND r.horse_id IN (@river, @golden);

-- What the manager recorded when each horse arrived (Stay at the club card).
UPDATE HORSE SET height_cm = 160.0, bcs = 5, surface = 'TURF', distance_pref = 'MEDIUM', running_style = 'STALKER'
WHERE horse_id = @river;
UPDATE HORSE SET height_cm = 155.0, bcs = 6, surface = 'TURF', distance_pref = 'SPRINT', running_style = 'FRONT'
WHERE horse_id = @golden;

/* ------------------------------------------- 7c. point 14: a confirmed race entry
   Lan entered River Song herself (the owner registers); Quang, her Head Trainer,
   can only advise a withdrawal. No entry fee, so withdrawing it moves no money. */
INSERT INTO RACE (race_name, race_date, distance_m, venue, entry_deadline, entry_fee, max_entries,
                  status, created_by, organizer)
VALUES (N'Tracen Autumn Mile', DATEADD(DAY, 21, @today), 1600, N'Tracen Club track',
        DATEADD(DAY, 14, @today), NULL, 12, 'OPEN', @manager, N'Tracen Club');
DECLARE @raceId INT = SCOPE_IDENTITY();

INSERT INTO RACE_REGISTRATION (horse_id, race_name, race_date, registered_by, race_id,
                               approval_status, owner_decision_at)
VALUES (@river, N'Tracen Autumn Mile', DATEADD(DAY, 21, @today), @lan, @raceId,
        'APPROVED', DATEADD(DAY, -1, @utc));

COMMIT;

/* ---------------------------------------------------------- 8. check */
SELECT u.username, r.role_name, u.status, u.failed_login_count, t.username AS head_trainer, u.max_horses
FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
LEFT JOIN APP_USER t ON t.user_id = u.trainer_id
WHERE u.username IN ('lan.pn', 'minh.td', 'ha.vt', 'quang.hd', 'phuc.hv', 'khoa.lv', 'dung.nt')
ORDER BY u.username;

SELECT h.registration_code, h.horse_name, o.username AS owner, h.record_type,
       h.activity_status, h.stall_no, t.username AS head_trainer
FROM HORSE h JOIN APP_USER o ON o.user_id = h.owner_id
LEFT JOIN APP_USER t ON t.user_id = h.trainer_id
WHERE h.registration_code LIKE 'DM-%'
ORDER BY h.registration_code;

-- Expected: Little Spark 1 COMPLETED, 1 RUNNING, 3 SCHEDULED (phuc.hv),
-- Golden Hour 1 RUNNING (khoa.lv).
SELECT h.horse_name, s.status, s.session_date, s.planned_duration, g.username AS groom
FROM TRAINING_SESSION s JOIN APP_USER g ON g.user_id = s.assigned_to
JOIN HORSE h ON h.horse_id = s.horse_id
WHERE s.horse_id IN (@spark, @golden)
ORDER BY h.horse_name, s.session_date;

-- Expected: River Song ACTIVE (about 10 days left), Golden Hour EXPIRED.
SELECT h.horse_name, c.status, c.start_date, c.end_date, DATEDIFF(DAY, @today, c.end_date) AS days_left
FROM HORSE_CONTRACT c JOIN HORSE h ON h.horse_id = c.horse_id
WHERE c.owner_id = @lan
ORDER BY h.horse_name;
