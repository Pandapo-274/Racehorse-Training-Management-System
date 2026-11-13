/* =============================================================================
   V41 - Parents are declared when the horse is registered.

   A horse is born with its sire and dam: they are a fact from its papers, not
   something to pick later. So the registration request now carries them, the
   club checks them against the papers when it approves, and approval copies
   them onto the new horse. After that the owner can only fill in a parent that
   is still unknown; correcting one already on record is the club manager's job
   (PUT /api/horses/{id}/parents, with a reason in the audit log).

   Two columns, no new table: HORSE_REQUEST.sire_id / dam_id -> HORSE.
   ============================================================================= */

IF COL_LENGTH('HORSE_REQUEST', 'sire_id') IS NULL
    ALTER TABLE HORSE_REQUEST ADD sire_id INT NULL;
GO

IF COL_LENGTH('HORSE_REQUEST', 'dam_id') IS NULL
    ALTER TABLE HORSE_REQUEST ADD dam_id INT NULL;
GO

IF OBJECT_ID('FK_HREQ_sire', 'F') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT FK_HREQ_sire FOREIGN KEY (sire_id) REFERENCES HORSE(horse_id);
GO

IF OBJECT_ID('FK_HREQ_dam', 'F') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT FK_HREQ_dam FOREIGN KEY (dam_id) REFERENCES HORSE(horse_id);
GO

IF COL_LENGTH('HORSE_REQUEST', 'sire_id') IS NULL OR COL_LENGTH('HORSE_REQUEST', 'dam_id') IS NULL
    THROW 50410, N'V41: HORSE_REQUEST.sire_id / dam_id were not created.', 1;
GO

PRINT 'V41 done.';
GO
