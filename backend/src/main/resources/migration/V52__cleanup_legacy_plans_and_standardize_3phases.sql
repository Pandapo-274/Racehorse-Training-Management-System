/* =============================================================================
   V51 - Clean up legacy/redundant Training Plans, normalize Horse surfaces
         (TURF / DIRT only), and standardize kept Training Plans to fixed
         3 Phases (FOUNDATION 30%, BUILD 45%, PEAK 25%) with computed dates &
         sessions_per_week = phase days.
   Idempotent: safe to re-run.
   ============================================================================= */

/* ---------- 1. Normalize Surface (TURF / DIRT only) & Horse Aptitudes ---------- */

UPDATE HORSE
SET surface = CASE
        WHEN UPPER(LTRIM(RTRIM(ISNULL(surface, '')))) = 'DIRT' THEN 'DIRT'
        WHEN UPPER(LTRIM(RTRIM(ISNULL(surface, '')))) IN ('GRASS', 'TURF') THEN 'TURF'
        WHEN horse_id % 2 = 0 THEN 'DIRT'
        ELSE 'TURF'
    END
WHERE surface IS NULL OR UPPER(LTRIM(RTRIM(surface))) NOT IN ('TURF', 'DIRT');

UPDATE HORSE
SET distance_pref = CASE
        WHEN horse_id % 3 = 1 THEN 'SPRINT'
        WHEN horse_id % 3 = 0 THEN 'LONG'
        ELSE 'MEDIUM'
    END
WHERE distance_pref IS NULL;

UPDATE HORSE
SET running_style = CASE
        WHEN UPPER(LTRIM(RTRIM(ISNULL(running_style, '')))) = 'MIDDLE' THEN 'STALKER'
        WHEN UPPER(LTRIM(RTRIM(ISNULL(running_style, '')))) = 'BACK' THEN 'CLOSER'
        WHEN running_style IS NOT NULL THEN UPPER(LTRIM(RTRIM(running_style)))
        WHEN horse_id % 3 = 1 THEN 'FRONT'
        WHEN horse_id % 3 = 0 THEN 'CLOSER'
        ELSE 'STALKER'
    END
WHERE running_style IS NULL
   OR UPPER(LTRIM(RTRIM(running_style))) NOT IN ('FRONT', 'STALKER', 'CLOSER');

UPDATE HORSE_REQUEST
SET surface = 'TURF'
WHERE surface IS NOT NULL AND UPPER(LTRIM(RTRIM(surface))) = 'GRASS';

UPDATE TRAINING_PLAN
SET preferred_surface = CASE
        WHEN UPPER(LTRIM(RTRIM(ISNULL(preferred_surface, '')))) = 'DIRT' THEN 'DIRT'
        ELSE 'TURF'
    END
WHERE preferred_surface IS NULL OR UPPER(LTRIM(RTRIM(preferred_surface))) NOT IN ('TURF', 'DIRT');

UPDATE PLAN_PHASE
SET surface = CASE
        WHEN UPPER(LTRIM(RTRIM(ISNULL(surface, '')))) IN ('DIRT', 'SYNTHETIC') THEN 'DIRT'
        ELSE 'TURF'
    END
WHERE surface IS NULL OR UPPER(LTRIM(RTRIM(surface))) NOT IN ('TURF', 'DIRT');
GO

/* ---------- 2. Remove legacy/demo plans (keep at most 3 non-completed plans) ---------- */

DECLARE @keptPlans TABLE (plan_id INT PRIMARY KEY);

INSERT INTO @keptPlans (plan_id)
SELECT TOP (3) tp.plan_id
FROM TRAINING_PLAN tp
WHERE tp.status <> 'COMPLETED'
ORDER BY
    CASE WHEN tp.plan_name LIKE N'%[DEMO]%' THEN 1 ELSE 0 END ASC,
    CASE WHEN tp.groom_id IS NOT NULL OR tp.primary_groom_id IS NOT NULL THEN 0 ELSE 1 END ASC,
    CASE tp.status
        WHEN 'ACTIVE' THEN 1
        WHEN 'APPROVED' THEN 2
        WHEN 'PENDING_APPROVAL' THEN 3
        WHEN 'DRAFT' THEN 4
        ELSE 5
    END ASC,
    tp.plan_id DESC;

DECLARE @deletePlans TABLE (plan_id INT PRIMARY KEY);
INSERT INTO @deletePlans (plan_id)
SELECT tp.plan_id
FROM TRAINING_PLAN tp
WHERE tp.plan_id NOT IN (SELECT plan_id FROM @keptPlans);

