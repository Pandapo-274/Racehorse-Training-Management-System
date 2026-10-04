/* =============================================================================
   V2 - Dữ liệu demo cho UC6 (Head Trainer Dashboard)

   Chỉ là dữ liệu trình diễn và thử nghiệm, không phải dữ liệu thật. Xoá file
   này trước khi nộp bản chạy thật, hoặc giữ lại nếu bài nộp cần có sẵn số liệu
   để chấm.

   LƯU Ý ĐẶT TÊN: nếu một người khác trong nhóm cũng tạo file V2__, Flyway sẽ
   báo trùng version và không khởi động được. Thống nhất ai lấy số nào trước
   khi commit.

   Dữ liệu sinh ra:
     6 tài khoản, 10 chiến mã, 4 giáo án đang chạy với các giai đoạn,
     khoảng 190 buổi tập trải đều 8 tuần gần nhất, 3 cảnh báo chưa xử lý,
     1 lệnh khoá huấn luyện đang hiệu lực.

   Nhịp tim hồi phục được sinh theo xu hướng giảm dần theo thời gian, nên biểu
   đồ thể lực đi lên - đúng với một đàn ngựa đang vào phong độ.
   ============================================================================= */

/* ---------- 1. Tài khoản ---------- */

INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone, status)
SELECT r.role_id, v.username, v.password_hash, v.full_name, v.email, v.phone, 'ACTIVE'
FROM (VALUES
    ('CLUB_MANAGER', 'hoa.tm',   '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Trần Minh Hòa',      'hoa.tm@tenma.vn',   '0901000001'),
    ('HEAD_TRAINER', 'thang.nd', '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Nguyễn Đức Thắng',  'thang.nd@tenma.vn', '0901000002'),
    ('HEAD_TRAINER', 'lam.tb',   '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Trần Bảo Lâm',      'lam.tb@tenma.vn',   '0901000003'),
    ('VETERINARIAN', 'ha.pt',    '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Phạm Thu Hà',       'ha.pt@tenma.vn',    '0901000004'),
    ('GROOM',        'binh.lv',  '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Lê Văn Bình',       'binh.lv@tenma.vn',  '0901000005'),
    ('HORSE_OWNER',  'anh.dq',   '$2a$10$demoHashOnlyNotARealPassword000000000000000000000000', N'Đặng Quốc Anh',     'anh.dq@tenma.vn',   '0901000006')
) AS v(role_name, username, password_hash, full_name, email, phone)
JOIN ROLE r ON r.role_name = v.role_name;
GO

/* ---------- 2. Chiến mã ---------- */

DECLARE @owner INT = (SELECT user_id FROM APP_USER WHERE username = 'anh.dq');

INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender,
                   date_of_birth, color, weight, stall_no, status)
VALUES
    (@owner, N'Symboli Rudolf', 'TM-0481', 'Thoroughbred', 'COLT',    '2022-03-14', N'Dark bay', 486.0, 'B-14', 'ELIGIBLE'),
    (@owner, N'Air Groove',     'TM-0332', 'Thoroughbred', 'MARE',    '2020-05-02', N'Bay',      472.5, 'B-07', 'INJURED'),
    (@owner, N'Gold Ship',      'TM-0298', 'Thoroughbred', 'GELDING', '2021-04-21', N'Grey',     503.0, 'A-03', 'MONITOR'),
    (@owner, N'Kitasan Black',  'TM-0507', 'Thoroughbred', 'COLT',    '2023-02-18', N'Black',    455.0, 'A-11', 'ELIGIBLE'),
    (@owner, N'Vodka',          'TM-0276', 'Thoroughbred', 'FILLY',   '2023-04-05', N'Chestnut', 441.0, 'C-02', 'QUARANTINE'),
    (@owner, N'Daiwa Scarlet',  'TM-0455', 'Thoroughbred', 'MARE',    '2021-06-30', N'Chestnut', 468.0, 'C-05', 'ELIGIBLE'),
    (@owner, N'Deep Impact',    'TM-0410', 'Thoroughbred', 'STALLION','2020-03-25', N'Bay',      497.0, 'A-01', 'ELIGIBLE'),
    (@owner, N'TM Opera O',     'TM-0389', 'Thoroughbred', 'GELDING', '2021-08-11', N'Dark bay', 489.5, 'B-02', 'MONITOR'),
    (@owner, N'Almond Eye',     'TM-0523', 'Thoroughbred', 'FILLY',   '2023-03-10', N'Bay',      438.0, 'C-09', 'ELIGIBLE'),
    (@owner, N'Orfevre',        'TM-0344', 'Thoroughbred', 'COLT',    '2022-01-28', N'Chestnut', 478.0, 'B-18', 'ELIGIBLE');
