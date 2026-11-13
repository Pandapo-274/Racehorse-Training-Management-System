/* =============================================================================
   DỮ LIỆU DEMO FLOW 4 - Chăm sóc chuồng trại & dinh dưỡng hằng ngày.
   Chạy tay trong SSMS / Azure Data Studio trên DB dev (vd. rtms). KHÔNG phải migration.

   Ngày tính theo HÔM NAY (GETDATE), nên chạy lại ngay trước buổi demo:
     - Quá khứ  (D-6..D-1): việc DONE, DONE_LATE, MISSED; sổ kho có CONSUME từng ngày.
     - Hiện tại (D)       : việc OPEN của 3 Groom; nam.tq đã có 11 việc (sát ngưỡng 12).
     - Tương lai (D+1..D+3): dọn chuồng cả dãy, cho ăn ngày mai, ngâm đá.

   Chạy lại bao nhiêu lần cũng được: phần 0 trả kho về như trước (đảo các dòng sổ
   kho của việc demo, kể cả lượng đã trừ khi bấm thử trên web), xoá việc demo,
   xoá món "Cà rốt" demo, rồi tạo lại từ đầu.

   Cần: các tài khoản demo thang.nd, ha.pt, hoa.tm, binh.lv, nam.tq, hieu.lm
   và 7 ngựa giao cho thang.nd (V37). Mật khẩu demo: 123456.
   ============================================================================= */
SET NOCOUNT ON;
SET XACT_ABORT ON;
SET QUOTED_IDENTIFIER ON;   -- SUPPLY_STOCK có cột tính sẵn (needs_restock)

DECLARE @D   DATE = CAST(GETDATE() AS DATE);
DECLARE @tag NVARCHAR(20) = N'[DEMO-F4]';
-- Giờ trong DB là UTC (completed_at, missed_at, created_at); phút lệch so với giờ máy.
DECLARE @off INT = DATEDIFF(MINUTE, SYSDATETIME(), SYSUTCDATETIME());

DECLARE @trainer INT = (SELECT user_id FROM APP_USER WHERE username = 'thang.nd');
DECLARE @vet     INT = (SELECT user_id FROM APP_USER WHERE username = 'ha.pt');
DECLARE @manager INT = (SELECT user_id FROM APP_USER WHERE username = 'hoa.tm');
DECLARE @binh    INT = (SELECT user_id FROM APP_USER WHERE username = 'binh.lv');
DECLARE @nam     INT = (SELECT user_id FROM APP_USER WHERE username = 'nam.tq');
DECLARE @hieu    INT = (SELECT user_id FROM APP_USER WHERE username = 'hieu.lm');
IF @trainer IS NULL OR @vet IS NULL OR @manager IS NULL OR @binh IS NULL OR @nam IS NULL OR @hieu IS NULL
BEGIN
    RAISERROR(N'Thiếu tài khoản demo (thang.nd, ha.pt, hoa.tm, binh.lv, nam.tq, hieu.lm).', 16, 1);
    RETURN;
END

/* 7 ngựa của thang.nd và Groom phụ trách cố định (rota) */
DECLARE @H TABLE (horse_name NVARCHAR(100) PRIMARY KEY, horse_id INT, groom INT);
INSERT INTO @H (horse_name, groom) VALUES
    (N'Symboli Rudolf', @binh), (N'Air Groove', @binh), (N'Daiwa Scarlet', @binh),
    (N'Vodka', @nam), (N'TM Opera O', @nam),
    (N'Almond Eye', @hieu), (N'Orfevre', @hieu);
UPDATE h SET h.horse_id = x.horse_id
FROM @H h JOIN HORSE x ON x.horse_name = h.horse_name
WHERE x.activity_status = 'ACTIVE' AND x.stall_no IS NOT NULL;
IF EXISTS (SELECT 1 FROM @H WHERE horse_id IS NULL)
BEGIN
    RAISERROR(N'Thiếu ngựa demo (hoặc ngựa đã rút / chưa có chuồng).', 16, 1);
    RETURN;
END