IF EXISTS (SELECT 1 FROM @deletePlans)
BEGIN
    DECLARE @deleteSessions TABLE (session_id INT PRIMARY KEY);
    INSERT INTO @deleteSessions (session_id)
    SELECT ts.session_id
    FROM TRAINING_SESSION ts
    JOIN PLAN_PHASE pp ON pp.phase_id = ts.phase_id
    WHERE pp.plan_id IN (SELECT plan_id FROM @deletePlans);

    IF EXISTS (SELECT 1 FROM @deleteSessions)
    BEGIN
        /* Preserve incident/medical history while detaching deleted training sessions */
        UPDATE INCIDENT_REPORT
        SET session_id = NULL
        WHERE session_id IN (SELECT session_id FROM @deleteSessions);

        DELETE FROM VET_CALL
        WHERE session_id IN (SELECT session_id FROM @deleteSessions)
           OR alert_id IN (
               SELECT alert_id
               FROM THRESHOLD_ALERT
               WHERE session_id IN (SELECT session_id FROM @deleteSessions)
           );

        DELETE FROM THRESHOLD_ALERT
        WHERE session_id IN (SELECT session_id FROM @deleteSessions);

        DELETE FROM TRAINING_SESSION
        WHERE session_id IN (SELECT session_id FROM @deleteSessions);
    END

    DELETE FROM PLAN_PHASE
    WHERE plan_id IN (SELECT plan_id FROM @deletePlans);

    DELETE FROM TRAINING_PLAN
    WHERE plan_id IN (SELECT plan_id FROM @deletePlans);
END

/* ---------- 3. Standardize kept plans (grooms, template, load params, 3 fixed phases) ---------- */

DECLARE @primaryGroomId INT = (
    SELECT TOP (1) u.user_id
    FROM APP_USER u
    JOIN ROLE r ON r.role_id = u.role_id
    WHERE r.role_name = 'GROOM' AND u.status = 'ACTIVE'
    ORDER BY u.user_id ASC
);

DECLARE @backupGroomId INT = (
    SELECT TOP (1) u.user_id
    FROM APP_USER u
    JOIN ROLE r ON r.role_id = u.role_id
    WHERE r.role_name = 'GROOM' AND u.status = 'ACTIVE'
      AND (@primaryGroomId IS NULL OR u.user_id <> @primaryGroomId)
    ORDER BY u.user_id ASC
);
IF @backupGroomId IS NULL SET @backupGroomId = @primaryGroomId;

DECLARE @fallbackTemplateId INT = (
    SELECT TOP (1) template_id
    FROM TRAINING_PLAN_TEMPLATE
    ORDER BY template_id ASC
);

/* Ensure each kept plan spans at least 10 days so 30/45/25 splits cleanly */
UPDATE tp
SET tp.end_date = DATEADD(DAY, 29, tp.start_date)
FROM TRAINING_PLAN tp
JOIN @keptPlans k ON k.plan_id = tp.plan_id
WHERE DATEDIFF(DAY, tp.start_date, tp.end_date) < 9;

/* Populate missing grooms, matching template, surface & load parameters on kept plans */
UPDATE tp
SET
    tp.primary_groom_id = COALESCE(tp.primary_groom_id, tp.groom_id, @primaryGroomId),
    tp.groom_id         = COALESCE(tp.groom_id, tp.primary_groom_id, @primaryGroomId),
    tp.backup_groom_id  = COALESCE(
        NULLIF(tp.backup_groom_id, COALESCE(tp.primary_groom_id, tp.groom_id, @primaryGroomId)),
        CASE
            WHEN COALESCE(tp.primary_groom_id, tp.groom_id, @primaryGroomId) = @backupGroomId THEN @primaryGroomId
            ELSE @backupGroomId
        END
    ),
    tp.template_id = COALESCE(
        tp.template_id,
        (
            SELECT TOP (1) tpl.template_id
            FROM TRAINING_PLAN_TEMPLATE tpl
            WHERE UPPER(ISNULL(h.breed, '')) LIKE '%' + UPPER(tpl.breed) + '%'
              AND tpl.running_style = COALESCE(h.running_style, 'STALKER')
            ORDER BY tpl.template_id ASC
        ),
        (
            SELECT TOP (1) tpl.template_id
            FROM TRAINING_PLAN_TEMPLATE tpl
            WHERE tpl.running_style = COALESCE(h.running_style, 'STALKER')
            ORDER BY tpl.template_id ASC
        ),
        @fallbackTemplateId
    ),
    tp.preferred_surface = CASE WHEN UPPER(ISNULL(h.surface, '')) = 'DIRT' THEN 'DIRT' ELSE 'TURF' END
FROM TRAINING_PLAN tp
JOIN @keptPlans k ON k.plan_id = tp.plan_id
JOIN HORSE h ON h.horse_id = tp.horse_id;

UPDATE tp
SET
    tp.intensity         = COALESCE(tp.intensity, tpl.default_intensity, 'MEDIUM'),
    tp.max_heart_rate    = COALESCE(tp.max_heart_rate, tpl.base_max_heart_rate, 202),
    tp.max_speed         = COALESCE(tp.max_speed, tpl.base_max_speed, 63.0),
    tp.rest_days_per_week = COALESCE(tp.rest_days_per_week, tpl.default_rest_days, 1),
    tp.target_hrr        = COALESCE(tp.target_hrr, tpl.default_target_hrr, 115)
FROM TRAINING_PLAN tp
JOIN @keptPlans k ON k.plan_id = tp.plan_id
LEFT JOIN TRAINING_PLAN_TEMPLATE tpl ON tpl.template_id = tp.template_id;