GO

/* ---------- 3. Giáo án và giai đoạn ---------- */

DECLARE @trainer INT = (SELECT user_id FROM APP_USER WHERE username = 'thang.nd');

INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
SELECT h.horse_id, @trainer,
       N'Hướng tới Tenma Cup 2026 - ' + h.horse_name,
       CAST(DATEADD(WEEK, -9, GETDATE()) AS DATE),
       CAST(DATEADD(WEEK, 6,  GETDATE()) AS DATE),
       'ACTIVE'
FROM HORSE h
WHERE h.registration_code IN ('TM-0481', 'TM-0298', 'TM-0507', 'TM-0410');
GO

/* Ba giai đoạn cho mỗi giáo án. Giai đoạn 1 và 2 đã bắt đầu, giai đoạn 3 còn
   ở tương lai - nên cột "giai đoạn hiện tại" trên dashboard hiện Phase 2. */
INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                        sessions_per_week, heart_rate_ceiling, start_date, end_date)
SELECT p.plan_id, v.phase_no, v.phase_name, v.distance, v.workload, v.surface,
       v.spw, v.ceiling,
       CAST(DATEADD(WEEK, v.start_offset, GETDATE()) AS DATE),
       CAST(DATEADD(WEEK, v.end_offset,   GETDATE()) AS DATE)
FROM TRAINING_PLAN p
CROSS JOIN (VALUES
    (1, N'Base',  3200, N'60% VO2 max', 'DIRT',      3, 170, -9, -5),
    (2, N'Build', 2000, N'75% VO2 max', 'TURF',      4, 180, -5,  2),
    (3, N'Peak',  1600, N'90% VO2 max', 'TURF',      3, 190,  2,  6)
) AS v(phase_no, phase_name, distance, workload, surface, spw, ceiling, start_offset, end_offset)
WHERE p.status = 'ACTIVE';
GO

/* ---------- 4. Buổi tập 8 tuần gần nhất ---------- */

/* days sinh dãy 0, 3, 6 ... 56 = số ngày tính ngược từ hôm nay.
   Nhịp tim hồi phục giảm dần khi tiến về hiện tại: buổi cách đây 56 ngày
   khoảng 116 bpm, buổi hôm nay khoảng 92 bpm. Qua công thức thể lực trong
   TrainerDashboardService, chỉ số đi từ khoảng 40 lên khoảng 80. */
DECLARE @groom INT = (SELECT user_id FROM APP_USER WHERE username = 'binh.lv');
DECLARE @evaluator INT = (SELECT user_id FROM APP_USER WHERE username = 'thang.nd');

