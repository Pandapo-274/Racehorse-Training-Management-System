/* =============================================================================
   V30 - Seed 10 Ready-to-Race Horses (Trial Run & Vaccination).

   PROBLEM
   All horses lacked recent trial runs or vaccination records within the
   30-day window, triggering warnings that disqualified them from racing.

   FIX
   For 10 active club horses:
   1. Ensure activity_status = 'ACTIVE' and status = 'ELIGIBLE'.
   2. Clear any active training lock or open incident report.
   3. Ensure they have a valid, up-to-date VACCINATION in MEDICAL_RECORD.
   4. Ensure an active TRAINING_PLAN & PLAN_PHASE exist.
   5. Ensure they have a completed TRIAL_RUN session within the last 7 days.
   ============================================================================= */

DECLARE @vet INT = (SELECT TOP 1 user_id FROM APP_USER WHERE role_id = (SELECT role_id FROM ROLE WHERE role_name = 'VETERINARIAN'));
DECLARE @trainer INT = (SELECT TOP 1 user_id FROM APP_USER WHERE role_id = (SELECT role_id FROM ROLE WHERE role_name = 'HEAD_TRAINER'));
DECLARE @groom INT = (SELECT TOP 1 user_id FROM APP_USER WHERE role_id = (SELECT role_id FROM ROLE WHERE role_name = 'GROOM'));

-- 1. Chon 10 con ngua phong do cao cua hoc vien (tuoi tu 2 den 10)
DECLARE @Horses TABLE (horse_id INT PRIMARY KEY);
INSERT INTO @Horses (horse_id)
SELECT TOP 10 horse_id
FROM HORSE
WHERE record_type = 'ACADEMY' AND activity_status = 'ACTIVE'
  AND DATEDIFF(YEAR, date_of_birth, GETDATE()) BETWEEN 2 AND 10
ORDER BY horse_id;

-- Cap nhat trang thai suc khoe dat chuan
UPDATE HORSE
SET status = 'ELIGIBLE'
WHERE horse_id IN (SELECT horse_id FROM @Horses);

-- Xoa bo khoa tap cu neu co tren 10 con nay
DELETE FROM TRAINING_LOCK WHERE horse_id IN (SELECT horse_id FROM @Horses);

-- Dong cac su co mo neu co tren 10 con nay
UPDATE INCIDENT_REPORT
SET status = 'EXAMINED'
WHERE horse_id IN (SELECT horse_id FROM @Horses) AND status <> 'EXAMINED';

-- 2. Cap nhat / Bo sung tiem phong (VACCINATION) con han 6 thang
INSERT INTO MEDICAL_RECORD (horse_id, vet_id, record_type, exam_date, diagnosis, next_due_date)
SELECT h.horse_id, @vet, 'VACCINATION', DATEADD(DAY, -7, GETDATE()), N'Tiem phong cum dinh ky dat tieu chuan thi dau', DATEADD(DAY, 180, GETDATE())
FROM @Horses h
WHERE NOT EXISTS (
    SELECT 1 FROM MEDICAL_RECORD r
    WHERE r.horse_id = h.horse_id AND r.record_type = 'VACCINATION' AND r.next_due_date >= CAST(GETDATE() AS DATE)
);

-- 3. Dam bao moi ngua deu co giao an tap luyen (TRAINING_PLAN) va giai doan (PLAN_PHASE)
INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
SELECT h.horse_id, @trainer, N'Giao an tap luyen - ' + (SELECT horse_name FROM HORSE WHERE horse_id = h.horse_id),
       DATEADD(DAY, -30, CAST(GETDATE() AS DATE)), DATEADD(DAY, 60, CAST(GETDATE() AS DATE)), 'ACTIVE'
FROM @Horses h
WHERE NOT EXISTS (
    SELECT 1 FROM TRAINING_PLAN tp WHERE tp.horse_id = h.horse_id
);

INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface, sessions_per_week, heart_rate_ceiling, start_date, end_date)
SELECT tp.plan_id, 1, N'Chay thu & Thu the luc', 2000, N'80% VO2 max', 'TURF', 3, 180,
       tp.start_date, tp.end_date
FROM TRAINING_PLAN tp
WHERE tp.horse_id IN (SELECT horse_id FROM @Horses)
  AND NOT EXISTS (
      SELECT 1 FROM PLAN_PHASE ph WHERE ph.plan_id = tp.plan_id
  );

-- 4. Cap nhat buoi tap gan nhat hoac bo sung buoi tap chay thu (TRIAL_RUN) hoan thanh cach day 3 ngay
-- Neu da co buoi tap thi set is_trial_run = 1 va status = 'COMPLETED'
UPDATE s
SET s.is_trial_run = 1,
    s.status = 'COMPLETED',
    s.session_date = DATEADD(DAY, -3, CAST(GETDATE() AS DATETIME2))
FROM TRAINING_SESSION s
JOIN (
    SELECT session_id,
           ROW_NUMBER() OVER (PARTITION BY horse_id ORDER BY session_date DESC) AS rn
    FROM TRAINING_SESSION
    WHERE horse_id IN (SELECT horse_id FROM @Horses)
) latest ON s.session_id = latest.session_id AND latest.rn = 1;

-- Voi nhung ngua van chua co buoi trial run hoan thanh trong 30 ngay: INSERT moi
INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance, actual_distance, status, is_trial_run, remarks)
SELECT (
    SELECT TOP 1 ph.phase_id
    FROM PLAN_PHASE ph
    JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
    WHERE tp.horse_id = h.horse_id
    ORDER BY ph.phase_id DESC
),
h.horse_id,
@groom,
DATEADD(DAY, -3, CAST(GETDATE() AS DATETIME2)),
2000,
2000,
'COMPLETED',
1,
N'Dat chuan chay thu truoc giai dua'
FROM @Horses h
WHERE NOT EXISTS (
    SELECT 1 FROM TRAINING_SESSION s
    WHERE s.horse_id = h.horse_id AND s.is_trial_run = 1 AND s.status = 'COMPLETED'
      AND s.session_date >= DATEADD(DAY, -30, SYSUTCDATETIME())
);

PRINT 'V30 done: 10 race-ready horses prepared.';
GO
