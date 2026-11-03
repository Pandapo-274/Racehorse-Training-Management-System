/* =============================================================================
   V21 - Feeding uses up stock, and every stock change is written down (#10, #11).

   PROBLEM
   Three things that should be one chain were not connected:
     FEEDING_SCHEDULE.feed_type   free text, no link to the store
     DAILY_TASK 'FEEDING'          ticking it done changed nothing
     SUPPLY_STOCK.current_qty      only ever overwritten by a count
   So the store never went down when horses ate, and a count replaced the
   old number with no trace of what it had been.

   FIX
   1. SUPPLY_MOVEMENT: one row for every change to current_qty - received,
      used by a task, given back when a task is reopened, adjusted by a count,
      thrown away. current_qty stays the live balance; this is its history.
   2. FEEDING_SCHEDULE.supply_id: a ration line points at the stock item it is
      taken from. Nullable, because lines written before V21 may not match
      anything; the service requires it for every NEW line.

   UNITS - why the back-fill below also compares the unit
   A ration line's quantity is in the ration's unit, and it is subtracted
   from current_qty, which is in the stock's unit. In the demo data
   "Cỏ khô timothy" is 5 kg in the ration but counted in kiện (bales) in the
   store. Linking by name alone would take 5 BALES per feed. So an old line is
   linked only when the name AND the unit match, and only when exactly one
   FEED item matches. Everything else stays NULL and the Feeding screen asks
   the Head Trainer to pick the stock item again.
   ============================================================================= */

IF OBJECT_ID('SUPPLY_MOVEMENT', 'U') IS NULL
CREATE TABLE SUPPLY_MOVEMENT (
    movement_id   INT IDENTITY(1,1) PRIMARY KEY,
    supply_id     INT           NOT NULL,
    movement_type VARCHAR(12)   NOT NULL,
    qty_delta     DECIMAL(8,2)  NOT NULL,   -- positive = into the store, negative = out
    qty_after     DECIMAL(8,2)  NOT NULL,   -- current_qty right after this movement
    ref_type      VARCHAR(20)   NULL,       -- 'DAILY_TASK', ...
    ref_id        INT           NULL,
    reason        NVARCHAR(255) NULL,
    created_by    INT           NOT NULL,
    created_at    DATETIME2     NOT NULL CONSTRAINT DF_MOV_time DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_MOV_supply FOREIGN KEY (supply_id)  REFERENCES SUPPLY_STOCK(supply_id),
    CONSTRAINT FK_MOV_user   FOREIGN KEY (created_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_MOV_type   CHECK (movement_type IN ('RECEIVE','CONSUME','REVERSAL','ADJUST','WASTE')),
    CONSTRAINT CK_MOV_delta  CHECK (qty_delta <> 0),
    CONSTRAINT CK_MOV_after  CHECK (qty_after >= 0)
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_MOV_supply')
    CREATE INDEX IX_MOV_supply ON SUPPLY_MOVEMENT(supply_id, created_at DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_MOV_ref')
    CREATE INDEX IX_MOV_ref ON SUPPLY_MOVEMENT(ref_type, ref_id);
GO

/* Opening balance: without it, the first movement of every existing item
   would have no starting point in the history. */
INSERT INTO SUPPLY_MOVEMENT (supply_id, movement_type, qty_delta, qty_after, reason, created_by)
SELECT s.supply_id, 'ADJUST', s.current_qty, s.current_qty, N'Opening balance (V21)', s.updated_by
FROM SUPPLY_STOCK s
WHERE s.current_qty > 0
  AND NOT EXISTS (SELECT 1 FROM SUPPLY_MOVEMENT m WHERE m.supply_id = s.supply_id);
GO

IF COL_LENGTH('FEEDING_SCHEDULE', 'supply_id') IS NULL
    ALTER TABLE FEEDING_SCHEDULE ADD supply_id INT NULL;
GO

IF OBJECT_ID('FK_FEED_supply', 'F') IS NULL
    ALTER TABLE FEEDING_SCHEDULE ADD CONSTRAINT FK_FEED_supply
        FOREIGN KEY (supply_id) REFERENCES SUPPLY_STOCK(supply_id);
GO

/* Back-fill: same name, same unit, exactly one FEED item. See UNITS above. */
UPDATE f
SET f.supply_id = m.supply_id
FROM FEEDING_SCHEDULE f
CROSS APPLY (
    SELECT MIN(s.supply_id) AS supply_id, COUNT(*) AS matches
    FROM SUPPLY_STOCK s
    WHERE s.category = 'FEED'
      AND LTRIM(RTRIM(s.supply_name)) = LTRIM(RTRIM(f.feed_type))
      AND LTRIM(RTRIM(s.unit)) = LTRIM(RTRIM(f.unit))
) m
WHERE f.supply_id IS NULL
  AND m.matches = 1;
GO

DECLARE @linked INT = (SELECT COUNT(*) FROM FEEDING_SCHEDULE WHERE supply_id IS NOT NULL);
DECLARE @unlinked INT = (SELECT COUNT(*) FROM FEEDING_SCHEDULE WHERE supply_id IS NULL);
PRINT CONCAT('V21: ration lines linked to stock = ', @linked,
             ', still to link by hand = ', @unlinked, '.');

IF OBJECT_ID('SUPPLY_MOVEMENT', 'U') IS NULL
    THROW 50210, N'V21: SUPPLY_MOVEMENT was not created.', 1;
IF COL_LENGTH('FEEDING_SCHEDULE', 'supply_id') IS NULL
    THROW 50211, N'V21: FEEDING_SCHEDULE.supply_id was not created.', 1;

PRINT 'V21 done.';
GO
