/* =============================================================================
   V67 - Backfill Session Review recovery heart rate data:
         - Majority (~80%+) of reviewable sessions have valid recovery HRR (happy case)
         - A small minority (~15-20%) have missing recovery HRR + warning notice
   ============================================================================= */

DECLARE @warn NVARCHAR(200) = N'[Warning: Recovery heart rate was not recorded within 5 minutes after cooldown ended]';

;WITH ReviewSessions AS (
    SELECT
        s.session_id,
        s.phase_id,
        s.recovery_heart_rate,
        s.actual_hrr,
        s.remarks,
        tp.target_hrr,
        ROW_NUMBER() OVER (ORDER BY s.session_id DESC) AS rn,
        COUNT(*) OVER () AS total_cnt
    FROM TRAINING_SESSION s
    LEFT JOIN PLAN_PHASE p ON p.phase_id = s.phase_id
    LEFT JOIN TRAINING_PLAN tp ON tp.plan_id = p.plan_id
    WHERE s.status IN ('COMPLETED', 'STOPPED_EARLY')
      AND s.review_status IN ('PENDING', 'REVIEWED', 'ARCHIVED_AUTO')
),
Classified AS (
    SELECT
        session_id,
        phase_id,
        recovery_heart_rate,
        actual_hrr,
        remarks,
        target_hrr,
        CASE
            WHEN total_cnt >= 5 AND (rn % 5 = 2) THEN 1
            WHEN total_cnt BETWEEN 3 AND 4 AND rn = 2 THEN 1
            WHEN total_cnt = 2 AND rn = 2 THEN 1
            ELSE 0
        END AS is_missing_hrr_case
    FROM ReviewSessions
)
UPDATE s
SET
    s.recovery_heart_rate = CASE
        WHEN c.is_missing_hrr_case = 1 THEN NULL
        ELSE COALESCE(s.recovery_heart_rate, s.actual_hrr, ISNULL(c.target_hrr, 115) - 3 - (s.session_id % 6))
    END,
    s.actual_hrr = CASE
        WHEN c.is_missing_hrr_case = 1 THEN NULL
        ELSE COALESCE(s.actual_hrr, s.recovery_heart_rate, ISNULL(c.target_hrr, 115) - 3 - (s.session_id % 6))
    END,
    s.overload_warning = CASE
        WHEN c.is_missing_hrr_case = 1 THEN 1
        WHEN s.recovery_heart_rate IS NULL AND s.actual_hrr IS NULL THEN 0
        ELSE s.overload_warning
    END,
    s.remarks = CASE
        WHEN c.is_missing_hrr_case = 1 THEN
            CASE
                WHEN s.remarks IS NULL OR LEN(LTRIM(RTRIM(s.remarks))) = 0 THEN @warn
                WHEN s.remarks LIKE N'%Recovery heart rate was not recorded%' THEN s.remarks
                ELSE s.remarks + CHAR(10) + @warn
            END
        ELSE
            CASE
                WHEN s.remarks IS NULL THEN NULL
                ELSE NULLIF(LTRIM(RTRIM(REPLACE(REPLACE(s.remarks, CHAR(10) + @warn, N''), @warn, N''))), N'')
            END
    END
FROM TRAINING_SESSION s
JOIN Classified c ON c.session_id = s.session_id;
GO

PRINT 'V67 backfill session review recovery HRR complete.';
GO
