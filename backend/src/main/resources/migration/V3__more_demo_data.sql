/* =============================================================================
   V3 - Bổ sung dữ liệu demo, và lấp phả hệ còn thiếu.

   VÌ SAO LÀ FILE MỚI CHỨ KHÔNG SỬA V2
   V2 đã chạy trên máy của mọi người trong nhóm. Sửa vào đó là đổi checksum,
   và mọi máy đã chạy bản cũ sẽ không khởi động nổi cho tới khi xoá sạch
   database - đúng chuyện đã xảy ra ngày 04/10 và làm mất một buổi tối. File
   mới thì Flyway chỉ việc chạy thêm, không ai phải xoá gì.

   ĐIỀU NÀY THÊM GÌ
     10 tài khoản   - 6 chủ ngựa, 4 nhân sự. V2 chỉ có 1 chủ ngựa nên ô chọn
                      chủ trong biểu mẫu UC7 gần như trống.
     28 chiến mã    - 20 tổ tiên và 8 con đang nuôi.
     Phả hệ         - V2 chèn HORSE mà bỏ trống sire_id và dam_id, nên thẻ
                      Pedigree của UC7 không có gì để vẽ. Ở đây nối lại theo
                      đúng phả hệ thật của từng con, sâu 3-4 đời.
     ~80 dòng khác  - mười bảng V2 chưa từng chạm tới: hồ sơ bệnh án, chấn
                      thương, điều trị, báo cáo sự cố, khẩu phần, việc hằng
                      ngày, kho vật tư, đăng ký đua, chi phí.

   NGỰA TỔ TIÊN ĐỂ stall_no = NULL
   UX_HORSE_stall là unique index có lọc, nên hai con cùng số chuồng sẽ bị
   chặn. Tổ tiên thì không ở trong chuồng nào của học viện - chúng chỉ tồn tại
   trong sổ giống - nên NULL vừa đúng nghĩa vừa tránh đụng chuồng.

   TÊN NGỰA LÀ TÊN THẬT
   Phả hệ dưới đây lấy theo đời thật: Deep Impact đúng là con của Sunday
   Silence và Wind in Her Hair; Gold Ship và Orfevre đúng là cùng cha Stay
   Gold. Nhờ vậy cây phả hệ có nhánh trùng nhau ở đời ông, giống dữ liệu thật
   chứ không phải mỗi con một nhánh riêng rẽ.
   ============================================================================= */

/* ---------- 1. Tài khoản ---------- */

/* Cùng hash BCrypt của "123456" như V2, để đăng nhập thử cho nhanh.
   Đây là dữ liệu demo; bản chạy thật phải đặt lại mật khẩu. */
INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone, status)
SELECT r.role_id, v.username,
       '$2b$10$W0zKT4yUrmErFfpx9KvfN.QSGp0t7eA3ueZpY8hnJjsiwfJy.fQ66',
       v.full_name, v.email, v.phone, v.status
FROM (VALUES
    ('HORSE_OWNER',  'trang.lt',  N'Lý Thu Trang',      'trang.lt@tenma.vn',  '0901000007', 'ACTIVE'),
    ('HORSE_OWNER',  'khanh.nv',  N'Nguyễn Văn Khánh',  'khanh.nv@tenma.vn',  '0901000008', 'ACTIVE'),
    ('HORSE_OWNER',  'mai.ht',    N'Hoàng Thị Mai',     'mai.ht@tenma.vn',    '0901000009', 'ACTIVE'),
    ('HORSE_OWNER',  'son.dv',    N'Đỗ Văn Sơn',        'son.dv@tenma.vn',    '0901000010', 'ACTIVE'),
    ('HORSE_OWNER',  'yen.pt',    N'Phan Thị Yến',      'yen.pt@tenma.vn',    '0901000011', 'ACTIVE'),
    /* Một tài khoản bị khoá, để màn hình nào hiện trạng thái tài khoản cũng
       có ít nhất một ca SUSPENDED mà xem. */
    ('HORSE_OWNER',  'cuong.bm',  N'Bùi Minh Cường',    'cuong.bm@tenma.vn',  '0901000012', 'SUSPENDED'),
    ('HEAD_TRAINER', 'tuan.ng',   N'Ngô Anh Tuấn',      'tuan.ng@tenma.vn',   '0901000013', 'ACTIVE'),
    ('VETERINARIAN', 'linh.vt',   N'Vũ Thuỳ Linh',      'linh.vt@tenma.vn',   '0901000014', 'ACTIVE'),
    ('GROOM',        'nam.tq',    N'Trương Quang Nam',  'nam.tq@tenma.vn',    '0901000015', 'ACTIVE'),
    ('GROOM',        'hieu.lm',   N'Lê Minh Hiếu',      'hieu.lm@tenma.vn',   '0901000016', 'ACTIVE')
) AS v(role_name, username, full_name, email, phone, status)
JOIN ROLE r ON r.role_name = v.role_name;
GO

