/* =============================================================================
   V54 - Review round 2: the contract's insurance clause and one running-style list.

   1. Clause 8 of the contract says the owner gives the club the insurer's
      emergency number and a second person who can decide for the horse, but
      nothing collected them. HORSE_REQUEST now stores both. The columns stay
      NULL for requests sent before this version; the service requires them on
      every new club-horse request.

   2. V48 stored running styles as FRONT / MIDDLE / BACK and V50 moved the
      training templates to FRONT / STALKER / CLOSER. V52 converted HORSE only,
      so requests kept the old names and the two lists no longer matched.
      Both tables now use FRONT / STALKER / CLOSER. No row is deleted.

   Every statement checks first, so the file can run twice.
   ============================================================================= */

IF COL_LENGTH('HORSE_REQUEST', 'insurer_name') IS NULL
    ALTER TABLE HORSE_REQUEST ADD insurer_name NVARCHAR(100) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'insurer_phone') IS NULL
    ALTER TABLE HORSE_REQUEST ADD insurer_phone VARCHAR(20) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'emergency_name') IS NULL
    ALTER TABLE HORSE_REQUEST ADD emergency_name NVARCHAR(100) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'emergency_phone') IS NULL
    ALTER TABLE HORSE_REQUEST ADD emergency_phone VARCHAR(20) NULL;
GO

UPDATE HORSE_REQUEST SET running_style = 'STALKER' WHERE running_style = 'MIDDLE';
UPDATE HORSE_REQUEST SET running_style = 'CLOSER' WHERE running_style = 'BACK';
UPDATE HORSE SET running_style = 'STALKER' WHERE running_style = 'MIDDLE';
UPDATE HORSE SET running_style = 'CLOSER' WHERE running_style = 'BACK';
GO

IF COL_LENGTH('HORSE_REQUEST', 'emergency_phone') IS NULL
    THROW 50540, N'V54: owner contact columns were not created.', 1;
GO

PRINT 'V54 done.';
GO