DECLARE @oat    INT = (SELECT supply_id FROM SUPPLY_STOCK WHERE supply_name = N'Yến mạch'        AND category = 'FEED');
DECLARE @mix    INT = (SELECT supply_id FROM SUPPLY_STOCK WHERE supply_name = N'Cám hỗn hợp'     AND category = 'FEED');
DECLARE @tim    INT = (SELECT supply_id FROM SUPPLY_STOCK WHERE supply_name = N'Cỏ khô timothy'  AND category = 'FEED');
DECLARE @alf    INT = (SELECT supply_id FROM SUPPLY_STOCK WHERE supply_name = N'Cỏ khô alfalfa'  AND category = 'FEED');
IF @oat IS NULL OR @mix IS NULL OR @tim IS NULL OR @alf IS NULL
BEGIN
    RAISERROR(N'Thiếu món FEED trong kho (Yến mạch, Cám hỗn hợp, Cỏ khô timothy, Cỏ khô alfalfa).', 16, 1);
    RETURN;
END

BEGIN TRAN;

/* ---------------------------------------------------------------------------
   0. Dọn lần chạy trước
   --------------------------------------------------------------------------- */
DECLARE @oldTasks TABLE (task_id INT PRIMARY KEY);
INSERT INTO @oldTasks SELECT task_id FROM DAILY_TASK WHERE LEFT(note, 9) = @tag;

DECLARE @carrotOld INT = (SELECT supply_id FROM SUPPLY_STOCK WHERE supply_name = N'Cà rốt' AND area = N'Kho A');

-- Đảo mọi dòng sổ kho của việc demo + dòng có nhãn demo (trừ món Cà rốt, sẽ xoá hẳn)
UPDATE s SET s.current_qty = CASE WHEN s.current_qty - x.net < 0 THEN 0 ELSE s.current_qty - x.net END
FROM SUPPLY_STOCK s
JOIN (SELECT supply_id, SUM(qty_delta) AS net
      FROM SUPPLY_MOVEMENT
      WHERE (ref_type = 'DAILY_TASK' AND ref_id IN (SELECT task_id FROM @oldTasks))
         OR LEFT(reason, 9) = @tag
      GROUP BY supply_id) x ON x.supply_id = s.supply_id
WHERE s.supply_id <> ISNULL(@carrotOld, -1);

DELETE FROM SUPPLY_MOVEMENT
WHERE (ref_type = 'DAILY_TASK' AND ref_id IN (SELECT task_id FROM @oldTasks))
   OR LEFT(reason, 9) = @tag
   OR supply_id = @carrotOld;
DELETE FROM DAILY_TASK WHERE task_id IN (SELECT task_id FROM @oldTasks);
DELETE FROM FEEDING_SCHEDULE WHERE horse_id IN (SELECT horse_id FROM @H) OR supply_id = @carrotOld;
IF @carrotOld IS NOT NULL
    DELETE FROM SUPPLY_STOCK WHERE supply_id = @carrotOld;

/* ---------------------------------------------------------------------------
   1. Kho
   - Cà rốt: chỉ còn 3 kg, khẩu phần Vodka cần 4 kg/ngày -> demo "Stock ran out".
   - Thuốc: 1 món đã hết hạn, 1 món sắp hết hạn -> bản tổng hợp 07:05 cho Manager.
   - Cỏ khô timothy vốn đã dưới định mức (48/60 kiện).
   --------------------------------------------------------------------------- */
DECLARE @carrot INT;
INSERT INTO SUPPLY_STOCK (supply_name, category, unit, min_threshold, current_qty, area, unit_price, updated_by)
VALUES (N'Cà rốt', 'FEED', N'kg', 10, 3, N'Kho A', 20000, @manager);
SET @carrot = SCOPE_IDENTITY();

UPDATE SUPPLY_STOCK SET expiry_date = DATEADD(DAY, -5, @D)  WHERE supply_name = N'Dexamethasone' AND category = 'MEDICINE';
UPDATE SUPPLY_STOCK SET expiry_date = DATEADD(DAY, 10, @D)  WHERE supply_name LIKE N'Xylazine%'  AND category = 'MEDICINE';
UPDATE SUPPLY_STOCK SET last_reminded_on = NULL;   -- để bản tổng hợp sáng mai gửi lại

/* ---------------------------------------------------------------------------
   2. Khẩu phần đã duyệt (mọi dòng nối với món FEED trong kho)
   --------------------------------------------------------------------------- */
