/* =============================================================================
   V46 - Repair: run V40 (vet shift handover + external referral) again.

   An older branch had shipped another V40 (plan_assigned_groom, now V42), so
   databases that ran that branch recorded "V40 done" and Flyway
   (validate-on-migrate=false) skipped V40__vet_handoff_and_external_referral.
   This is the same script; every statement checks first (or, for
   CK_NOTIF_target, drops and re-adds the same list), so it changes nothing
   where V40 did run.
   ============================================================================= */
IF COL_LENGTH('INCIDENT_REPORT', 'handover_by') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD handover_by INT NULL;
GO

IF COL_LENGTH('INCIDENT_REPORT', 'handover_at') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD handover_at DATETIME2 NULL;
GO

IF COL_LENGTH('INCIDENT_REPORT', 'handover_notes') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD handover_notes NVARCHAR(1000) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_INC_handover_by')
    ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT FK_INC_handover_by
        FOREIGN KEY (handover_by) REFERENCES APP_USER(user_id);
GO

IF OBJECT_ID('EXTERNAL_REFERRAL', 'U') IS NULL
BEGIN
    CREATE TABLE EXTERNAL_REFERRAL (
        referral_id         INT IDENTITY(1,1) PRIMARY KEY,
        horse_id            INT NOT NULL,
        report_id           INT NULL,
        medical_record_id   INT NULL,
        referred_by         INT NOT NULL,
        referred_at         DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        hospital_name       NVARCHAR(255) NOT NULL,
        referral_reason     NVARCHAR(1000) NOT NULL,
        urgency             VARCHAR(20) NOT NULL DEFAULT 'HIGH',
        expected_return     DATE NULL,
        status              VARCHAR(30) NOT NULL DEFAULT 'HOSPITALIZED',
        discharge_notes     NVARCHAR(1000) NULL,
        discharged_at       DATETIME2 NULL,
        discharged_by       INT NULL,
        CONSTRAINT FK_REF_horse FOREIGN KEY (horse_id) REFERENCES HORSE(horse_id),
        CONSTRAINT FK_REF_report FOREIGN KEY (report_id) REFERENCES INCIDENT_REPORT(report_id),
        CONSTRAINT FK_REF_med_record FOREIGN KEY (medical_record_id) REFERENCES MEDICAL_RECORD(record_id),
        CONSTRAINT FK_REF_vet FOREIGN KEY (referred_by) REFERENCES APP_USER(user_id),
        CONSTRAINT FK_REF_discharge_vet FOREIGN KEY (discharged_by) REFERENCES APP_USER(user_id),
        CONSTRAINT CK_REF_status CHECK (status IN ('HOSPITALIZED', 'DISCHARGED', 'CANCELLED')),
        CONSTRAINT CK_REF_urgency CHECK (urgency IN ('HIGH', 'CRITICAL'))
    );

    CREATE INDEX IX_REF_horse ON EXTERNAL_REFERRAL(horse_id);
    CREATE INDEX IX_REF_status ON EXTERNAL_REFERRAL(status);
END
GO

IF OBJECT_ID('CK_NOTIF_target', 'C') IS NOT NULL
    ALTER TABLE NOTIFICATION DROP CONSTRAINT CK_NOTIF_target;
GO

ALTER TABLE NOTIFICATION ADD CONSTRAINT CK_NOTIF_target CHECK (target_entity IS NULL OR target_entity IN
    ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT', 'HORSE_WITHDRAWAL', 'VET_CALL',
     'TRAINING_PLAN', 'EXTERNAL_REFERRAL', 'INCIDENT_REPORT'));
GO

PRINT 'V46 done.';
GO
