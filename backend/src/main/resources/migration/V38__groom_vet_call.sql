/* =============================================================================
   V38 - A groom stopping a session for an injury also creates a VET_CALL, so
   the call shows up in the veterinarian's "Vet calls" queue just like a call
   made by a head trainer.

   - VET_CALL.alert_id becomes NULL-able (a groom call has no threshold alert).
   - alert_id stays unique when present (filtered unique index).
   - Backfill: injury reports already filed from a stopped session get a call.

   Re-runnable.
   ============================================================================= */

IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = 'UQ_VETCALL_alert')
    ALTER TABLE VET_CALL DROP CONSTRAINT UQ_VETCALL_alert;
GO

ALTER TABLE VET_CALL ALTER COLUMN alert_id INT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_VETCALL_alert' AND object_id = OBJECT_ID('VET_CALL'))
    CREATE UNIQUE INDEX UX_VETCALL_alert ON VET_CALL(alert_id) WHERE alert_id IS NOT NULL;
GO

INSERT INTO VET_CALL (alert_id, session_id, horse_id, called_by, reason, observations, urgency,
                      session_stopped, called_at, status, report_id)
SELECT NULL, ir.session_id, ir.horse_id, ir.reporter_id,
       LEFT(ir.symptoms, 500), LEFT(ir.description, 1000), ir.urgency,
       1, ir.reported_at, 'PENDING', ir.report_id
FROM INCIDENT_REPORT ir
WHERE ir.session_id IS NOT NULL
  AND ir.status IN ('PENDING', 'RECEIVED')
  AND NOT EXISTS (SELECT 1 FROM VET_CALL vc WHERE vc.session_id = ir.session_id);
GO

PRINT 'V38 done.';
GO
