/* =============================================================================
   V60 - The stable plan: which stalls exist, so the system can say which are free.

   Until now a stall was free text on HORSE.stall_no; UX_HORSE_stall kept two
   horses out of one stall, but nothing knew which stalls the club has. So the
   manager could not be shown the empty ones, and a typo ("B-300") became a stall.

   STALL lists the club's stalls. A stall is free when no horse has its number:
   occupancy stays on HORSE.stall_no (and UX_HORSE_stall), it is not copied here,
   so the two can never disagree.

   Seeded with the club's barns - A (12 stalls), B (30), C (15) - plus every stall
   a horse already uses, so no existing record falls off the plan.
   The service refuses a stall that is not on the plan when a horse is received,
   created, re-admitted or moved; a horse may keep an old stall it already has.
   ============================================================================= */

IF OBJECT_ID('STALL', 'U') IS NULL
BEGIN
    CREATE TABLE STALL (
        stall_no     VARCHAR(20) NOT NULL CONSTRAINT PK_STALL PRIMARY KEY,
        barn         VARCHAR(10) NOT NULL,
        stall_number INT         NOT NULL,
        created_at   DATETIME2   NOT NULL CONSTRAINT DF_STALL_created DEFAULT SYSUTCDATETIME()
    );
    CREATE INDEX IX_STALL_order ON STALL(barn, stall_number);
END
GO

-- The club's barns.
;WITH n AS (
    SELECT TOP (30) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS k FROM sys.all_objects
), plan_rows AS (
    SELECT b.barn, n.k
    FROM (VALUES ('A', 12), ('B', 30), ('C', 15)) AS b(barn, size)
    JOIN n ON n.k <= b.size
)
INSERT INTO STALL (stall_no, barn, stall_number)
SELECT p.barn + '-' + RIGHT('0' + CAST(p.k AS VARCHAR(3)), 2), p.barn, p.k
FROM plan_rows p
WHERE NOT EXISTS (SELECT 1 FROM STALL s WHERE s.stall_no = p.barn + '-' + RIGHT('0' + CAST(p.k AS VARCHAR(3)), 2));
GO

-- Every stall already in use stays on the plan ("Z-07" -> barn Z, number 7).
INSERT INTO STALL (stall_no, barn, stall_number)
SELECT DISTINCT h.stall_no,
       CASE WHEN CHARINDEX('-', h.stall_no) > 1 THEN LEFT(h.stall_no, CHARINDEX('-', h.stall_no) - 1)
            ELSE LEFT(h.stall_no, 10) END,
       COALESCE(TRY_CAST(SUBSTRING(h.stall_no, CHARINDEX('-', h.stall_no) + 1, 20) AS INT), 0)
FROM HORSE h
WHERE h.stall_no IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM STALL s WHERE s.stall_no = h.stall_no);
GO
