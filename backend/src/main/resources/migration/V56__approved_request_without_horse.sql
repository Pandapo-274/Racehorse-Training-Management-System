/* =============================================================================
   V56 - An approved club horse waits for its contract without a horse record.

   CK_HREQ_horse (V5) said an APPROVED request must point at a horse, because
   approving used to create the horse at once. Since V49, approving a club horse
   only sends the contract: the horse is created when the club receives it,
   after the owner signed and paid (ContractService.receive, which then fills
   HORSE_REQUEST.horse_id). The old rule made every club-horse approval fail
   with "This change conflicts with existing data".

   New rule: only an APPROVED request may point at a horse. A pedigree record
   still gets its horse at approval; a club horse gets it on arrival.
   Every existing row already meets the new rule (it is looser than the old
   one), and no row is changed.

   The file can run twice.
   ============================================================================= */

IF OBJECT_ID('CK_HREQ_horse', 'C') IS NOT NULL
    ALTER TABLE HORSE_REQUEST DROP CONSTRAINT CK_HREQ_horse;
GO

ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_horse
    CHECK (status = 'APPROVED' OR horse_id IS NULL);
GO

IF OBJECT_ID('CK_HREQ_horse', 'C') IS NULL
    THROW 50560, N'V56: CK_HREQ_horse was not recreated.', 1;
GO

PRINT 'V56 done.';
GO
