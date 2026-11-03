/* =============================================================================
   V22 - A race is a thing in the database, not a typed name (#7).

   PROBLEM
   RACE_REGISTRATION held the race and the entry in one row: race_name and
   race_date were free text, so a race had no distance, venue, entry deadline,
   fee or field size, and nothing for several horses to point at. (Letter case
   was NOT the weak point: the database collation is case-insensitive - see
   V10 - so UQ_REG_entry already treats "Tracen Cup" and "tracen cup" as one.)

   FIX
   1. RACE: one row per race. Name + date are unique and cannot be edited after
      creation (cancel and create a new race instead), because...
   2. RACE_REGISTRATION.race_id points at it, and race_name / race_date STAY as
      copies taken at registration. Every existing query that reads them
      (dashboards, club stats, withdrawal) keeps working unchanged. Because the
      name and date of a race never change, the copies cannot drift.
   3. finish_position and finish_time_ms: the actual placing and time.
      result (WON / PLACED / UNPLACED / SCRATCHED) stays, so old statistics do
      not break. The service checks they agree: WON = 1st, PLACED = 2nd-3rd,
      UNPLACED = 4th or worse.

   Back-fill: one RACE per distinct (trimmed name, date) in the existing
   entries; names that differ only in spacing or case become one race.
   UQ_REG_entry and the copied columns are kept on purpose; a later migration
   can drop them once nothing reads them.
   ============================================================================= */

IF OBJECT_ID('RACE', 'U') IS NULL
CREATE TABLE RACE (
    race_id        INT IDENTITY(1,1) PRIMARY KEY,
    race_name      NVARCHAR(150) NOT NULL,
    race_date      DATE          NOT NULL,
    distance_m     INT           NULL,
    venue          NVARCHAR(150) NULL,
    entry_deadline DATE          NULL,
    entry_fee      DECIMAL(12,2) NULL,
    max_entries    INT           NULL,
    status         VARCHAR(12)   NOT NULL CONSTRAINT DF_RACE_status DEFAULT 'OPEN',
    cancel_reason  NVARCHAR(255) NULL,
    created_by     INT           NULL,       -- NULL for races rebuilt from old entries
    created_at     DATETIME2     NOT NULL CONSTRAINT DF_RACE_time DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_RACE          UNIQUE (race_name, race_date),
    CONSTRAINT FK_RACE_user     FOREIGN KEY (created_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_RACE_status   CHECK (status IN ('OPEN', 'CANCELLED')),
    CONSTRAINT CK_RACE_cancel   CHECK ((status = 'CANCELLED' AND cancel_reason IS NOT NULL)
                                    OR (status = 'OPEN' AND cancel_reason IS NULL)),
    CONSTRAINT CK_RACE_distance CHECK (distance_m IS NULL OR distance_m > 0),
    CONSTRAINT CK_RACE_max      CHECK (max_entries IS NULL OR max_entries > 0),
    CONSTRAINT CK_RACE_fee      CHECK (entry_fee IS NULL OR entry_fee >= 0),
    CONSTRAINT CK_RACE_deadline CHECK (entry_deadline IS NULL OR entry_deadline <= race_date)
);
GO

/* One race per distinct (trimmed name, date). The collation is
   case-insensitive, so GROUP BY already merges "Cup" and "cup". */
INSERT INTO RACE (race_name, race_date)
SELECT MIN(LTRIM(RTRIM(rr.race_name))), rr.race_date
FROM RACE_REGISTRATION rr
WHERE NOT EXISTS (SELECT 1 FROM RACE r
                  WHERE r.race_name = LTRIM(RTRIM(rr.race_name)) AND r.race_date = rr.race_date)
GROUP BY LTRIM(RTRIM(rr.race_name)), rr.race_date;
GO

IF COL_LENGTH('RACE_REGISTRATION', 'race_id') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD race_id INT NULL;
GO

UPDATE rr
SET rr.race_id = r.race_id
FROM RACE_REGISTRATION rr
JOIN RACE r ON r.race_name = LTRIM(RTRIM(rr.race_name)) AND r.race_date = rr.race_date
WHERE rr.race_id IS NULL;
GO

/* Every entry must have found its race before the column becomes NOT NULL.
   This one is a THROW: the INSERT above created a race for every entry, so a
   miss here is a bug in this script, not a property of your data. */
IF EXISTS (SELECT 1 FROM RACE_REGISTRATION WHERE race_id IS NULL)
    THROW 50220, N'V22: some RACE_REGISTRATION rows did not match a RACE.', 1;
GO

ALTER TABLE RACE_REGISTRATION ALTER COLUMN race_id INT NOT NULL;
GO

IF OBJECT_ID('FK_REG_race', 'F') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT FK_REG_race
        FOREIGN KEY (race_id) REFERENCES RACE(race_id);
GO

/* One entry per horse per race. Same rule as UQ_REG_entry, stated on the key. */
IF OBJECT_ID('UQ_REG_horse_race', 'UQ') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT UQ_REG_horse_race UNIQUE (horse_id, race_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_REG_race')
    CREATE INDEX IX_REG_race ON RACE_REGISTRATION(race_id);
GO

IF COL_LENGTH('RACE_REGISTRATION', 'finish_position') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD finish_position INT NULL;
IF COL_LENGTH('RACE_REGISTRATION', 'finish_time_ms') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD finish_time_ms INT NULL;
GO

IF OBJECT_ID('CK_REG_position', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_position
        CHECK (finish_position IS NULL
               OR (finish_position >= 1 AND result IN ('WON', 'PLACED', 'UNPLACED')));
IF OBJECT_ID('CK_REG_time', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_time
        CHECK (finish_time_ms IS NULL
               OR (finish_time_ms > 0 AND result IN ('WON', 'PLACED', 'UNPLACED')));
GO

DECLARE @races INT = (SELECT COUNT(*) FROM RACE);
PRINT CONCAT('V22: ', @races, ' race(s).');
PRINT 'V22 done.';
GO
