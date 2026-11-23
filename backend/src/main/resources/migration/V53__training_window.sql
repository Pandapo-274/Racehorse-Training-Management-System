/* =============================================================================
   V50 - The owner asks for a training window: from which day, until which day.

   Feedback after V48: "when the owner registers a horse there is no requested
   start and end date of training". V48 only had training_weeks (a length).
   The request and the contract now carry the window itself:

     preferred_start   (V48) the day the owner brings the horse in
     training_start    first day of training asked for, not before preferred_start
     training_end      last day asked for: at least 21 days (3 weeks) after
                       training_start, and inside the stay
                       (no later than preferred_start + contract_months)

   training_weeks stays, computed from the two dates (whole weeks), so older
   screens and the contract text keep working.

   CK_HREQ_term (V48) also compared training_weeks with contract_months * 4.
   With real dates a 31-day month holds more than 4 weeks, so that clause
   would refuse a valid window; the date check below replaces it.

   Every statement checks first, so the file can run twice.
   ============================================================================= */

IF COL_LENGTH('HORSE_REQUEST', 'training_start') IS NULL
    ALTER TABLE HORSE_REQUEST ADD training_start DATE NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'training_end') IS NULL
    ALTER TABLE HORSE_REQUEST ADD training_end DATE NULL;
GO
IF COL_LENGTH('HORSE_CONTRACT', 'training_start') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD training_start DATE NULL;
GO
IF COL_LENGTH('HORSE_CONTRACT', 'training_end') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD training_end DATE NULL;
GO

IF OBJECT_ID('CK_HREQ_term', 'C') IS NOT NULL
    ALTER TABLE HORSE_REQUEST DROP CONSTRAINT CK_HREQ_term;
GO
ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_term CHECK (
    (contract_months IS NULL OR contract_months BETWEEN 1 AND 24)
    AND (training_weeks IS NULL OR training_weeks >= 3));
GO

IF OBJECT_ID('CK_HREQ_training_window', 'C') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_training_window CHECK (
        (training_start IS NULL OR training_end IS NULL
             OR training_end >= DATEADD(DAY, 21, training_start))
        AND (training_start IS NULL OR preferred_start IS NULL
             OR training_start >= preferred_start)
        AND (training_end IS NULL OR preferred_start IS NULL OR contract_months IS NULL
             OR training_end <= DATEADD(MONTH, contract_months, preferred_start)));
GO

IF COL_LENGTH('HORSE_REQUEST', 'training_end') IS NULL
   OR COL_LENGTH('HORSE_CONTRACT', 'training_end') IS NULL
    THROW 50500, N'V50: training window columns were not created.', 1;
GO

PRINT 'V50 done.';
GO