/* ---------- 2. Ngựa tổ tiên ----------
   Chỉ có trong sổ giống: không chuồng, trạng thái ELIGIBLE vì cột status
   NOT NULL và CK_HORSE_status không có giá trị nào nghĩa là "đã nghỉ".
   Đó là một thiếu sót của lược đồ, ghi lại ở cuối file. */

DECLARE @stud INT = (SELECT user_id FROM APP_USER WHERE username = 'anh.dq');

INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender,
                   date_of_birth, color, weight, stall_no, status)
SELECT @stud, v.horse_name, v.registration_code, v.breed, v.horse_gender,
       v.date_of_birth, v.color, v.weight, NULL, 'ELIGIBLE'
FROM (VALUES
    /* đời cha mẹ của 10 con trong V2 */
    (N'Sunday Silence',    'TM-A001', 'Thoroughbred', 'STALLION', '2003-03-25', N'Dark bay', 500.0),
    (N'Wind in Her Hair',  'TM-A002', 'Thoroughbred', 'MARE',     '2001-04-12', N'Bay',      470.0),
    (N'Tony Bin',          'TM-A003', 'Thoroughbred', 'STALLION', '2000-04-07', N'Bay',      495.0),
    (N'Dyna Carle',        'TM-A004', 'Thoroughbred', 'MARE',     '1999-05-18', N'Bay',      462.0),
    (N'Partholon',         'TM-A005', 'Thoroughbred', 'STALLION', '2002-02-20', N'Chestnut', 498.0),
    (N'Sweet Luna',        'TM-A006', 'Thoroughbred', 'MARE',     '2003-03-09', N'Dark bay', 455.0),
    (N'Black Tide',        'TM-A007', 'Thoroughbred', 'STALLION', '2004-03-18', N'Bay',      492.0),
    (N'Shugar Heart',      'TM-A008', 'Thoroughbred', 'MARE',     '2005-04-27', N'Bay',      448.0),
    (N'Stay Gold',         'TM-A009', 'Thoroughbred', 'STALLION', '2001-02-24', N'Black',    485.0),
    (N'Point Flag',        'TM-A010', 'Thoroughbred', 'MARE',     '2004-05-03', N'Grey',     466.0),
    (N'Oriental Art',      'TM-A011', 'Thoroughbred', 'MARE',     '2003-04-15', N'Chestnut', 459.0),
    (N'Tanino Gimlet',     'TM-A012', 'Thoroughbred', 'STALLION', '2004-03-04', N'Dark bay', 488.0),
    (N'Tanino Sister',     'TM-A013', 'Thoroughbred', 'MARE',     '2005-04-21', N'Chestnut', 443.0),
    (N'Agnes Tachyon',     'TM-A014', 'Thoroughbred', 'STALLION', '2002-04-13', N'Chestnut', 490.0),
    (N'Scarlet Bouquet',   'TM-A015', 'Thoroughbred', 'MARE',     '2004-02-29', N'Chestnut', 451.0),
    (N'Opera House',       'TM-A016', 'Thoroughbred', 'STALLION', '2000-03-11', N'Bay',      502.0),
    (N'Once Wed',          'TM-A017', 'Thoroughbred', 'MARE',     '2001-05-07', N'Dark bay', 457.0),
    (N'Lord Kanaloa',      'TM-A018', 'Thoroughbred', 'STALLION', '2005-03-30', N'Bay',      494.0),
    (N'Fusaichi Pandora',  'TM-A019', 'Thoroughbred', 'MARE',     '2006-03-14', N'Chestnut', 453.0),
    /* Hai đời trên nữa của dòng Sunday Silence. Cần tới đây vì thẻ Pedigree
       cho chọn hiển thị 4 đời; thiếu thì nút "4 gen" bấm vào chỉ ra thêm một
       hàng ô "Unknown". */
    (N'Halo',              'TM-A020', 'Thoroughbred', 'STALLION', '1996-02-07', N'Dark bay', 489.0),
    (N'Golden Sash',       'TM-A021', 'Thoroughbred', 'MARE',     '1997-04-16', N'Bay',      461.0),
    (N'Hail to Reason',    'TM-A022', 'Thoroughbred', 'STALLION', '1992-04-18', N'Dark bay', 486.0),
    (N'Cosmah',            'TM-A023', 'Thoroughbred', 'MARE',     '1993-03-22', N'Bay',      449.0)
) AS v(horse_name, registration_code, breed, horse_gender, date_of_birth, color, weight);
GO