DECLARE @R TABLE (horse_name NVARCHAR(100), meal VARCHAR(10), supply_id INT, qty DECIMAL(6,2), approver INT, note NVARCHAR(255));
INSERT INTO @R VALUES
    (N'Symboli Rudolf', 'MORNING', @oat,    3.5, @trainer, NULL),
    (N'Symboli Rudolf', 'NOON',    @tim,    0.5, @trainer, NULL),
    (N'Symboli Rudolf', 'EVENING', @mix,    2.5, @trainer, NULL),
    (N'Air Groove',     'MORNING', @mix,    2.0, @vet,     N'Ít tinh bột theo chỉ định bác sĩ'),
    (N'Air Groove',     'EVENING', @alf,    0.5, @vet,     NULL),
    (N'Vodka',          'MORNING', @carrot, 2.0, @trainer, N'Bổ sung vitamin A'),
    (N'Vodka',          'EVENING', @carrot, 2.0, @trainer, NULL),
    (N'Vodka',          'EVENING', @oat,    3.0, @trainer, NULL),
    (N'Daiwa Scarlet',  'MORNING', @oat,    3.0, @trainer, NULL),
    (N'Daiwa Scarlet',  'EVENING', @mix,    2.0, @trainer, NULL),
    (N'TM Opera O',     'MORNING', @oat,    3.2, @trainer, NULL),
    (N'TM Opera O',     'EVENING', @alf,    0.5, @trainer, NULL),
    (N'Almond Eye',     'MORNING', @oat,    3.0, @vet,     NULL),
    (N'Almond Eye',     'NOON',    @tim,    0.5, @vet,     NULL),
    (N'Almond Eye',     'EVENING', @mix,    2.0, @vet,     NULL),
    (N'Orfevre',        'MORNING', @oat,    3.5, @trainer, NULL),
    (N'Orfevre',        'EVENING', @mix,    2.5, @trainer, NULL);

INSERT INTO FEEDING_SCHEDULE (horse_id, meal_time, feed_type, quantity, unit, approved_by, note, supply_id)
SELECT h.horse_id, r.meal, LEFT(s.supply_name, 50), r.qty, s.unit, r.approver, r.note, r.supply_id
FROM @R r JOIN @H h ON h.horse_name = r.horse_name JOIN SUPPLY_STOCK s ON s.supply_id = r.supply_id;

/* ---------------------------------------------------------------------------
   3. Việc chăm sóc: quá khứ, hiện tại, tương lai
   state: DONE | LATE (xong hôm sau) | TRAINER (HLV đánh dấu hộ) | MISSED | OPEN
   minute: giờ làm trong ngày, tính bằng phút (450 = 07:30)
   --------------------------------------------------------------------------- */
DECLARE @T TABLE (d INT, horse_name NVARCHAR(100), ttype VARCHAR(20), groom INT, state VARCHAR(10),
                  minute INT, note NVARCHAR(200));

-- Quá khứ: mỗi ngày cho ăn + dọn chuồng cho cả 7 con, đều xong
INSERT INTO @T (d, horse_name, ttype, groom, state, minute)
SELECT dd.d, h.horse_name, t.ttype, h.groom, 'DONE', t.minute + dd.d * -3
FROM (VALUES (-6), (-5), (-4), (-3), (-2), (-1)) dd(d)
CROSS JOIN @H h
CROSS JOIN (VALUES ('FEEDING', 450), ('CLEANING', 540)) t(ttype, minute);

-- Các ngoại lệ trong quá khứ
UPDATE @T SET state = 'LATE'    WHERE d = -3 AND horse_name = N'Air Groove'  AND ttype = 'CLEANING';
UPDATE @T SET state = 'MISSED'  WHERE d = -2 AND horse_name = N'TM Opera O'  AND ttype = 'CLEANING';
UPDATE @T SET state = 'MISSED'  WHERE d = -1 AND horse_name = N'Almond Eye'  AND ttype = 'CLEANING';
UPDATE @T SET state = 'TRAINER' WHERE d = -1 AND horse_name = N'Vodka'       AND ttype = 'CLEANING';
INSERT INTO @T (d, horse_name, ttype, groom, state, minute, note) VALUES
    (-5, N'Air Groove',     'BATHING',  @binh, 'DONE',   900, NULL),
    (-4, N'Orfevre',        'BATHING',  @hieu, 'MISSED', 900, NULL),
    (-2, N'Symboli Rudolf', 'ICE_BATH', @binh, 'DONE',   990, N'Sau buổi tập tốc độ');

