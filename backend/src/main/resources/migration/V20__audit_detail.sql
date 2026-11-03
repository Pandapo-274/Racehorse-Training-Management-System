/* =============================================================================
   V20 - What changed, not just which row.

   PROBLEM
   AUDIT_LOG records who did what to which id. For a correction that is not
   enough: after a race result is overwritten, a cost is voided or a stock
   count is replaced, the audit says "RACE_RESULT on #12" but not that WON with
   100,000,000 became PLACED with 40,000,000. The old value is gone everywhere.

   FIX
   One nullable text column holding a short human-readable description of the
   change, written by the service at the moment it knows both values, e.g.
     "WON, 100000000 -> PLACED, 40000000"
     "Yến mạch (Kho A): 540 -> 150 kg"
     "TM-0481 Symboli Rudolf, FEEDING, 2500000 on 2026-11-02. Reason: Entered twice"

   Existing rows keep NULL: the detail was never recorded for them.
   ============================================================================= */

IF COL_LENGTH('AUDIT_LOG', 'detail') IS NULL
    ALTER TABLE AUDIT_LOG ADD detail NVARCHAR(500) NULL;
GO

IF COL_LENGTH('AUDIT_LOG', 'detail') IS NULL
    THROW 50200, N'V20: AUDIT_LOG.detail was not created.', 1;

PRINT 'V20 done.';
GO