/* ---------- 3. Chiến mã đang nuôi ---------- */

INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender,
                   date_of_birth, color, weight, stall_no, status)
SELECT u.user_id, v.horse_name, v.registration_code, v.breed, v.horse_gender,
       v.date_of_birth, v.color, v.weight, v.stall_no, v.status
FROM (VALUES
    ('trang.lt', N'Mejiro McQueen',  'TM-0612', 'Thoroughbred', 'STALLION', '2021-03-27', N'Grey',     496.0, 'A-05', 'ELIGIBLE'),
    ('trang.lt', N'Grass Wonder',    'TM-0618', 'Thoroughbred', 'COLT',     '2023-01-19', N'Chestnut', 447.0, 'C-11', 'ELIGIBLE'),
    ('khanh.nv', N'El Condor Pasa',  'TM-0624', 'Thoroughbred', 'COLT',     '2022-09-08', N'Bay',      471.0, 'B-21', 'MONITOR'),
    ('khanh.nv', N'Special Week',    'TM-0631', 'Thoroughbred', 'STALLION', '2020-05-02', N'Dark bay', 505.0, 'A-07', 'ELIGIBLE'),
    ('mai.ht',   N'Still in Love',   'TM-0637', 'Thoroughbred', 'FILLY',    '2023-05-23', N'Bay',      436.0, 'C-14', 'ELIGIBLE'),
    ('mai.ht',   N'Biwa Hayahide',   'TM-0645', 'Thoroughbred', 'GELDING',  '2021-11-02', N'Grey',     499.0, 'B-25', 'INJURED'),
    ('son.dv',   N'Narita Brian',    'TM-0652', 'Thoroughbred', 'COLT',     '2022-05-03', N'Black',    483.0, 'B-30', 'ELIGIBLE'),
    ('yen.pt',   N'Hishi Amazon',    'TM-0660', 'Thoroughbred', 'MARE',     '2020-03-26', N'Chestnut', 474.0, 'A-09', 'QUARANTINE')
) AS v(owner_username, horse_name, registration_code, breed, horse_gender,
       date_of_birth, color, weight, stall_no, status)
JOIN APP_USER u ON u.username = v.owner_username;
GO

/* ---------- 4. Phả hệ ----------
   Gán bằng UPDATE sau khi đã chèn hết, thay vì cố sắp xếp thứ tự chèn sao cho
   cha mẹ luôn đi trước con. Với khoá ngoại tự trỏ vào chính bảng HORSE, cách
   sắp xếp ấy rất dễ sai và sai thì khó đọc ra; còn ở đây mọi con ngựa đã tồn
   tại rồi nên chỉ việc nối.

   Tra theo registration_code chứ không theo horse_id: IDENTITY phụ thuộc thứ
   tự chèn, nên số id trên máy mỗi người một khác. */

UPDATE h
SET h.sire_id = s.horse_id,
    h.dam_id  = d.horse_id
