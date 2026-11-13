/* =============================================================================
   V47 - Plan update approval workflow (pending update + 2-day auto-reject)
         and per-session velocity ceiling for speed threshold alerts.
   ============================================================================= */

IF COL_LENGTH('TRAINING_PLAN', 'pending_update_status') IS NULL
    ALTER TABLE TRAINING_PLAN ADD pending_update_status VARCHAR(20) NULL;
GO

IF COL_LENGTH('TRAINING_PLAN', 'pending_update_at') IS NULL
    ALTER TABLE TRAINING_PLAN ADD pending_update_at DATETIME2 NULL;
GO

IF COL_LENGTH('TRAINING_PLAN', 'pending_update_json') IS NULL
    ALTER TABLE TRAINING_PLAN ADD pending_update_json NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('TRAINING_SESSION', 'velocity_ceiling') IS NULL
    ALTER TABLE TRAINING_SESSION ADD velocity_ceiling DECIMAL(5,2) NULL;
GO