-- Hôm nay: binh 6 việc, hieu 2 việc, nam 11 việc (giao thêm 2 là vượt ngưỡng 12)
INSERT INTO @T (d, horse_name, ttype, groom, state, minute, note) VALUES
    (0, N'Symboli Rudolf', 'FEEDING',  @binh, 'OPEN', 0, NULL),
    (0, N'Symboli Rudolf', 'CLEANING', @binh, 'OPEN', 0, NULL),
    (0, N'Air Groove',     'FEEDING',  @binh, 'OPEN', 0, NULL),
    (0, N'Air Groove',     'CLEANING', @binh, 'OPEN', 0, NULL),
    (0, N'Daiwa Scarlet',  'FEEDING',  @binh, 'OPEN', 0, NULL),
    (0, N'Vodka',          'FEEDING',  @binh, 'OPEN', 0, N'Kho cà rốt sắp hết'),
    (0, N'Vodka',          'CLEANING', @hieu, 'OPEN', 0, NULL),
    (0, N'Symboli Rudolf', 'BATHING',  @hieu, 'OPEN', 0, NULL);
INSERT INTO @T (d, horse_name, ttype, groom, state, minute)
SELECT 0, h.horse_name, t.ttype, @nam, 'OPEN', 0
FROM @H h CROSS JOIN (VALUES ('FEEDING'), ('CLEANING'), ('BATHING'), ('ICE_BATH')) t(ttype)
WHERE h.horse_name IN (N'TM Opera O', N'Almond Eye', N'Orfevre')
  AND NOT (h.horse_name = N'Orfevre' AND t.ttype = 'ICE_BATH');

-- Tương lai: dọn chuồng cả dãy 3 ngày (hieu), cho ăn ngày mai (binh), ngâm đá ngày kia
INSERT INTO @T (d, horse_name, ttype, groom, state, minute)
SELECT dd.d, h.horse_name, 'CLEANING', @hieu, 'OPEN', 0
FROM (VALUES (1), (2), (3)) dd(d) CROSS JOIN @H h;
INSERT INTO @T (d, horse_name, ttype, groom, state, minute)
SELECT 1, horse_name, 'FEEDING', @binh, 'OPEN', 0 FROM @H WHERE groom = @binh;
INSERT INTO @T (d, horse_name, ttype, groom, state, minute, note) VALUES
    (2, N'Symboli Rudolf', 'ICE_BATH', @binh, 'OPEN', 0, N'Sau buổi chạy thử');

INSERT INTO DAILY_TASK (horse_id, task_type, assigned_to, task_date, is_completed, completed_at, note,
                        completed_by, missed_at)
SELECT h.horse_id, t.ttype, t.groom, DATEADD(DAY, t.d, @D),
       CASE WHEN t.state IN ('DONE', 'LATE', 'TRAINER') THEN 1 ELSE 0 END,
       CASE t.state
            WHEN 'LATE' THEN DATEADD(MINUTE, @off + 480, CAST(DATEADD(DAY, t.d + 1, @D) AS DATETIME2))
            WHEN 'DONE' THEN DATEADD(MINUTE, @off + t.minute, CAST(DATEADD(DAY, t.d, @D) AS DATETIME2))
            WHEN 'TRAINER' THEN DATEADD(MINUTE, @off + t.minute, CAST(DATEADD(DAY, t.d, @D) AS DATETIME2))
       END,
       @tag + ISNULL(N' ' + t.note, N''),
       CASE t.state WHEN 'TRAINER' THEN @trainer WHEN 'DONE' THEN t.groom WHEN 'LATE' THEN t.groom END,
       -- MissedTaskJob đóng dấu lúc 00:15 hôm sau
       CASE WHEN t.state = 'MISSED' THEN DATEADD(MINUTE, @off + 15, CAST(DATEADD(DAY, t.d + 1, @D) AS DATETIME2)) END
FROM @T t JOIN @H h ON h.horse_name = t.horse_name;