FROM HORSE h
JOIN (VALUES
    /* con,       cha,        mẹ */
    ('TM-0410', 'TM-A001', 'TM-A002'),   -- Deep Impact
    ('TM-0332', 'TM-A003', 'TM-A004'),   -- Air Groove
    ('TM-0481', 'TM-A005', 'TM-A006'),   -- Symboli Rudolf
    ('TM-0507', 'TM-A007', 'TM-A008'),   -- Kitasan Black
    ('TM-0298', 'TM-A009', 'TM-A010'),   -- Gold Ship
    ('TM-0344', 'TM-A009', 'TM-A011'),   -- Orfevre   (cùng cha với Gold Ship)
    ('TM-0276', 'TM-A012', 'TM-A013'),   -- Vodka
    ('TM-0455', 'TM-A014', 'TM-A015'),   -- Daiwa Scarlet
    ('TM-0389', 'TM-A016', 'TM-A017'),   -- TM Opera O
    ('TM-0523', 'TM-A018', 'TM-A019'),   -- Almond Eye
    /* Các đời trên. Stay Gold đúng là con của Sunday Silence ngoài đời thật,
       nên Gold Ship và Orfevre truy ngược lên cũng gặp Sunday Silence - cây
       phả hệ vì thế có nhánh chụm lại, giống dữ liệu thật, thay vì mỗi con
       một nhánh song song. */
    ('TM-A009', 'TM-A001', 'TM-A021'),   -- Stay Gold
    ('TM-A001', 'TM-A020', NULL),        -- Sunday Silence
    ('TM-A020', 'TM-A022', 'TM-A023'),   -- Halo
    /* vài con mới cũng có cha mẹ trong sổ, để chúng không trống trơn */
    ('TM-0612', 'TM-A009', 'TM-A010'),   -- Mejiro McQueen, em cùng cha mẹ với Gold Ship
    ('TM-0631', 'TM-A001', 'TM-A017'),   -- Special Week
    ('TM-0652', 'TM-A005', 'TM-A015')    -- Narita Brian
) AS v(child, sire, dam) ON v.child = h.registration_code
LEFT JOIN HORSE s ON s.registration_code = v.sire
LEFT JOIN HORSE d ON d.registration_code = v.dam;
GO

/* Kiểm ngay tại đây thay vì để lỗi lộ ra trên giao diện. CK_HORSE_notself đã
   chặn con tự làm cha mẹ của chính nó, nhưng không chặn được vòng lặp hai đời
   (A là cha B, B là cha A) - loại dữ liệu đó sẽ làm truy vấn đệ quy phả hệ
   chạy mãi tới khi chạm MAXRECURSION. */
IF EXISTS (
    SELECT 1 FROM HORSE c
    JOIN HORSE p ON p.horse_id IN (c.sire_id, c.dam_id)
    WHERE c.horse_id IN (p.sire_id, p.dam_id)
)
BEGIN
    THROW 50001, 'Pha he co vong lap hai doi - kiem tra lai muc 4.', 1;
END
GO

/* ---------- 5. Hồ sơ bệnh án ---------- */

INSERT INTO MEDICAL_RECORD (horse_id, vet_id, record_type, exam_date, diagnosis,
                            health_status, injury_confirmed, expected_rest_days, next_due_date, notes)
SELECT h.horse_id, u.user_id, v.record_type,
       DATEADD(DAY, v.day_offset, CAST(GETDATE() AS DATE)),
       v.diagnosis, v.health_status, v.injury_confirmed, v.rest_days,
       CASE WHEN v.due_offset IS NULL THEN NULL
            ELSE DATEADD(DAY, v.due_offset, CAST(GETDATE() AS DATE)) END,
       v.notes
FROM (VALUES
    ('TM-0645', 'ha.pt',   'EXAM',        -4, N'Viêm gân chi trước trái',        'INJURED',    1, 45,   NULL, N'Chụp lại sau 6 tuần.'),
    ('TM-0332', 'ha.pt',   'EXAM',        -2, N'Nghi căng gân chi trước trái',   'INJURED',    1, 30,   NULL, N'Chườm lạnh ngày hai lần.'),
    ('TM-0660', 'linh.vt', 'EXAM',        -6, N'Sốt nhẹ chưa rõ nguyên nhân',    'QUARANTINE', 0, 14,   NULL, N'Cách ly tới khi hết sốt 72 giờ.'),
    ('TM-0624', 'linh.vt', 'EXAM',        -9, N'Mệt mỏi sau thi đấu',            'MONITOR',    0,  7,   NULL, N'Giảm cường độ một tuần.'),
    ('TM-0481', 'ha.pt',   'VACCINATION', -30, N'Tiêm cúm ngựa mũi nhắc lại',    'ELIGIBLE',   0, NULL,  150, NULL),
    ('TM-0410', 'ha.pt',   'VACCINATION', -28, N'Tiêm cúm ngựa mũi nhắc lại',    'ELIGIBLE',   0, NULL,  152, NULL),
    ('TM-0507', 'linh.vt', 'DEWORMING',   -45, N'Tẩy giun định kỳ',              'ELIGIBLE',   0, NULL,   45, NULL),
    ('TM-0298', 'linh.vt', 'FARRIER',     -12, N'Thay móng bốn chân',            'ELIGIBLE',   0, NULL,   30, N'Móng sau mòn lệch, theo dõi.'),
    /* Hai dòng cuối là hồ sơ cũ, đã khỏi. Chúng BẮT BUỘC phải nằm ở đây chứ
       không phải sau mục 6: mục 6 tra hồ sơ gần nhất của từng con bằng CROSS
       APPLY, nên con nào chưa có hồ sơ thì dòng chấn thương của nó lặng lẽ bị
       bỏ qua - không lỗi, chỉ là thiếu dữ liệu, và chỉ phát hiện ra khi mở
       giao diện lên thấy trống. */
    ('TM-0652', 'ha.pt',   'EXAM',        -60, N'Bầm móng sau phải',             'ELIGIBLE',   1,  21,  NULL, NULL),
    ('TM-0455', 'ha.pt',   'EXAM',        -90, N'Căng khuỷu chi sau trái',       'ELIGIBLE',   1,  28,  NULL, NULL)
) AS v(reg_code, vet_username, record_type, day_offset, diagnosis, health_status,
       injury_confirmed, rest_days, due_offset, notes)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = v.vet_username;
