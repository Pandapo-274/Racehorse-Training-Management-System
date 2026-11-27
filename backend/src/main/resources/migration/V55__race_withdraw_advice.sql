/* =============================================================================
   V55 - The Head Trainer advises a withdrawal; only the owner withdraws.

   Committee point 14: the horse owner registers the horse for a race and the
   Head Trainer is the adviser. Registering was already made the
   owner's act (POST /api/races/owner-entry, after the committee review).
   Withdrawing was still open to the Head Trainer for any entry, so an adviser could undo the owner's decision.

   Now:
     - the owner is the only one who withdraws a confirmed entry;
     - the Head Trainer may retract a recommendation the owner has not acted on
       yet (it is still their own advice), and otherwise RECOMMENDS a withdrawal:
       the reason is stored on the entry and the owner is notified;
     - cancelling a whole race (Club Manager) still scratches its entries:
       that is the race disappearing, not anyone's choice about the horse.

   RACE_REGISTRATION gains the latest withdrawal advice. It stays on the row
   after the owner acts, as a record of what was advised.
   ============================================================================= */

IF COL_LENGTH('RACE_REGISTRATION', 'withdraw_advice') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD withdraw_advice NVARCHAR(500) NULL;
GO
IF COL_LENGTH('RACE_REGISTRATION', 'withdraw_advice_at') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD withdraw_advice_at DATETIME2 NULL;
GO
IF COL_LENGTH('RACE_REGISTRATION', 'withdraw_advice_by') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD withdraw_advice_by INT NULL;
GO
IF OBJECT_ID('FK_RREG_advice_by', 'F') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT FK_RREG_advice_by
        FOREIGN KEY (withdraw_advice_by) REFERENCES APP_USER(user_id);
GO

IF COL_LENGTH('RACE_REGISTRATION', 'withdraw_advice_by') IS NULL
    THROW 50550, N'V55: withdrawal advice columns were not created.', 1;
GO

PRINT 'V55 done.';
GO
