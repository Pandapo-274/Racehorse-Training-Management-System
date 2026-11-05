/* =============================================================================
   V31 - Seed 15+ common equine medicines across major diseases with unit & pricing.
   ============================================================================= */

-- Update prices for existing items (feed, tools, demo medicines)
UPDATE SUPPLY_STOCK
SET unit_price = 15000.00
WHERE supply_name LIKE N'%Cám%' AND (unit_price IS NULL OR unit_price <= 0);

UPDATE SUPPLY_STOCK
SET unit_price = 45000.00
WHERE supply_name LIKE N'%timothy%' AND (unit_price IS NULL OR unit_price <= 0);

UPDATE SUPPLY_STOCK
SET unit_price = 50000.00
WHERE supply_name LIKE N'%alfalfa%' AND (unit_price IS NULL OR unit_price <= 0);

UPDATE SUPPLY_STOCK
SET unit_price = 35000.00
WHERE supply_name LIKE N'%Băng quấn%' AND (unit_price IS NULL OR unit_price <= 0);

UPDATE SUPPLY_STOCK
SET unit_price = 150000.00, supply_name = N'Phenylbutazone (Bute)'
WHERE supply_name LIKE N'%Phenylbutazone%';

UPDATE SUPPLY_STOCK
SET unit_price = 150000.00, supply_name = N'Flunixin Meglumine (Banamine)'
WHERE supply_name LIKE N'%Flunixin%';

UPDATE SUPPLY_STOCK
SET unit_price = 150000.00, supply_name = N'Dung dịch điện giải (Electrolytes)'
WHERE supply_name LIKE N'%điện giải%';
GO

-- Seed common veterinary medicines for horses
DECLARE @updaterId INT;
SELECT TOP 1 @updaterId = user_id FROM APP_USER WHERE role_id = (SELECT role_id FROM ROLE WHERE role_name = 'VETERINARIAN');
IF @updaterId IS NULL
    SELECT TOP 1 @updaterId = user_id FROM APP_USER;

INSERT INTO SUPPLY_STOCK (supply_name, category, unit, min_threshold, current_qty, area, unit_price, updated_by)
SELECT v.supply_name, 'MEDICINE', v.unit, v.min_thresh, v.curr_qty, N'Tủ thuốc', v.price, @updaterId
FROM (VALUES
    (N'Firocoxib (Equioxx)',               N'viên', 30.00,  80.00,  45000.00),
    (N'Dexamethasone',                     N'lọ',   10.00,  25.00, 120000.00),
    (N'Amoxicillin Trihydrate',           N'lọ',   10.00,  30.00, 210000.00),
    (N'Gentamicin Sulfate',                N'lọ',    8.00,  18.00, 320000.00),
    (N'Trimethoprim-Sulfadiazine (TMS)',   N'tuýp', 12.00,  24.00, 280000.00),
    (N'Penicillin G Procaine',             N'lọ',   10.00,  22.00, 195000.00),
    (N'Omeprazole (GastroGard)',           N'tuýp', 15.00,  35.00, 350000.00),
    (N'Men vi sinh tiêu hóa (Probiotic)',  N'gói',  25.00,  60.00,  65000.00),
    (N'Clenbuterol (Ventipulmin)',         N'chai',  6.00,  15.00, 420000.00),
    (N'Prednisolone',                      N'viên', 50.00, 150.00,  15000.00),
    (N'Glucosamine + Chondroitin',         N'hộp',   5.00,  12.00, 650000.00),
    (N'Hyaluronic Acid (Tiêm khớp)',       N'ống',   6.00,  14.00, 550000.00),
    (N'Thuốc mỡ sát trùng Povidone',      N'tuýp', 15.00,  40.00,  50000.00),
    (N'Ivermectin (Tẩy giun sán)',         N'tuýp', 12.00,  30.00, 160000.00),
    (N'Xylazine (An thần/Giảm đau)',       N'lọ',    5.00,  10.00, 290000.00)
) AS v(supply_name, unit, min_thresh, curr_qty, price)
WHERE NOT EXISTS (
    SELECT 1 FROM SUPPLY_STOCK s WHERE s.supply_name = v.supply_name
);
GO

PRINT 'V31 common medicines and supply pricing seeded successfully.';
GO

-- Clean up existing prescription notes in EXPENSE_LOG to use unit format
UPDATE EXPENSE_LOG
SET note = REPLACE(note, ' (Qty: 2 @ 15000)', N' (2 viên)')
WHERE note LIKE '%Prednisolone%Qty: 2%';

UPDATE EXPENSE_LOG
SET note = REPLACE(note, ' (Qty: 3 @ 150000)', N' (3 tuýp)')
WHERE note LIKE '%Phenylbutazone%Qty: 3%';

UPDATE EXPENSE_LOG
SET note = REPLACE(note, ' (Qty: 1 @ 150000)', N' (1 tuýp)')
WHERE note LIKE '%Phenylbutazone%Qty: 1%';
GO

-- Update existing movements to remove @
UPDATE SUPPLY_MOVEMENT
SET reason = REPLACE(reason, ' @ ', ' - ')
WHERE reason LIKE '% @ %';
GO
