/* =============================================================================
   V50 - Training Plan Templates (9 = 3 Breeds x 3 Running Styles),
         Auto-Scheduling Engine, Session Recovery State Machine,
         and Velocity Unit Standardization (m/s -> km/h).
   Idempotent: safe to re-run.
   ============================================================================= */

/* ---------- 1. Aptitude & Surface Constraints (GRASS/TURF, DIRT; STALKER/CLOSER) ---------- */

IF OBJECT_ID('CK_HORSE_aptitude', 'C') IS NOT NULL
    ALTER TABLE HORSE DROP CONSTRAINT CK_HORSE_aptitude;
GO
ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_aptitude CHECK (
    (surface IS NULL OR surface IN ('TURF', 'GRASS', 'DIRT'))
    AND (distance_pref IS NULL OR distance_pref IN ('SPRINT', 'MEDIUM', 'LONG'))
    AND (running_style IS NULL OR running_style IN ('FRONT', 'MIDDLE', 'STALKER', 'BACK', 'CLOSER')));
GO

IF OBJECT_ID('CK_HREQ_aptitude', 'C') IS NOT NULL
    ALTER TABLE HORSE_REQUEST DROP CONSTRAINT CK_HREQ_aptitude;
GO
ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_aptitude CHECK (
    (surface IS NULL OR surface IN ('TURF', 'GRASS', 'DIRT'))
    AND (distance_pref IS NULL OR distance_pref IN ('SPRINT', 'MEDIUM', 'LONG'))
    AND (running_style IS NULL OR running_style IN ('FRONT', 'MIDDLE', 'STALKER', 'BACK', 'CLOSER')));
GO

IF OBJECT_ID('CK_PHASE_surface', 'C') IS NOT NULL
    ALTER TABLE PLAN_PHASE DROP CONSTRAINT CK_PHASE_surface;
GO
ALTER TABLE PLAN_PHASE ADD CONSTRAINT CK_PHASE_surface
    CHECK (surface IS NULL OR surface IN ('TURF', 'GRASS', 'DIRT', 'SYNTHETIC'));
GO

IF OBJECT_ID('CK_PHASE_target_velocity', 'C') IS NOT NULL
    ALTER TABLE PLAN_PHASE DROP CONSTRAINT CK_PHASE_target_velocity;
GO
ALTER TABLE PLAN_PHASE ADD CONSTRAINT CK_PHASE_target_velocity
    CHECK (target_velocity IS NULL OR target_velocity BETWEEN 1 AND 100);
GO

/* ---------- 2. Convert legacy velocity data from m/s to km/h ---------- */

UPDATE PLAN_PHASE
SET target_velocity = ROUND(target_velocity * 3.6, 1)
WHERE target_velocity IS NOT NULL AND target_velocity <= 25;
GO

UPDATE TRAINING_SESSION
SET peak_velocity = ROUND(peak_velocity * 3.6, 1)
WHERE peak_velocity IS NOT NULL AND peak_velocity <= 25;
GO

UPDATE TRAINING_SESSION
SET velocity_ceiling = ROUND(velocity_ceiling * 3.6, 1)
WHERE velocity_ceiling IS NOT NULL AND velocity_ceiling <= 25;
GO

/* ---------- 3. Training Plan Templates (9 Templates = 3 Breeds x 3 Styles) ---------- */

IF OBJECT_ID('TRAINING_PLAN_TEMPLATE', 'U') IS NULL
BEGIN
    CREATE TABLE TRAINING_PLAN_TEMPLATE (
        template_id         INT IDENTITY(1,1) PRIMARY KEY,
        template_code       VARCHAR(50)   NOT NULL UNIQUE,
        template_name       NVARCHAR(150) NOT NULL,
        breed               NVARCHAR(50)  NOT NULL,
        running_style       VARCHAR(20)   NOT NULL,
        default_intensity   VARCHAR(20)   NOT NULL,
        base_max_heart_rate INT           NOT NULL,
        base_max_speed      DECIMAL(6,2)  NOT NULL,
        default_rest_days   INT           NOT NULL,
        default_target_hrr  INT           NOT NULL,
        base_distance_m     INT           NOT NULL,
        CONSTRAINT CK_TPL_style     CHECK (running_style IN ('FRONT', 'STALKER', 'CLOSER')),
        CONSTRAINT CK_TPL_intensity CHECK (default_intensity IN ('LOW', 'MEDIUM', 'HIGH')),
        CONSTRAINT CK_TPL_hr        CHECK (base_max_heart_rate BETWEEN 100 AND 260),
        CONSTRAINT CK_TPL_speed     CHECK (base_max_speed BETWEEN 10 AND 100)
    );
END
GO