GO

/* ---------- 6. Chấn thương ----------
   CK_INJ_recovered: status = RECOVERED thì bắt buộc có recovered_at. */

INSERT INTO INJURY (record_id, body_location, coordinate_x, coordinate_y,
                    severity, status, marked_at, recovered_at)
SELECT m.record_id, v.body_location, v.cx, v.cy, v.severity, v.status,
       DATEADD(DAY, v.marked_offset, GETDATE()),
       CASE WHEN v.status = 'RECOVERED'
            THEN DATEADD(DAY, v.marked_offset + 20, GETDATE()) ELSE NULL END
FROM (VALUES
    ('TM-0645', N'Gân chi trước trái',  31.50, 62.00, 'GRADE_2', 'TREATING',  -4),
    ('TM-0332', N'Gân chi trước trái',  30.00, 60.50, 'GRADE_1', 'TREATING',  -2),
    ('TM-0624', N'Cơ mông phải',        68.00, 44.00, 'GRADE_1', 'PARTIAL',   -9),
    ('TM-0652', N'Móng sau phải',       74.50, 88.00, 'GRADE_1', 'RECOVERED', -60),
    ('TM-0455', N'Khuỷu chi sau trái',  66.00, 71.00, 'GRADE_2', 'RECOVERED', -90)
) AS v(reg_code, body_location, cx, cy, severity, status, marked_offset)
JOIN HORSE h ON h.registration_code = v.reg_code
/* Gắn chấn thương vào hồ sơ bệnh án gần nhất của chính con ngựa đó. Mọi con
   nêu ở đây đều đã có hồ sơ từ mục 5 - nếu thiếu, CROSS APPLY trả rỗng và
   dòng chấn thương biến mất không một lời cảnh báo. Câu kiểm ngay dưới bắt
   đúng trường hợp ấy. */
CROSS APPLY (
    SELECT TOP 1 record_id
    FROM MEDICAL_RECORD mr
    WHERE mr.horse_id = h.horse_id
    ORDER BY mr.exam_date DESC
) m;

/* Phải nằm ngay dưới câu INSERT, trong CÙNG một batch: @@ROWCOUNT chỉ nói về
   câu lệnh liền trước. Dùng nó thay vì đếm COUNT(*) cả bảng, vì nếu ai đó đã
   tạo chấn thương qua giao diện trước khi chạy V3 thì phép đếm cả bảng sẽ báo
   nhầm. */
IF @@ROWCOUNT <> 5
BEGIN
    THROW 50002, 'Muc 6 chen thieu dong INJURY - mot con ngua nao do chua co ho so benh an o muc 5.', 1;
END
GO

/* ---------- 7. Điều trị ---------- */

INSERT INTO TREATMENT (record_id, medicine_name, dosage, frequency, instruction, start_date, end_date)
SELECT m.record_id, v.medicine, v.dosage, v.frequency, v.instruction,
       CAST(DATEADD(DAY, v.start_offset, GETDATE()) AS DATE),
       CAST(DATEADD(DAY, v.end_offset,   GETDATE()) AS DATE)
