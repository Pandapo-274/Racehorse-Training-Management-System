/* =============================================================================
   V62 - Withdrawal under the boarding contract (V49), clause 9:
         "The owner may end the stay with 15 days' notice through a withdrawal
          request. Months already started are not refunded; months not started are."

   Until now a withdrawal had no leave day (approving it sent the horse away at
   once) and no refund, so the contract promised what the system did not do.

     leave_on       : the day the owner collects the horse. The manager approves
                      on or after that day; until then the horse keeps training.
     notice_until   : the stay is paid until max(leave_on, request day + 15).
     refund_months  : months of the running contract that start after notice_until.
     refund_amount  : their share of what the owner paid; the club owes it (payOS
                      cannot send money back, so the club refunds by hand).
     contract_id    : the contract that ended with this withdrawal.
     by_club        : the club withdrew the horse itself - only when its stay ended
                      without renewal and the owner did not ask (the "Ended - follow
                      up" list had no way out before).
   ============================================================================= */

IF COL_LENGTH('HORSE_WITHDRAWAL', 'leave_on') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD leave_on DATE NULL;
IF COL_LENGTH('HORSE_WITHDRAWAL', 'notice_until') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD notice_until DATE NULL;
IF COL_LENGTH('HORSE_WITHDRAWAL', 'refund_months') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD refund_months INT NULL;
IF COL_LENGTH('HORSE_WITHDRAWAL', 'refund_amount') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD refund_amount DECIMAL(12, 0) NULL;
IF COL_LENGTH('HORSE_WITHDRAWAL', 'contract_id') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD contract_id INT NULL
        CONSTRAINT FK_HWD_contract REFERENCES HORSE_CONTRACT(contract_id);
IF COL_LENGTH('HORSE_WITHDRAWAL', 'by_club') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD by_club BIT NOT NULL CONSTRAINT DF_HWD_by_club DEFAULT 0;
GO

IF OBJECT_ID('CK_HWD_refund', 'C') IS NULL
    ALTER TABLE HORSE_WITHDRAWAL ADD CONSTRAINT CK_HWD_refund
        CHECK (refund_amount IS NULL OR (refund_amount >= 0 AND refund_months >= 0));
GO