IF OBJECT_ID('TEMPLATE_PHASE', 'U') IS NULL
BEGIN
    CREATE TABLE TEMPLATE_PHASE (
        template_phase_id         INT IDENTITY(1,1) PRIMARY KEY,
        template_id               INT           NOT NULL REFERENCES TRAINING_PLAN_TEMPLATE(template_id),
        phase_no                  INT           NOT NULL,
        phase_code                VARCHAR(20)   NOT NULL,
        phase_name                NVARCHAR(100) NOT NULL,
        default_ratio             INT           NOT NULL,
        recovery_duration_minutes INT           NOT NULL,
        distance_multiplier       DECIMAL(4,2)  NOT NULL,
        speed_multiplier          DECIMAL(4,2)  NOT NULL,
        hr_offset                 INT           NOT NULL,
        CONSTRAINT UQ_TPL_PHASE_NO   UNIQUE (template_id, phase_no),
        CONSTRAINT CK_TPL_PHASE_CODE CHECK (phase_code IN ('FOUNDATION', 'BUILD', 'PEAK'))
    );
END
GO

/* Seed 9 templates (idempotent) */
DECLARE @tpl TABLE (
    code VARCHAR(50), name NVARCHAR(150), breed NVARCHAR(50), style VARCHAR(20),
    intensity VARCHAR(20), hr INT, spd DECIMAL(6,2), rest INT, hrr INT, dist INT
);
INSERT INTO @tpl VALUES
('TB_FRONT',   N'Thoroughbred · Front-Runner Program',  N'Thoroughbred', 'FRONT',   'HIGH',   210, 66.0, 1, 118, 1600),
('TB_STALKER', N'Thoroughbred · Stalker Balanced',      N'Thoroughbred', 'STALKER', 'MEDIUM', 202, 63.0, 1, 115, 1800),
('TB_CLOSER',  N'Thoroughbred · Closer Late-Kick',      N'Thoroughbred', 'CLOSER',  'HIGH',   206, 65.0, 2, 112, 2000),
('AR_FRONT',   N'Arabian · Front-Runner Pace',          N'Arabian',      'FRONT',   'MEDIUM', 196, 58.0, 1, 110, 2000),
('AR_STALKER', N'Arabian · Stalker Endurance',          N'Arabian',      'STALKER', 'MEDIUM', 192, 56.0, 2, 108, 2200),
('AR_CLOSER',  N'Arabian · Closer Surge',               N'Arabian',      'CLOSER',  'MEDIUM', 195, 57.5, 2, 110, 2400),
('QH_FRONT',   N'Quarter Horse · Gate-to-Wire Sprint',  N'Quarter Horse','FRONT',   'HIGH',   215, 70.0, 2, 122, 1000),
('QH_STALKER', N'Quarter Horse · Tactical Sprint',      N'Quarter Horse','STALKER', 'HIGH',   208, 67.5, 2, 120, 1100),
('QH_CLOSER',  N'Quarter Horse · Power Finish',         N'Quarter Horse','CLOSER',  'MEDIUM', 205, 66.0, 2, 118, 1200);

INSERT INTO TRAINING_PLAN_TEMPLATE (
    template_code, template_name, breed, running_style, default_intensity,
    base_max_heart_rate, base_max_speed, default_rest_days, default_target_hrr, base_distance_m
)
SELECT t.code, t.name, t.breed, t.style, t.intensity, t.hr, t.spd, t.rest, t.hrr, t.dist
FROM @tpl t
WHERE NOT EXISTS (
    SELECT 1 FROM TRAINING_PLAN_TEMPLATE existing WHERE existing.template_code = t.code
);

INSERT INTO TEMPLATE_PHASE (
    template_id, phase_no, phase_code, phase_name, default_ratio,
    recovery_duration_minutes, distance_multiplier, speed_multiplier, hr_offset
)
SELECT t.template_id, p.phase_no, p.phase_code, p.phase_name, p.default_ratio,
       p.rec_mins, p.dist_mult, p.spd_mult, p.hr_off
FROM TRAINING_PLAN_TEMPLATE t
CROSS APPLY (VALUES
    (1, 'FOUNDATION', N'Foundation', 30, 15, 0.80, 0.84, -15),
    (2, 'BUILD',      N'Build',      45, 20, 1.00, 0.94, -5),
    (3, 'PEAK',       N'Peak',       25, 30, 1.15, 1.00, 0)
) p(phase_no, phase_code, phase_name, default_ratio, rec_mins, dist_mult, spd_mult, hr_off)
WHERE NOT EXISTS (
    SELECT 1 FROM TEMPLATE_PHASE tp WHERE tp.template_id = t.template_id AND tp.phase_no = p.phase_no
);
GO

/* ---------- 4. Extend TRAINING_PLAN & PLAN_PHASE ---------- */

IF COL_LENGTH('TRAINING_PLAN', 'template_id') IS NULL
    ALTER TABLE TRAINING_PLAN ADD template_id INT NULL REFERENCES TRAINING_PLAN_TEMPLATE(template_id);
