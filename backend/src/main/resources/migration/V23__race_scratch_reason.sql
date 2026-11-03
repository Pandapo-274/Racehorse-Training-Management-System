/* =============================================================================
   V23 - The reason a horse was scratched gets its own column (#6).

   Until now the reason was glued in front of the entry's note
   ("Scratched: <reason> | <note>") and the result cut at 255 characters, so a
   long note lost its end. The audit already kept the full reason (V20); this
   keeps it on the entry too, and leaves the note alone.

   Old scratched rows keep their reason inside the note: splitting it back out
   by text matching would guess, and a guess in a record is worse than an old
   format.
   ============================================================================= */

IF COL_LENGTH('RACE_REGISTRATION', 'scratch_reason') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD scratch_reason NVARCHAR(500) NULL;
GO

IF OBJECT_ID('CK_REG_scratch_reason', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_scratch_reason
        CHECK (scratch_reason IS NULL OR result = 'SCRATCHED');
GO

PRINT 'V23 done.';
GO
