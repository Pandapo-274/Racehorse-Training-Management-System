/* =============================================================================
   V64 - A horse that left comes back through the registration flow.

   Until now the manager pressed "Re-admit" and the horse was back in a stall:
   no request from the owner, no new photo or measurements, no review basis, no
   contract, no payment, no end date, no Head Trainer. A horse the club had
   withdrawn because its stay ended unpaid could come back the same way.

   Now the owner asks to bring the horse back. The request is an ordinary
   HORSE_REQUEST, prefilled from the horse and pointing at it:

     return_of_horse_id : the horse coming back. Identity (name, breed, sex, date
                          of birth, colour, parents) is taken from the horse, not
                          typed again. Photo, weight, height, aptitude, stay,
                          training window, preferred trainer and clause 8 contacts
                          are given fresh.

   It is reviewed, offered, signed and paid like a new registration. At "Receive"
   the club brings the SAME horse back (same registration code, history kept)
   instead of creating one, and the stay starts that day.
   ============================================================================= */

IF COL_LENGTH('HORSE_REQUEST', 'return_of_horse_id') IS NULL
    ALTER TABLE HORSE_REQUEST ADD return_of_horse_id INT NULL
        CONSTRAINT FK_HREQ_return REFERENCES HORSE(horse_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_HREQ_return')
    CREATE INDEX IX_HREQ_return ON HORSE_REQUEST(return_of_horse_id) WHERE return_of_horse_id IS NOT NULL;
GO