/* ---------------------------------------------------------------------------
   4. Sổ kho của quá khứ: một lần nhập hàng D-7, rồi mỗi việc FEEDING đã xong
      trừ khẩu phần cả ngày (giống CareService.consumeRation). Tồn kho cuối
      cùng bằng đúng tồn kho trước khi chạy script.
   --------------------------------------------------------------------------- */
DECLARE @use TABLE (task_id INT, supply_id INT, qty DECIMAL(8,2), horse_name NVARCHAR(100), at DATETIME2, by_user INT);
INSERT INTO @use
SELECT t.task_id, f.supply_id, SUM(f.quantity), h.horse_name, t.completed_at, ISNULL(t.completed_by, t.assigned_to)
FROM DAILY_TASK t
JOIN HORSE h ON h.horse_id = t.horse_id
JOIN FEEDING_SCHEDULE f ON f.horse_id = t.horse_id AND f.supply_id IS NOT NULL
WHERE LEFT(t.note, 9) = @tag AND t.task_type = 'FEEDING' AND t.is_completed = 1
GROUP BY t.task_id, f.supply_id, h.horse_name, t.completed_at, t.completed_by, t.assigned_to;

DECLARE @recvAt DATETIME2 = DATEADD(MINUTE, @off + 540, CAST(DATEADD(DAY, -7, @D) AS DATETIME2));
UPDATE s SET s.current_qty = s.current_qty + u.total
FROM SUPPLY_STOCK s JOIN (SELECT supply_id, SUM(qty) AS total FROM @use GROUP BY supply_id) u
  ON u.supply_id = s.supply_id;
INSERT INTO SUPPLY_MOVEMENT (supply_id, movement_type, qty_delta, qty_after, ref_type, ref_id, reason, created_by, created_at)
SELECT s.supply_id, 'RECEIVE', u.total, s.current_qty, NULL, NULL, @tag + N' Nhập hàng đầu tuần', @manager, @recvAt
FROM SUPPLY_STOCK s JOIN (SELECT supply_id, SUM(qty) AS total FROM @use GROUP BY supply_id) u
  ON u.supply_id = s.supply_id;

DECLARE @tid INT, @sid INT, @q DECIMAL(8,2), @hn NVARCHAR(100), @at DATETIME2, @by INT, @after DECIMAL(8,2);
DECLARE cur CURSOR LOCAL FAST_FORWARD FOR
    SELECT task_id, supply_id, qty, horse_name, at, by_user FROM @use ORDER BY at, task_id, supply_id;
OPEN cur;
FETCH NEXT FROM cur INTO @tid, @sid, @q, @hn, @at, @by;
WHILE @@FETCH_STATUS = 0
BEGIN
    UPDATE SUPPLY_STOCK SET @after = current_qty = current_qty - @q WHERE supply_id = @sid;
    INSERT INTO SUPPLY_MOVEMENT (supply_id, movement_type, qty_delta, qty_after, ref_type, ref_id, reason, created_by, created_at)
    VALUES (@sid, 'CONSUME', -@q, @after, 'DAILY_TASK', @tid, N'Feeding ' + @hn, @by, @at);
    FETCH NEXT FROM cur INTO @tid, @sid, @q, @hn, @at, @by;
END
CLOSE cur;
DEALLOCATE cur;

COMMIT;

/* Tóm tắt */
SELECT CASE WHEN t.task_date < @D THEN '1. Qua khu' WHEN t.task_date = @D THEN '2. Hom nay' ELSE '3. Tuong lai' END AS nhom,
       SUM(CASE WHEN t.is_completed = 1 THEN 1 ELSE 0 END) AS xong,
       SUM(CASE WHEN t.is_completed = 0 AND t.task_date < @D THEN 1 ELSE 0 END) AS bo_lo,
       SUM(CASE WHEN t.is_completed = 0 AND t.task_date >= @D THEN 1 ELSE 0 END) AS dang_mo
FROM DAILY_TASK t WHERE LEFT(t.note, 9) = @tag
GROUP BY CASE WHEN t.task_date < @D THEN '1. Qua khu' WHEN t.task_date = @D THEN '2. Hom nay' ELSE '3. Tuong lai' END
ORDER BY nhom;
