/* =============================================================================
   V61 - A horse sold during its stay: the new owner signs their own contract.

   Before: a transfer moved the running contract to the new owner (owner_id).
   The buyer became bound by a contract they never read or signed, the seller
   lost the contract they had signed and paid from their account, and renewals
   kept the seller's insurer and second contact (taken from the seller's
   registration request) in clause 8.

   Now a transfer ENDS the seller's contract (it stays in the seller's account)
   and sends the buyer a change-of-owner contract for the rest of the same stay:
   nothing to pay (the seller paid for it), the buyer's insurer and second
   contact in clause 8. It becomes ACTIVE the moment the buyer signs. Until then
   the horse takes no new training plan and no new race entry.

     takes_over_contract_id : the contract whose stay this one continues
                              (NULL for a registration or a renewal).
     insurer_* / emergency_*: clause 8 contacts held by the contract itself, so
                              a renewal copies them from the running contract
                              instead of from the original registration request.
   ============================================================================= */

IF COL_LENGTH('HORSE_CONTRACT', 'takes_over_contract_id') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD takes_over_contract_id INT NULL
        CONSTRAINT FK_HCON_takes_over REFERENCES HORSE_CONTRACT(contract_id);
IF COL_LENGTH('HORSE_CONTRACT', 'insurer_name') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD insurer_name NVARCHAR(100) NULL;
IF COL_LENGTH('HORSE_CONTRACT', 'insurer_phone') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD insurer_phone VARCHAR(20) NULL;
IF COL_LENGTH('HORSE_CONTRACT', 'emergency_name') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD emergency_name NVARCHAR(100) NULL;
IF COL_LENGTH('HORSE_CONTRACT', 'emergency_phone') IS NULL
    ALTER TABLE HORSE_CONTRACT ADD emergency_phone VARCHAR(20) NULL;
GO

-- Contracts made so far carry the contacts of the request they came from.
UPDATE c
SET insurer_name = q.insurer_name, insurer_phone = q.insurer_phone,
    emergency_name = q.emergency_name, emergency_phone = q.emergency_phone
FROM HORSE_CONTRACT c
JOIN HORSE_REQUEST q ON q.request_id = c.request_id
WHERE c.insurer_name IS NULL AND c.emergency_name IS NULL;
GO

-- At most one change-of-owner contract waiting per horse.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_HCON_takeover')
    CREATE UNIQUE INDEX UX_HCON_takeover ON HORSE_CONTRACT(horse_id)
        WHERE status = 'SENT' AND takes_over_contract_id IS NOT NULL;
GO