IF COL_LENGTH('TRAINING_PLAN', 'primary_groom_id') IS NULL
    ALTER TABLE TRAINING_PLAN ADD primary_groom_id INT NULL REFERENCES APP_USER(user_id);
IF COL_LENGTH('TRAINING_PLAN', 'backup_groom_id') IS NULL
    ALTER TABLE TRAINING_PLAN ADD backup_groom_id INT NULL REFERENCES APP_USER(user_id);
IF COL_LENGTH('TRAINING_PLAN', 'preferred_surface') IS NULL
    ALTER TABLE TRAINING_PLAN ADD preferred_surface VARCHAR(20) NULL;
IF COL_LENGTH('TRAINING_PLAN', 'intensity') IS NULL
    ALTER TABLE TRAINING_PLAN ADD intensity VARCHAR(20) NULL;
IF COL_LENGTH('TRAINING_PLAN', 'max_heart_rate') IS NULL
    ALTER TABLE TRAINING_PLAN ADD max_heart_rate INT NULL;
IF COL_LENGTH('TRAINING_PLAN', 'max_speed') IS NULL
    ALTER TABLE TRAINING_PLAN ADD max_speed DECIMAL(6,2) NULL;
IF COL_LENGTH('TRAINING_PLAN', 'rest_days_per_week') IS NULL
    ALTER TABLE TRAINING_PLAN ADD rest_days_per_week INT NULL CONSTRAINT DF_PLAN_rest_days DEFAULT 1;
IF COL_LENGTH('TRAINING_PLAN', 'target_hrr') IS NULL
    ALTER TABLE TRAINING_PLAN ADD target_hrr INT NULL;
GO

UPDATE TRAINING_PLAN
SET primary_groom_id = groom_id
WHERE primary_groom_id IS NULL AND groom_id IS NOT NULL;
GO

IF COL_LENGTH('PLAN_PHASE', 'phase_code') IS NULL
    ALTER TABLE PLAN_PHASE ADD phase_code VARCHAR(20) NULL;
IF COL_LENGTH('PLAN_PHASE', 'phase_ratio') IS NULL
    ALTER TABLE PLAN_PHASE ADD phase_ratio INT NULL;
IF COL_LENGTH('PLAN_PHASE', 'recovery_duration_minutes') IS NULL
    ALTER TABLE PLAN_PHASE ADD recovery_duration_minutes INT NOT NULL CONSTRAINT DF_PHASE_recovery_mins DEFAULT 15;
GO

/* ---------- 5. Extend TRAINING_SESSION for Auto-Schedule & Recovery ---------- */

ALTER TABLE TRAINING_SESSION ALTER COLUMN assigned_to INT NULL;
GO

IF OBJECT_ID('CK_SESSION_status', 'C') IS NOT NULL
    ALTER TABLE TRAINING_SESSION DROP CONSTRAINT CK_SESSION_status;
GO
ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_status
    CHECK (status IN ('SCHEDULED','RUNNING','RECOVERY','COMPLETED','STOPPED_EARLY','CANCELLED','UNASSIGNED','REST'));
GO

IF COL_LENGTH('TRAINING_SESSION', 'slot_type') IS NULL
    ALTER TABLE TRAINING_SESSION ADD slot_type VARCHAR(20) NOT NULL CONSTRAINT DF_SESSION_slot_type DEFAULT 'TRAINING';
IF COL_LENGTH('TRAINING_SESSION', 'conflict_alert') IS NULL
    ALTER TABLE TRAINING_SESSION ADD conflict_alert BIT NOT NULL CONSTRAINT DF_SESSION_conflict DEFAULT 0;
IF COL_LENGTH('TRAINING_SESSION', 'recovery_duration_minutes') IS NULL
    ALTER TABLE TRAINING_SESSION ADD recovery_duration_minutes INT NULL;
IF COL_LENGTH('TRAINING_SESSION', 'recovery_started_at') IS NULL
    ALTER TABLE TRAINING_SESSION ADD recovery_started_at DATETIME2 NULL;
IF COL_LENGTH('TRAINING_SESSION', 'recovery_ends_at') IS NULL
    ALTER TABLE TRAINING_SESSION ADD recovery_ends_at DATETIME2 NULL;
IF COL_LENGTH('TRAINING_SESSION', 'actual_hrr') IS NULL
    ALTER TABLE TRAINING_SESSION ADD actual_hrr INT NULL;
IF COL_LENGTH('TRAINING_SESSION', 'overload_warning') IS NULL
    ALTER TABLE TRAINING_SESSION ADD overload_warning BIT NOT NULL CONSTRAINT DF_SESSION_overload DEFAULT 0;
GO

PRINT 'V50 done.';
GO