WITH days AS (
    SELECT 0 AS d
    UNION ALL
    SELECT d + 3 FROM days WHERE d + 3 <= 56
),
target AS (
    SELECT ph.phase_id, tp.horse_id,
           ph.distance, ph.start_date, ph.end_date,
           /* mỗi con lệch nhau vài nhịp để các dòng không giống hệt nhau */
           (tp.horse_id % 5) * 2 AS horse_offset
    FROM PLAN_PHASE ph
    JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
    WHERE tp.status = 'ACTIVE' AND ph.phase_no <= 2
),
raw AS (
    SELECT
        t.phase_id, t.horse_id, t.distance,
        DATEADD(HOUR, 5, CAST(DATEADD(DAY, -d.d, CAST(GETDATE() AS DATE)) AS DATETIME2)) AS session_date,
        116 - ((56 - d.d) * 24 / 56) + t.horse_offset
            + (ABS(CHECKSUM(NEWID())) % 7 - 3) AS recovery_hr
    FROM days d
    JOIN target t
      ON CAST(DATEADD(DAY, -d.d, GETDATE()) AS DATE) BETWEEN t.start_date AND t.end_date
)
INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date,
                              planned_distance, actual_distance, duration_minutes,
                              avg_heart_rate, peak_heart_rate, recovery_heart_rate,
                              peak_velocity, evaluation, remarks, evaluated_by, status)
SELECT
    r.phase_id, r.horse_id, @groom, r.session_date,
    r.distance,
    r.distance + (ABS(CHECKSUM(NEWID())) % 200 - 100),
    38 + ABS(CHECKSUM(NEWID())) % 18,
    152 + ABS(CHECKSUM(NEWID())) % 14,
    172 + ABS(CHECKSUM(NEWID())) % 20,
    r.recovery_hr,
    13.0 + (ABS(CHECKSUM(NEWID())) % 25) / 10.0,
    CASE
        WHEN r.recovery_hr <= 95  THEN 'EXCELLENT'
        WHEN r.recovery_hr <= 104 THEN 'GOOD'
        WHEN r.recovery_hr <= 112 THEN 'FAIR'
        ELSE 'POOR'
    END,
    NULL, @evaluator,
    /* một phần nhỏ buổi tập bị dừng sớm, để dashboard có cả hai trạng thái */
    CASE WHEN ABS(CHECKSUM(NEWID())) % 20 = 0 THEN 'STOPPED_EARLY' ELSE 'COMPLETED' END
FROM raw r
OPTION (MAXRECURSION 100);
GO

/* ---------- 5. Cảnh báo vượt ngưỡng chưa xử lý ---------- */

/* Gắn vào 3 buổi tập gần nhất có nhịp tim đỉnh cao nhất, để số liệu khớp nhau
   thay vì bịa rời rạc. */
INSERT INTO THRESHOLD_ALERT (session_id, metric_type, threshold_value, actual_value,
                             severity, triggered_at, action_taken, resolved_by)
SELECT TOP 3
    s.session_id, 'HEART_RATE',
    ph.heart_rate_ceiling, s.peak_heart_rate,
    CASE
        WHEN s.peak_heart_rate - ph.heart_rate_ceiling >= 12 THEN 'HIGH'
        WHEN s.peak_heart_rate - ph.heart_rate_ceiling >= 5  THEN 'MEDIUM'
        ELSE 'LOW'
    END,
    s.session_date,
    CASE WHEN s.status = 'STOPPED_EARLY' THEN 'Order session stopped' ELSE NULL END,
    NULL
FROM TRAINING_SESSION s
JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
WHERE s.peak_heart_rate > ph.heart_rate_ceiling
ORDER BY s.session_date DESC;
GO

/* ---------- 6. Lệnh khoá huấn luyện đang hiệu lực ---------- */

/* Air Groove đang ở trạng thái INJURED, nên phải có lệnh khoá tương ứng -
   nếu không, dữ liệu demo tự mâu thuẫn với chính nó. */
INSERT INTO TRAINING_LOCK (horse_id, injury_id, issued_by, reason, lock_level,
                           issued_at, status)
SELECT h.horse_id, NULL,
       (SELECT user_id FROM APP_USER WHERE username = 'ha.pt'),
       N'Viêm bao gân cổ chân sau trái, độ 2',
       'FULL',
       DATEADD(DAY, -4, GETDATE()),
       'ACTIVE'
FROM HORSE h
WHERE h.registration_code = 'TM-0332';
GO
