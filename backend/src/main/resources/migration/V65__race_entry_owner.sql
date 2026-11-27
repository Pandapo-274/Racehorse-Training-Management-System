/* =============================================================================
   V65 - A race entry belongs to the owner who entered the horse.

   Race entries were read through HORSE.owner_id, the CURRENT owner. After a
   sale the buyer saw the seller's past races and prize money as their own, and
   the seller lost them from "Results & races" and "Costs & prizes".

     RACE_REGISTRATION.owner_id : the owner of the horse when it was entered.
                                  Results and prize money stay with that owner.

   A sale is refused while an entry is confirmed and has no result yet (the
   service checks it), so the owner who entered is also the owner on race day
   and when the result comes in.

   Existing rows take the horse's current owner: the history of earlier sales
   is not recorded anywhere else. Every statement checks first, so the file can
   run twice.
   ============================================================================= */

IF COL_LENGTH('RACE_REGISTRATION', 'owner_id') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD owner_id INT NULL;
GO

UPDATE r SET owner_id = h.owner_id
FROM RACE_REGISTRATION r JOIN HORSE h ON h.horse_id = r.horse_id
WHERE r.owner_id IS NULL;
GO

IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID('RACE_REGISTRATION') AND name = 'owner_id' AND is_nullable = 1)
    ALTER TABLE RACE_REGISTRATION ALTER COLUMN owner_id INT NOT NULL;
GO

IF OBJECT_ID('FK_REG_owner', 'F') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT FK_REG_owner
        FOREIGN KEY (owner_id) REFERENCES APP_USER(user_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_REG_owner')
    CREATE INDEX IX_REG_owner ON RACE_REGISTRATION(owner_id);
GO

IF COL_LENGTH('RACE_REGISTRATION', 'owner_id') IS NULL
    THROW 50650, N'V65: RACE_REGISTRATION.owner_id was not created.', 1;
GO

PRINT 'V65 done.';
GO
