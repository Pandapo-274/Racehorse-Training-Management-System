/* =============================================================================
   V24 - Store catalogue rules (#11, #12).

   1. One name per area. Two "Yến mạch" rows in Kho A meant two balances for
      one bin, and a count could land on either. UNIQUE (supply_name, area).
      SQL Server treats NULLs as equal in a UNIQUE constraint, so only one item
      of a given name may have no area - give every item an area.
   2. expiry_date for MEDICINE. One date per item, not per batch: enough to
      warn and to block expired stock; batches would need a SUPPLY_BATCH table.
   3. last_reminded_on: the daily store check (SupplyDigestJob) writes today's
      date here, so a second run on the same day sends nothing.

   If the database already holds duplicate names in one area, the constraint is
   NOT created (the migration PRINTs the duplicates instead of failing - your
   data, not this script, decides). The service still refuses new duplicates
   with 409. Merge the duplicates by hand, then add the constraint:
     ALTER TABLE SUPPLY_STOCK ADD CONSTRAINT UQ_SUP_name_area UNIQUE (supply_name, area);
   ============================================================================= */

IF COL_LENGTH('SUPPLY_STOCK', 'expiry_date') IS NULL
    ALTER TABLE SUPPLY_STOCK ADD expiry_date DATE NULL;
GO

IF COL_LENGTH('SUPPLY_STOCK', 'last_reminded_on') IS NULL
    ALTER TABLE SUPPLY_STOCK ADD last_reminded_on DATE NULL;
GO

IF OBJECT_ID('CK_SUP_expiry', 'C') IS NULL
    ALTER TABLE SUPPLY_STOCK ADD CONSTRAINT CK_SUP_expiry
        CHECK (expiry_date IS NULL OR category = 'MEDICINE');
GO

IF OBJECT_ID('UQ_SUP_name_area', 'UQ') IS NULL
BEGIN
    IF EXISTS (SELECT 1 FROM SUPPLY_STOCK GROUP BY supply_name, area HAVING COUNT(*) > 1)
    BEGIN
        DECLARE @dups INT = (SELECT COUNT(*) FROM (SELECT supply_name, area FROM SUPPLY_STOCK
                             GROUP BY supply_name, area HAVING COUNT(*) > 1) d);
        PRINT CONCAT(N'V24: UQ_SUP_name_area NOT created, ', @dups, N' duplicated name/area pair(s). Find them with: ',
                     N'SELECT supply_name, area, COUNT(*) FROM SUPPLY_STOCK GROUP BY supply_name, area HAVING COUNT(*) > 1');
    END
    ELSE
        ALTER TABLE SUPPLY_STOCK ADD CONSTRAINT UQ_SUP_name_area UNIQUE (supply_name, area);
END
GO

IF COL_LENGTH('SUPPLY_STOCK', 'expiry_date') IS NULL
    THROW 50240, N'V24: SUPPLY_STOCK.expiry_date was not created.', 1;

PRINT 'V24 done.';
GO