FROM (VALUES
    ('TM-0645', N'Phenylbutazone', N'2 g',   N'2 lần/ngày', N'Trộn vào thức ăn sáng và chiều.', -4,  10),
    ('TM-0645', N'Chườm lạnh',     N'20 phút', N'3 lần/ngày', N'Sau mỗi lần dắt đi bộ.',        -4,  14),
    ('TM-0332', N'Flunixin',       N'1.1 mg/kg', N'1 lần/ngày', N'Tiêm tĩnh mạch, tối đa 5 ngày.', -2, 3),
    ('TM-0660', N'Dung dịch điện giải', N'60 ml', N'2 lần/ngày', N'Pha nước uống.',             -6,   8),
    ('TM-0624', N'Vitamin E + Selen', N'10 ml', N'1 lần/ngày', N'Hỗ trợ phục hồi cơ.',          -9,   5),
    ('TM-0652', N'Ngâm muối Epsom', N'15 phút', N'1 lần/ngày', N'Ngâm móng sau phải.',         -60, -40)
) AS v(reg_code, medicine, dosage, frequency, instruction, start_offset, end_offset)
JOIN HORSE h ON h.registration_code = v.reg_code
CROSS APPLY (
    SELECT TOP 1 record_id FROM MEDICAL_RECORD mr
    WHERE mr.horse_id = h.horse_id ORDER BY mr.exam_date DESC
) m;
GO

/* ---------- 8. Báo cáo sự cố ---------- */

INSERT INTO INCIDENT_REPORT (horse_id, reporter_id, symptoms, description, urgency, reported_at, status)
SELECT h.horse_id, u.user_id, v.symptoms, v.description, v.urgency,
       DATEADD(HOUR, v.hour_offset, GETDATE()), v.status
FROM (VALUES
    ('TM-0645', 'binh.lv', N'Đi khập khiễng chân trước trái', N'Phát hiện lúc dắt ra sân buổi sáng.', 'HIGH',   -96, 'EXAMINED'),
    ('TM-0660', 'nam.tq',  N'Bỏ ăn, tai nóng',               N'Bỏ nửa khẩu phần sáng.',              'HIGH',  -144, 'EXAMINED'),
    ('TM-0624', 'hieu.lm', N'Thở nặng sau buổi chạy',        N'Hồi phục chậm hơn thường lệ.',        'MEDIUM', -216, 'RECEIVED'),
    ('TM-0618', 'nam.tq',  N'Xước nhẹ cổ chân sau phải',     N'Có thể do va vào thành chuồng.',      'LOW',     -30, 'PENDING'),
    ('TM-0637', 'binh.lv', N'Bồn chồn, đi vòng trong chuồng', N'Kéo dài khoảng một giờ tối qua.',    'LOW',     -14, 'PENDING')
) AS v(reg_code, reporter, symptoms, description, urgency, hour_offset, status)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = v.reporter;
GO

/* ---------- 9. Khẩu phần ---------- */

INSERT INTO FEEDING_SCHEDULE (horse_id, meal_time, feed_type, quantity, unit, approved_by, note)
SELECT h.horse_id, v.meal_time, v.feed_type, v.quantity, v.unit, u.user_id, v.note
FROM (VALUES
    ('TM-0481', 'MORNING', N'Yến mạch',      3.50, N'kg',   N'Trộn thêm dầu lanh.'),
    ('TM-0481', 'NOON',    N'Cỏ khô timothy', 5.00, N'kg',  NULL),
    ('TM-0481', 'EVENING', N'Cám hỗn hợp',   2.50, N'kg',   NULL),
    ('TM-0410', 'MORNING', N'Yến mạch',      4.00, N'kg',   N'Khẩu phần ngựa đực giống.'),
    ('TM-0410', 'EVENING', N'Cỏ khô alfalfa', 6.00, N'kg',  NULL),
    ('TM-0332', 'MORNING', N'Cám ít tinh bột', 2.00, N'kg', N'Giảm tinh bột trong thời gian nghỉ.'),
    ('TM-0332', 'EVENING', N'Cỏ khô timothy', 5.50, N'kg',  NULL),
    ('TM-0612', 'MORNING', N'Yến mạch',      3.00, N'kg',   NULL),
    ('TM-0612', 'EVENING', N'Cám hỗn hợp',   2.50, N'kg',   NULL),
    ('TM-0660', 'MORNING', N'Cháo cám ấm',   2.00, N'kg',   N'Đang cách ly, cho ăn riêng.'),
    ('TM-0660', 'EVENING', N'Cỏ khô timothy', 4.00, N'kg',  N'Đang cách ly, cho ăn riêng.'),
    ('TM-0652', 'MORNING', N'Yến mạch',      3.20, N'kg',   NULL)
) AS v(reg_code, meal_time, feed_type, quantity, unit, note)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = 'thang.nd';
GO

