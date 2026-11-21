/* =============================================================================
   Remove the Flow 1 DEMO data (everything flow1-demo-data.sql made, plus what
   the demo created with it), when Flow 1 is done.

   Removes:
     - accounts lan.pn, minh.td, quang.hd, phuc.hv, khoa.lv, an.nv
       (and the "an.nv.old..." copies the demo script left behind);
     - horses DM-* and every horse owned by lan.pn / minh.td
       (Morning Star DM-08 and anything else created during the demo);
     - their registration requests, withdrawals, training plans and phases,
       training sessions, care tasks, password-reset links, notifications and
       audit-log lines (also the manager's lines about them).

   Leaves the rest as it was, except links that would point at removed data:
     - another horse with a DM horse as sire / dam keeps its record, without that parent;
     - another owner's registration request naming a DM horse as parent: parent cleared;
     - a horse or groom of another team with Head Trainer quang.hd: trainer cleared.

   Safe: it first checks every other table that points at these accounts or
   horses (vet calls, alerts, incident reports, medical records... from other
   flows). If one does, it stops and names the table, and deletes nothing.

   Local copy of Downloads\flow1-remove-demo-data.sql with two fixes:
     - Step 0: seed horses that were transferred to lan.pn while testing
       (Air Groove TM-0332, Agnes Tachyon TM-A014) go back to their original
       owner first, so they are not swept up as "horses owned by lan.pn".
     - PREVENTIVE_REMINDER_LOG rows of the demo horses are deleted with them:
       the reminder job writes them by itself, they are not another flow's data.
     - Withdrawal requests a demo account made (lan.pn asked to withdraw Air
       Groove while testing, still PENDING) are deleted too.
   ============================================================================= */
USE rtms;
GO

SET NOCOUNT ON;
SET XACT_ABORT ON;

IF OBJECT_ID('APP_USER', 'U') IS NULL
    THROW 50899, N'APP_USER not found: this is not the RTMS database. Pick the database the backend uses.', 1;

/* ---- 0. Give seed horses moved to lan.pn during testing back (audit log, 2026-11-11) ---- */
UPDATE h SET owner_id = o.user_id
FROM HORSE h
JOIN APP_USER cur ON cur.user_id = h.owner_id AND cur.username = 'lan.pn'
JOIN (VALUES ('TM-0332', 'trang.lt'),        -- Air Groove: Lý Thu Trang
             ('TM-A014', 'bloodstock.tm'))   -- Agnes Tachyon: Tracen Bloodstock
     AS back(code, username) ON back.code = h.registration_code
JOIN APP_USER o ON o.username = back.username;
PRINT CONCAT('Seed horses given back to their original owner: ', @@ROWCOUNT);

IF OBJECT_ID('tempdb..#u') IS NOT NULL DROP TABLE #u;
IF OBJECT_ID('tempdb..#h') IS NOT NULL DROP TABLE #h;
CREATE TABLE #u (id INT PRIMARY KEY);
CREATE TABLE #h (id INT PRIMARY KEY);

INSERT #u SELECT user_id FROM APP_USER
          WHERE username IN ('lan.pn', 'minh.td', 'quang.hd', 'phuc.hv', 'khoa.lv', 'an.nv')
             OR username LIKE 'an.nv.old%';
