/* =============================================================================
   V32 - Deduct medicine stock for treatments that haven't been deducted yet.
   ============================================================================= */

-- 1. Link supply_id in TREATMENT if it was null but name matches SUPPLY_STOCK
UPDATE t
SET t.supply_id = s.supply_id
FROM TREATMENT t
JOIN SUPPLY_STOCK s ON LOWER(LTRIM(RTRIM(t.medicine_name))) = LOWER(LTRIM(RTRIM(s.supply_name)))
WHERE t.supply_id IS NULL AND s.category = 'MEDICINE';

-- 2. Deduct stock and insert into SUPPLY_MOVEMENT for treatments that do not have a movement yet
DECLARE @default_vet_id INT;
SELECT TOP 1 @default_vet_id = u.user_id
FROM APP_USER u
JOIN ROLE r ON r.role_id = u.role_id
WHERE r.role_name = 'VETERINARIAN';

IF @default_vet_id IS NULL
    SELECT TOP 1 @default_vet_id = user_id FROM APP_USER;

DECLARE @t_id INT, @s_id INT, @qty DECIMAL(8,2), @h_name NVARCHAR(100), @user_id INT;

DECLARE treatment_cursor CURSOR FOR
SELECT t.treatment_id, t.supply_id, t.quantity, ISNULL(h.horse_name, 'Horse'), ISNULL(mr.vet_id, @default_vet_id)
FROM TREATMENT t
LEFT JOIN MEDICAL_RECORD mr ON mr.record_id = t.record_id
LEFT JOIN HORSE h ON h.horse_id = mr.horse_id
WHERE t.supply_id IS NOT NULL 
  AND t.quantity IS NOT NULL 
  AND t.quantity > 0
  AND NOT EXISTS (
      SELECT 1 FROM SUPPLY_MOVEMENT m 
      WHERE m.ref_type = 'TREATMENT' AND m.ref_id = t.treatment_id
  );

OPEN treatment_cursor;
FETCH NEXT FROM treatment_cursor INTO @t_id, @s_id, @qty, @h_name, @user_id;

WHILE @@FETCH_STATUS = 0
BEGIN
    DECLARE @current DECIMAL(10,2), @after DECIMAL(10,2);
    SELECT @current = current_qty FROM SUPPLY_STOCK WHERE supply_id = @s_id;
    
    SET @after = CASE WHEN @current >= @qty THEN @current - @qty ELSE 0 END;
    
    UPDATE SUPPLY_STOCK
    SET current_qty = @after, updated_at = SYSUTCDATETIME()
    WHERE supply_id = @s_id;

    INSERT INTO SUPPLY_MOVEMENT (supply_id, movement_type, qty_delta, qty_after, ref_type, ref_id, reason, created_by, created_at)
    VALUES (@s_id, 'CONSUME', -@qty, @after, 'TREATMENT', @t_id, 'Prescription for ' + @h_name, @user_id, SYSUTCDATETIME());

    FETCH NEXT FROM treatment_cursor INTO @t_id, @s_id, @qty, @h_name, @user_id;
END;

CLOSE treatment_cursor;
DEALLOCATE treatment_cursor;
GO