/* ---------- 10. Việc hằng ngày ----------
   CK_TASK_done: is_completed = 1 thì bắt buộc có completed_at, và ngược lại. */

INSERT INTO DAILY_TASK (horse_id, task_type, assigned_to, task_date, is_completed, completed_at, note)
SELECT h.horse_id, v.task_type, u.user_id,
       CAST(DATEADD(DAY, v.day_offset, GETDATE()) AS DATE),
       v.done,
       CASE WHEN v.done = 1 THEN DATEADD(HOUR, v.day_offset * 24 + 9, GETDATE()) ELSE NULL END,
       v.note
FROM (VALUES
    ('TM-0481', 'FEEDING',  'binh.lv',  0, 1, NULL),
    ('TM-0481', 'CLEANING', 'binh.lv',  0, 1, NULL),
    ('TM-0410', 'FEEDING',  'binh.lv',  0, 1, NULL),
    ('TM-0410', 'BATHING',  'nam.tq',   0, 0, N'Sau buổi tập chiều.'),
    ('TM-0332', 'ICE_BATH', 'nam.tq',   0, 0, N'Theo chỉ định của bác sĩ.'),
    ('TM-0332', 'FEEDING',  'nam.tq',   0, 1, NULL),
    ('TM-0612', 'CLEANING', 'hieu.lm',  0, 0, NULL),
    ('TM-0612', 'FEEDING',  'hieu.lm',  0, 1, NULL),
    ('TM-0645', 'ICE_BATH', 'hieu.lm',  0, 0, N'Chân trước trái, 20 phút.'),
    ('TM-0660', 'CLEANING', 'nam.tq',   0, 0, N'Dùng dụng cụ riêng, đang cách ly.'),
    ('TM-0652', 'FEEDING',  'binh.lv', -1, 1, NULL),
    ('TM-0652', 'BATHING',  'binh.lv', -1, 1, NULL)
) AS v(reg_code, task_type, assignee, day_offset, done, note)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = v.assignee;
GO

/* ---------- 11. Kho vật tư ----------
   needs_restock là cột tính sẵn, không chèn vào. Ba dòng dưới ngưỡng để màn
   hình nào lọc "cần nhập thêm" cũng có dữ liệu mà hiện. */

INSERT INTO SUPPLY_STOCK (supply_name, category, unit, min_threshold, current_qty, area, updated_by)
SELECT v.supply_name, v.category, v.unit, v.min_threshold, v.current_qty, v.area, u.user_id
FROM (VALUES
    (N'Yến mạch',              'FEED',     N'kg',   200.00, 540.00, N'Kho A'),
    (N'Cỏ khô timothy',        'FEED',     N'kiện', 60.00,  48.00,  N'Kho A'),
    (N'Cỏ khô alfalfa',        'FEED',     N'kiện', 40.00,  92.00,  N'Kho A'),
    (N'Cám hỗn hợp',           'FEED',     N'kg',   150.00, 310.00, N'Kho A'),
    (N'Phenylbutazone',        'MEDICINE', N'tuýp', 10.00,  6.00,   N'Tủ thuốc'),
    (N'Flunixin',              'MEDICINE', N'lọ',   8.00,   15.00,  N'Tủ thuốc'),
    (N'Dung dịch điện giải',   'MEDICINE', N'lít',  20.00,  34.00,  N'Tủ thuốc'),
    (N'Băng quấn chân',        'TOOL',     N'cuộn', 50.00,  22.00,  N'Kho B'),
    (N'Lược chải lông',        'TOOL',     N'cái',  15.00,  28.00,  N'Kho B'),
    (N'Móng sắt',              'TOOL',     N'bộ',   25.00,  61.00,  N'Kho B')
) AS v(supply_name, category, unit, min_threshold, current_qty, area)
CROSS JOIN (SELECT user_id FROM APP_USER WHERE username = 'hoa.tm') u;
GO

/* ---------- 12. Đăng ký đua ----------
   UQ_REG_entry chặn đăng ký trùng (ngựa, tên giải, ngày). */

INSERT INTO RACE_REGISTRATION (horse_id, race_name, race_date, registered_by, result, prize_money, note)
SELECT h.horse_id, v.race_name,
       CAST(DATEADD(DAY, v.day_offset, GETDATE()) AS DATE),
       u.user_id, v.result, v.prize_money, v.note
