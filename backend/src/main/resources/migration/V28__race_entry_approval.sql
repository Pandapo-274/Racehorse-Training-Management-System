/* =============================================================================
   V27 - Horse Owner Approval for Race Registrations.

   PROBLEM
   Head Trainer could register any horse for high-risk races without the
   owner's consent. Entry fees and injury risks were imposed on owners with
   no formal approval step.

   FIX
   Add approval_status, owner_decision_at, and reject_reason columns to
   RACE_REGISTRATION. New entries start as PENDING_OWNER. Existing entries
   are grandfathered as APPROVED.
   ============================================================================= */

IF COL_LENGTH('RACE_REGISTRATION', 'approval_status') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD approval_status VARCHAR(20) NOT NULL CONSTRAINT DF_REG_approval DEFAULT 'APPROVED';
GO

IF COL_LENGTH('RACE_REGISTRATION', 'owner_decision_at') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD owner_decision_at DATETIME2 NULL;
GO

IF COL_LENGTH('RACE_REGISTRATION', 'reject_reason') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD reject_reason NVARCHAR(255) NULL;
GO

IF OBJECT_ID('CK_REG_approval_status', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_approval_status
        CHECK (approval_status IN ('PENDING_OWNER', 'APPROVED', 'REJECTED'));
GO

PRINT 'V27 done.';
GO
