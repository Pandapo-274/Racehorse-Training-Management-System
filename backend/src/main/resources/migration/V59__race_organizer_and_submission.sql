/* =============================================================================
   V59 - Races are run by an organizer; the club submits its owners' entries.

   The race calendar holds outside races (the JRA G1 races of V35) and races the
   club runs itself. Nothing said who runs a race, nor whether a confirmed entry
   had actually been sent to that organizer. Now:

     RACE.organizer                    who runs the race: "Japan Racing Association
                                       (JRA)" for the JRA racecourses, "Tracen Club"
                                       for the older club races without a venue
     RACE_REGISTRATION.submitted_at    when the Club Manager sent the entry to the
     RACE_REGISTRATION.submitted_by    organizer, and the organizer's reference
     RACE_REGISTRATION.organizer_ref   number if it gave one

   An entry that already has a result evidently ran, so it is marked submitted on
   the day the owner confirmed it (or the race day). Confirmed entries without a
   result stay unsubmitted for the Club Manager to send. No row is deleted.
   Every statement checks first, so the file can run twice.
   ============================================================================= */

IF COL_LENGTH('RACE', 'organizer') IS NULL
    ALTER TABLE RACE ADD organizer NVARCHAR(100) NULL;
GO

UPDATE RACE SET organizer = N'Japan Racing Association (JRA)'
WHERE organizer IS NULL AND venue LIKE N'%Racecourse%';
UPDATE RACE SET organizer = N'Tracen Club'
WHERE organizer IS NULL;
GO

IF COL_LENGTH('RACE_REGISTRATION', 'submitted_at') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD submitted_at DATETIME2 NULL;
GO
IF COL_LENGTH('RACE_REGISTRATION', 'submitted_by') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD submitted_by INT NULL;
GO
IF COL_LENGTH('RACE_REGISTRATION', 'organizer_ref') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD organizer_ref NVARCHAR(50) NULL;
GO
IF OBJECT_ID('FK_REG_submitted_by', 'F') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT FK_REG_submitted_by
        FOREIGN KEY (submitted_by) REFERENCES APP_USER(user_id);
GO

UPDATE RACE_REGISTRATION
SET submitted_at = COALESCE(owner_decision_at, CAST(race_date AS DATETIME2))
WHERE submitted_at IS NULL AND approval_status = 'APPROVED'
  AND result IN ('WON', 'PLACED', 'UNPLACED');
GO

IF COL_LENGTH('RACE', 'organizer') IS NULL OR COL_LENGTH('RACE_REGISTRATION', 'organizer_ref') IS NULL
    THROW 50590, N'V59: race organizer columns were not created.', 1;
GO

PRINT 'V59 done.';
GO