FROM (VALUES
    ('TM-0410', N'Tenma Spring Cup 2026',  -120, 'WON',       450000000.00, N'Dẫn đầu từ khúc cua cuối.'),
    ('TM-0481', N'Tenma Spring Cup 2026',  -120, 'PLACED',    120000000.00, N'Về nhì, kém nửa thân.'),
    ('TM-0298', N'Hanoi Derby 2026',        -75, 'PLACED',     80000000.00, NULL),
    ('TM-0455', N'Hanoi Derby 2026',        -75, 'UNPLACED',          0.00, NULL),
    ('TM-0507', N'Autumn Mile 2026',        -40, 'WON',       260000000.00, NULL),
    ('TM-0344', N'Autumn Mile 2026',        -40, 'UNPLACED',          0.00, N'Xuất phát chậm.'),
    ('TM-0612', N'Autumn Mile 2026',        -40, 'PLACED',     70000000.00, NULL),
    ('TM-0410', N'Tenma Cup 2026',           21, NULL,                NULL, N'Đã nộp hồ sơ, chờ cân ngựa.'),
    ('TM-0481', N'Tenma Cup 2026',           21, NULL,                NULL, N'Đã nộp hồ sơ.'),
    ('TM-0631', N'Tenma Cup 2026',           21, NULL,                NULL, N'Chờ xác nhận sức khoẻ.')
) AS v(reg_code, race_name, day_offset, result, prize_money, note)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = 'hoa.tm';
GO

/* ---------- 13. Chi phí ---------- */

INSERT INTO EXPENSE_LOG (horse_id, category, amount, expense_date, recorded_by, note)
SELECT h.horse_id, v.category, v.amount,
       CAST(DATEADD(DAY, v.day_offset, GETDATE()) AS DATE), u.user_id, v.note
FROM (VALUES
    ('TM-0410', 'FEEDING',   8400000.00,  -30, N'Khẩu phần tháng trước.'),
    ('TM-0410', 'TRAINING', 15000000.00,  -30, NULL),
    ('TM-0481', 'FEEDING',   7900000.00,  -30, NULL),
    ('TM-0481', 'MEDICAL',   1200000.00,  -28, N'Tiêm cúm ngựa.'),
    ('TM-0332', 'MEDICAL',   6500000.00,   -4, N'Chụp siêu âm gân.'),
    ('TM-0332', 'FEEDING',   6100000.00,  -30, NULL),
    ('TM-0645', 'MEDICAL',   9800000.00,   -5, N'Chụp chiếu và thuốc.'),
    ('TM-0660', 'MEDICAL',   4300000.00,   -7, N'Xét nghiệm máu, cách ly.'),
    ('TM-0298', 'OTHER',     2100000.00,  -12, N'Thay móng bốn chân.'),
    ('TM-0612', 'TRAINING', 12000000.00,  -25, NULL),
    ('TM-0507', 'FEEDING',   7200000.00,  -30, NULL),
    ('TM-0652', 'TRAINING', 11000000.00,  -25, NULL)
) AS v(reg_code, category, amount, day_offset, note)
JOIN HORSE h    ON h.registration_code = v.reg_code
JOIN APP_USER u ON u.username = 'hoa.tm';
GO

/* =============================================================================
   GHI CHÚ ĐỂ LẠI CHO NHÓM

   1. Lược đồ chưa có trạng thái "đã nghỉ" cho ngựa.
      CK_HORSE_status chỉ nhận ELIGIBLE, MONITOR, INJURED, QUARANTINE, nên 20
      con tổ tiên ở mục 2 buộc phải mang ELIGIBLE dù chúng không còn tập nữa.
      Hệ quả thấy ngay trên màn UC7: danh sách đăng ký giờ lẫn cả ngựa trong sổ
      giống với ngựa đang nuôi, và bộ đếm "Eligible" cao hơn số ngựa thật sự
      sẵn sàng tập.
      Cách sửa gọn nhất là thêm 'RETIRED' vào CK_HORSE_status bằng một file V4,
      rồi lọc nó ra khỏi danh sách mặc định. Tôi không tự làm vì đổi ràng buộc
      là đụng tới lược đồ, nên để cả nhóm quyết.

   2. Mật khẩu của 10 tài khoản mới đều là 123456, giống V2.
      Chỉ dùng để demo.
   ============================================================================= */