/* Rebuild fixed 3 phases (101/102/103 temporary numbers to keep existing sessions linked) */
IF EXISTS (SELECT 1 FROM @keptPlans)
BEGIN
    DELETE FROM PLAN_PHASE
    WHERE plan_id IN (SELECT plan_id FROM @keptPlans)
      AND phase_no >= 100;

    INSERT INTO PLAN_PHASE (
        plan_id, phase_no, phase_name, distance, workload, surface,
        sessions_per_week, heart_rate_ceiling, start_date, end_date,
        min_gap_minutes, target_velocity, phase_code, phase_ratio, recovery_duration_minutes
    )
    SELECT
        tp.plan_id,
        100 + p.phase_no AS phase_no,
        p.phase_name,
        CAST(
            ROUND((ISNULL(tpl.base_distance_m, 1600) * p.dist_mult) / 50.0, 0) * 50
            AS INT
        ) AS distance,
        p.workload,
        tp.preferred_surface AS surface,
        CASE p.phase_no
            WHEN 1 THEN CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT)
            WHEN 2 THEN CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 45.0 / 100.0) AS INT)
            ELSE (DATEDIFF(DAY, tp.start_date, tp.end_date) + 1)
                 - CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT)
                 - CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 45.0 / 100.0) AS INT)
        END AS sessions_per_week,
        CASE
            WHEN tp.max_heart_rate + p.hr_off < 120 THEN 120
            WHEN tp.max_heart_rate + p.hr_off > 240 THEN 240
            ELSE tp.max_heart_rate + p.hr_off
        END AS heart_rate_ceiling,
        CASE p.phase_no
            WHEN 1 THEN tp.start_date
            WHEN 2 THEN DATEADD(DAY, CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT), tp.start_date)
            ELSE DATEADD(DAY,
                CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT)
                + CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 45.0 / 100.0) AS INT),
                tp.start_date)
        END AS start_date,
        CASE p.phase_no
            WHEN 1 THEN DATEADD(DAY, CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT) - 1, tp.start_date)
            WHEN 2 THEN DATEADD(DAY,
                CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 30.0 / 100.0) AS INT)
                + CAST(FLOOR((DATEDIFF(DAY, tp.start_date, tp.end_date) + 1) * 45.0 / 100.0) AS INT) - 1,
                tp.start_date)
            ELSE tp.end_date
        END AS end_date,
        120 AS min_gap_minutes,
        ROUND(tp.max_speed * p.spd_mult, 1) AS target_velocity,
        p.phase_code,
        p.phase_ratio,
        p.rec_mins
    FROM TRAINING_PLAN tp
    JOIN @keptPlans k ON k.plan_id = tp.plan_id
    LEFT JOIN TRAINING_PLAN_TEMPLATE tpl ON tpl.template_id = tp.template_id
    CROSS APPLY (VALUES
        (1, 'FOUNDATION', N'Foundation', 30, 15, N'Light / Aerobic',    0.80, 0.84, -15),
        (2, 'BUILD',      N'Build',      45, 20, N'Moderate / Interval', 1.00, 0.94,  -5),
        (3, 'PEAK',       N'Peak',       25, 30, N'High / Race Pace',   1.15, 1.00,   0)
    ) p(phase_no, phase_code, phase_name, phase_ratio, rec_mins, workload, dist_mult, spd_mult, hr_off);

    /* Re-point any existing sessions of kept plans to the corresponding 101/102/103 phase */
    UPDATE ts
    SET ts.phase_id = COALESCE(
        (
            SELECT TOP (1) np.phase_id
            FROM PLAN_PHASE np
            WHERE np.plan_id = old_pp.plan_id
              AND np.phase_no IN (101, 102, 103)
              AND CAST(ts.session_date AS DATE) BETWEEN np.start_date AND np.end_date
            ORDER BY np.phase_no ASC
        ),
        (
            SELECT TOP (1) np.phase_id
            FROM PLAN_PHASE np
            WHERE np.plan_id = old_pp.plan_id
              AND np.phase_no = CASE
                  WHEN CAST(ts.session_date AS DATE) <= (
                      SELECT p1.end_date FROM PLAN_PHASE p1
                      WHERE p1.plan_id = old_pp.plan_id AND p1.phase_no = 101
                  ) THEN 101
                  ELSE 103
              END
        )
    )
    FROM TRAINING_SESSION ts
    JOIN PLAN_PHASE old_pp ON old_pp.phase_id = ts.phase_id
    WHERE old_pp.plan_id IN (SELECT plan_id FROM @keptPlans)
      AND old_pp.phase_no < 100;

    DELETE FROM PLAN_PHASE
    WHERE plan_id IN (SELECT plan_id FROM @keptPlans)
      AND phase_no < 100;

    UPDATE PLAN_PHASE
    SET phase_no = phase_no - 100
    WHERE plan_id IN (SELECT plan_id FROM @keptPlans)
      AND phase_no >= 100;
END
GO

PRINT 'V51 cleanup and 3-phase standardization complete.';
GO
