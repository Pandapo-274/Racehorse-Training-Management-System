/* =============================================================================
   V19 - Void an expense instead of deleting it.

   PROBLEM
   ExpenseController only had create and a hard DELETE. A wrong figure had to be
   deleted and re-entered, and the audit entry for the delete held only the
   expense id - once the row was gone, nobody could tell how much it was or
   which horse it was charged to.

   FIX
   An entry in a ledger is not overwritten or erased; it is voided with a
   reason and, if needed, entered again correctly. These three columns record
   who voided the row, when, and why. The row stays, so the audit id still
   resolves to the full amount, horse and date.

   Every query that sums or lists expenses must add `cancelled_at IS NULL`.
   In the code at the time of writing that is ExpenseRepository.filter(),
   ManagerDashboardService and OwnerDashboardService.

   Existing rows are untouched: all three columns are NULL, i.e. not voided.
   ============================================================================= */

IF COL_LENGTH('EXPENSE_LOG', 'cancelled_at') IS NULL
    ALTER TABLE EXPENSE_LOG ADD cancelled_at DATETIME2 NULL;
GO

IF COL_LENGTH('EXPENSE_LOG', 'cancelled_by') IS NULL
    ALTER TABLE EXPENSE_LOG ADD cancelled_by INT NULL;
GO

IF COL_LENGTH('EXPENSE_LOG', 'cancel_reason') IS NULL
    ALTER TABLE EXPENSE_LOG ADD cancel_reason NVARCHAR(255) NULL;
GO

IF OBJECT_ID('FK_EXP_canceller', 'F') IS NULL
    ALTER TABLE EXPENSE_LOG ADD CONSTRAINT FK_EXP_canceller
        FOREIGN KEY (cancelled_by) REFERENCES APP_USER(user_id);
GO

/* All three together or none: a voided row without a reason is exactly the
   unexplained gap this migration exists to close. */
IF OBJECT_ID('CK_EXP_cancel', 'C') IS NULL
    ALTER TABLE EXPENSE_LOG ADD CONSTRAINT CK_EXP_cancel CHECK (
        (cancelled_at IS NULL AND cancelled_by IS NULL AND cancel_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL)
    );
GO

IF COL_LENGTH('EXPENSE_LOG', 'cancel_reason') IS NULL
    THROW 50190, N'V19: EXPENSE_LOG.cancel_reason was not created.', 1;

PRINT 'V19 done.';
GO
