/* =============================================================================
   V58 - Test-size prices: nothing the system charges is above 5,000 VND.

   payOS has no test environment: every payment is real money. So that the team
   can pay in demos, the amounts themselves are made small, and screens show the
   same number payOS charges.

     RACE.entry_fee                  / 45,000, rounded up to 100, kept within 1,000 .. 5,000
     RACE_REGISTRATION.prize_money   / 45,000, rounded up to 100, at most 10,000
                                     (same factor as the fee, so a prize still weighs
                                     against the fee as it did: 450,000,000 -> 10,000)
     EXPENSE_LOG.amount              a race fee already charged becomes the race's new fee;
                                     any other cost / 1,000, kept within 1,000 .. 5,000
                                     (unpaid lines only; a line paid online keeps its amount)
     SUPPLY_STOCK.unit_price         / 100, kept within 100 .. 500, so a prescription of up
     TREATMENT.unit_price            to 10 units stays within 5,000

   The monthly boarding fee is configuration, not data: application.properties
   now defaults to 1,000 VND (CONTRACT_MONTHLY_FEE), so 1- and 3-month stays are
   under 5,000. Contracts already sent keep the price frozen in their text.

   Each UPDATE only touches amounts still at real size, so running the file a
   second time changes nothing.
   ============================================================================= */

UPDATE RACE
SET entry_fee = CASE WHEN CEILING(entry_fee / 4500000.0) * 100 < 1000 THEN 1000
                     WHEN CEILING(entry_fee / 4500000.0) * 100 > 5000 THEN 5000
                     ELSE CEILING(entry_fee / 4500000.0) * 100 END
WHERE entry_fee >= 1000000;
GO

UPDATE RACE_REGISTRATION
SET prize_money = CASE WHEN CEILING(prize_money / 4500000.0) * 100 > 10000 THEN 10000
                       ELSE CEILING(prize_money / 4500000.0) * 100 END
WHERE prize_money >= 1000000;
GO

/* A race fee already charged to a horse matches its race's new fee. */
UPDATE e
SET e.amount = r.entry_fee
FROM EXPENSE_LOG e
JOIN RACE_REGISTRATION rr ON rr.fee_expense_id = e.expense_id
JOIN RACE r ON r.race_id = rr.race_id
WHERE e.paid_at IS NULL AND r.entry_fee IS NOT NULL AND e.amount <> r.entry_fee;
GO

UPDATE EXPENSE_LOG
SET amount = CASE WHEN CEILING(amount / 1000.0) < 1000 THEN 1000
                  WHEN CEILING(amount / 1000.0) > 5000 THEN 5000
                  ELSE CEILING(amount / 1000.0) END
WHERE amount >= 10000 AND paid_at IS NULL;
GO

UPDATE SUPPLY_STOCK
SET unit_price = CASE WHEN CEILING(unit_price / 100.0) < 100 THEN 100
                      WHEN CEILING(unit_price / 100.0) > 500 THEN 500
                      ELSE CEILING(unit_price / 100.0) END
WHERE unit_price >= 10000;
GO

UPDATE TREATMENT
SET unit_price = CASE WHEN CEILING(unit_price / 100.0) < 100 THEN 100
                      WHEN CEILING(unit_price / 100.0) > 500 THEN 500
                      ELSE CEILING(unit_price / 100.0) END
WHERE unit_price >= 10000;
GO

PRINT 'V58 done.';
GO
