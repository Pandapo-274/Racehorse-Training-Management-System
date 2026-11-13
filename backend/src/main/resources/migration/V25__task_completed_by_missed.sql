Y/* =============================================================================
   V25 - Who ticked a care task, and which tasks were never done (#14).

   completed_by  who pressed "done". A Head Trainer may tick for a groom, so
                 assigned_to alone does not say who did it.
   missed_at     when the system noticed the task's day had passed with the
                 task still open (MissedTaskJob, each morning). The MISSED
                 state on screen is derived from the date; this column is the
                 record for reports.

   is_completed stays a BIT bound by CK_TASK_done, so "missed" is NOT a third
   value of it; it is a separate column, and a task cannot be both done and
   missed (CK_TASK_missed). Ticking a task late clears missed_at.

   Existing completed rows keep completed_by NULL: who ticked them was never
   recorded.
   ============================================================================= */

IF COL_LENGTH('DAILY_TASK', 'completed_by') IS NULL
    ALTER TABLE DAILY_TASK ADD completed_by INT NULL;
GO

IF OBJECT_ID('FK_TASK_completed_by', 'F') IS NULL
    ALTER TABLE DAILY_TASK ADD CONSTRAINT FK_TASK_completed_by
        FOREIGN KEY (completed_by) REFERENCES APP_USER(user_id);
GO

IF COL_LENGTH('DAILY_TASK', 'missed_at') IS NULL
    ALTER TABLE DAILY_TASK ADD missed_at DATETIME2 NULL;
GO

IF OBJECT_ID('CK_TASK_missed', 'C') IS NULL
    ALTER TABLE DAILY_TASK ADD CONSTRAINT CK_TASK_missed
        CHECK (missed_at IS NULL OR is_completed = 0);
GO

IF COL_LENGTH('DAILY_TASK', 'completed_by') IS NULL OR COL_LENGTH('DAILY_TASK', 'missed_at') IS NULL
    THROW 50250, N'V25: DAILY_TASK columns were not created.', 1;

PRINT 'V25 done.';
GO
