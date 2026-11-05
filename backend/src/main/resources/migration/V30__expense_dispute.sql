/* =============================================================================
   V29 - Expense Dispute and Confirmation for Horse Owners.

   PROBLEM
   Expenses were unilaterally billed by Club Managers. Owners had no channel
   to dispute or acknowledge unfamiliar or erroneous charges.

   FIX
   Add status, dispute_reason, and disputed_at to EXPENSE_LOG.
   Existing rows default to 'BILLED'.
   ============================================================================= */

IF COL_LENGTH('EXPENSE_LOG', 'status') IS NULL
    ALTER TABLE EXPENSE_LOG ADD status VARCHAR(20) NOT NULL CONSTRAINT DF_EXP_status DEFAULT 'BILLED';
GO

IF COL_LENGTH('EXPENSE_LOG', 'dispute_reason') IS NULL
    ALTER TABLE EXPENSE_LOG ADD dispute_reason NVARCHAR(255) NULL;
GO

IF COL_LENGTH('EXPENSE_LOG', 'disputed_at') IS NULL
    ALTER TABLE EXPENSE_LOG ADD disputed_at DATETIME2 NULL;
GO

IF OBJECT_ID('CK_EXP_status', 'C') IS NULL
    ALTER TABLE EXPENSE_LOG ADD CONSTRAINT CK_EXP_status
        CHECK (status IN ('BILLED', 'DISPUTED', 'RESOLVED'));
GO

PRINT 'V29 done.';
GO