INSERT #h SELECT horse_id FROM HORSE
          WHERE registration_code LIKE 'DM-%' OR owner_id IN (SELECT id FROM #u);

DECLARE @users INT = (SELECT COUNT(*) FROM #u), @horses INT = (SELECT COUNT(*) FROM #h);
IF @users = 0 AND @horses = 0
BEGIN
    PRINT 'No demo data found. Nothing to remove.';
    RETURN;
END

/* ---- 1. Anything else pointing at them? Then stop, do not half-delete. ----
   Every foreign key to APP_USER or HORSE is checked. Rows this script deletes
   itself (plans, sessions, care tasks, requests and withdrawals of the demo
   horses) are left out of the count; rows of other horses are not. */
DECLARE @sql NVARCHAR(MAX) = N'', @found NVARCHAR(MAX);

SELECT @sql = @sql + N' UNION ALL SELECT N''' + t.name + N'.' + c.name + N''' AS ref, COUNT(*) AS n FROM '
            + QUOTENAME(s.name) + N'.' + QUOTENAME(t.name) + N' WHERE ' + QUOTENAME(c.name)
            + N' IN (SELECT id FROM ' + CASE rt.name WHEN 'APP_USER' THEN N'#u' ELSE N'#h' END + N')'
            + CASE
                WHEN t.name IN ('TRAINING_SESSION', 'TRAINING_PLAN', 'DAILY_TASK')
                    THEN N' AND horse_id NOT IN (SELECT id FROM #h)'
                WHEN t.name = 'HORSE_WITHDRAWAL'
                    THEN N' AND horse_id NOT IN (SELECT id FROM #h) AND requested_by NOT IN (SELECT id FROM #u)'
                WHEN t.name = 'HORSE_REQUEST'
                    THEN N' AND owner_id NOT IN (SELECT id FROM #u) AND (horse_id IS NULL OR horse_id NOT IN (SELECT id FROM #h))'
                ELSE N''
              END
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
JOIN sys.tables t   ON t.object_id  = fk.parent_object_id
JOIN sys.schemas s  ON s.schema_id  = t.schema_id
JOIN sys.columns c  ON c.object_id  = fkc.parent_object_id AND c.column_id = fkc.parent_column_id
JOIN sys.tables rt  ON rt.object_id = fk.referenced_object_id
WHERE rt.name IN ('APP_USER', 'HORSE')
  -- handled below: deleted with the demo, or the link is cleared
  AND t.name NOT IN ('AUDIT_LOG', 'NOTIFICATION', 'PASSWORD_RESET_TOKEN', 'HORSE', 'APP_USER',
                     'PREVENTIVE_REMINDER_LOG')
  AND NOT (t.name = 'HORSE_REQUEST' AND c.name IN ('sire_id', 'dam_id'));

IF LEN(@sql) > 0
BEGIN
    SET @sql = N'SELECT @f = STRING_AGG(ref + N'' ('' + CAST(n AS NVARCHAR(10)) + N'' rows)'', N'', '') FROM ('
             + STUFF(@sql, 1, 11, N'') + N') x WHERE n > 0';
    EXEC sp_executesql @sql, N'@f NVARCHAR(MAX) OUTPUT', @f = @found OUTPUT;
END

-- Alerts, vet calls and incident reports on a demo horse's training session
-- (they point at the session, not always at the horse).
IF EXISTS (SELECT 1 FROM TRAINING_SESSION s WHERE s.horse_id IN (SELECT id FROM #h)
           AND (EXISTS (SELECT 1 FROM THRESHOLD_ALERT a WHERE a.session_id = s.session_id)
             OR EXISTS (SELECT 1 FROM VET_CALL v WHERE v.session_id = s.session_id)
             OR EXISTS (SELECT 1 FROM INCIDENT_REPORT r WHERE r.session_id = s.session_id)))
    SET @found = CONCAT(@found + N', ', N'alerts / vet calls / incident reports on the demo horses'' training sessions');

IF @found IS NOT NULL
BEGIN
    DECLARE @msg NVARCHAR(2048) = LEFT(N'Demo accounts/horses are still used by: ' + @found
        + N'. Nothing was deleted. This data came from another flow; remove it first (or keep the demo data).', 2048);
    THROW 50930, @msg, 1;
END

/* ---- 2. Delete, children first ---------------------------------------- */
BEGIN TRAN;

DECLARE @req  TABLE (id INT PRIMARY KEY);
DECLARE @wd   TABLE (id INT PRIMARY KEY);
DECLARE @plan TABLE (id INT PRIMARY KEY);
DECLARE @ses  TABLE (id INT PRIMARY KEY);
INSERT @req  SELECT request_id    FROM HORSE_REQUEST    WHERE owner_id IN (SELECT id FROM #u) OR horse_id IN (SELECT id FROM #h);
-- also a demo owner's request on a seed horse given back in step 0 (lan.pn → Air Groove, test)
INSERT @wd   SELECT withdrawal_id FROM HORSE_WITHDRAWAL WHERE horse_id IN (SELECT id FROM #h)
                                                          OR requested_by IN (SELECT id FROM #u);
INSERT @plan SELECT plan_id       FROM TRAINING_PLAN    WHERE horse_id IN (SELECT id FROM #h);
INSERT @ses  SELECT session_id    FROM TRAINING_SESSION WHERE horse_id IN (SELECT id FROM #h);

DELETE FROM NOTIFICATION
WHERE user_id IN (SELECT id FROM #u)
   OR (target_entity = 'HORSE'            AND target_id IN (SELECT id FROM #h))
   OR (target_entity = 'HORSE_REQUEST'    AND target_id IN (SELECT id FROM @req))
   OR (target_entity = 'HORSE_WITHDRAWAL' AND target_id IN (SELECT id FROM @wd))
   OR (target_entity = 'TRAINING_PLAN'    AND target_id IN (SELECT id FROM @plan))
   OR (target_entity = 'TRAINING_SESSION' AND target_id IN (SELECT id FROM @ses));
PRINT CONCAT('Notifications removed: ', @@ROWCOUNT);

DELETE FROM AUDIT_LOG
WHERE user_id IN (SELECT id FROM #u)
   OR (target_entity = 'APP_USER'         AND target_id IN (SELECT id FROM #u))
   OR (target_entity = 'HORSE'            AND target_id IN (SELECT id FROM #h))
   OR (target_entity = 'HORSE_REQUEST'    AND target_id IN (SELECT id FROM @req))
   OR (target_entity = 'HORSE_WITHDRAWAL' AND target_id IN (SELECT id FROM @wd))
   OR (target_entity = 'TRAINING_PLAN'    AND target_id IN (SELECT id FROM @plan))
   OR (target_entity = 'TRAINING_SESSION' AND target_id IN (SELECT id FROM @ses));
PRINT CONCAT('Audit lines removed: ', @@ROWCOUNT);

DELETE FROM PASSWORD_RESET_TOKEN WHERE user_id IN (SELECT id FROM #u);
DELETE FROM PREVENTIVE_REMINDER_LOG WHERE horse_id IN (SELECT id FROM #h);
PRINT CONCAT('Preventive reminder log lines removed: ', @@ROWCOUNT);

DELETE FROM TRAINING_SESSION WHERE session_id IN (SELECT id FROM @ses);
PRINT CONCAT('Training sessions removed: ', @@ROWCOUNT);
DELETE FROM DAILY_TASK       WHERE horse_id IN (SELECT id FROM #h);
PRINT CONCAT('Care tasks removed: ', @@ROWCOUNT);
DELETE FROM PLAN_PHASE       WHERE plan_id IN (SELECT id FROM @plan);
DELETE FROM TRAINING_PLAN    WHERE plan_id IN (SELECT id FROM @plan);
DELETE FROM HORSE_WITHDRAWAL WHERE withdrawal_id IN (SELECT id FROM @wd);
DELETE FROM HORSE_REQUEST    WHERE request_id IN (SELECT id FROM @req);

-- Links from data that stays: cleared, not deleted.
UPDATE HORSE_REQUEST SET sire_id = NULL WHERE sire_id IN (SELECT id FROM #h);
UPDATE HORSE_REQUEST SET dam_id  = NULL WHERE dam_id  IN (SELECT id FROM #h);
UPDATE HORSE SET sire_id = NULL WHERE sire_id IN (SELECT id FROM #h) AND horse_id NOT IN (SELECT id FROM #h);
UPDATE HORSE SET dam_id  = NULL WHERE dam_id  IN (SELECT id FROM #h) AND horse_id NOT IN (SELECT id FROM #h);
PRINT CONCAT('Other horses that lost a DM parent: ', @@ROWCOUNT);
UPDATE HORSE    SET trainer_id = NULL WHERE trainer_id IN (SELECT id FROM #u);
UPDATE APP_USER SET trainer_id = NULL WHERE trainer_id IN (SELECT id FROM #u);

-- Demo horses point at each other (sire / dam): clear first, then delete.
UPDATE HORSE SET sire_id = NULL, dam_id = NULL WHERE horse_id IN (SELECT id FROM #h);
DELETE FROM HORSE    WHERE horse_id IN (SELECT id FROM #h);
PRINT CONCAT('Horses removed: ', @@ROWCOUNT);
DELETE FROM APP_USER WHERE user_id IN (SELECT id FROM #u);
PRINT CONCAT('Accounts removed: ', @@ROWCOUNT);

COMMIT;

DROP TABLE #u;
DROP TABLE #h;

-- Should all be 0.
SELECT (SELECT COUNT(*) FROM APP_USER
        WHERE username IN ('lan.pn', 'minh.td', 'quang.hd', 'phuc.hv', 'khoa.lv', 'an.nv')
           OR username LIKE 'an.nv.old%')                              AS demo_accounts_left,
       (SELECT COUNT(*) FROM HORSE WHERE registration_code LIKE 'DM-%') AS demo_horses_left;
