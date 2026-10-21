/* =============================================================================
   V7 - Thêm chủ ngựa, thêm huấn luyện viên, và chia lại đàn ngựa.

   VẤN ĐỀ
   anh.dq đang ôm phần lớn đàn ngựa. Trong số đó có một nhóm bản ghi tổ tiên
   (mã TM-A%) chỉ tồn tại để làm cha mẹ trong cây phả hệ - không chuồng, không
   bao giờ ra sân. Mọi màn hình "ngựa của tôi" đăng nhập bằng tài khoản khác
   đều gần như trống. Tương tự với huấn luyện viên: các giáo án của V2 đều do
   một mình thang.nd tạo.

   NGUYÊN TẮC VỀ PHÉP KIỂM - đọc trước khi sửa file này
   Bản V7 đầu tiên THROW vì nó chốt cứng "phải đổi đúng 23 bản ghi phả hệ".
   Mã nguồn V3 đúng là chèn 23 dòng, nhưng database thật của từng người đã
   lệch khỏi mã nguồn - mỗi máy một DB riêng, ai cũng đã thêm bớt qua giao
   diện. Một migration dữ liệu chốt cứng số dòng sẽ chạy được trên máy này và
   hỏng trên máy khác.

   Nên V7 chia phép kiểm làm hai loại:
     - THROW cho những gì file này TỰ kiểm soát: tài khoản nó tạo, ngựa nó
       chèn, bất biến nó phải giữ. Sai ở đây là lỗi của chính file này.
     - PRINT cho những gì phụ thuộc trạng thái database: còn bao nhiêu bản ghi
       phả hệ, còn con ngựa nào của V2. Ít hơn dự kiến không phải lỗi, nhưng
       phải hiện ra trong log để người chạy biết máy mình đang có gì.

   V7 KHÔNG đụng vào record_type. V6 đã thêm cột ACADEMY/PEDIGREE và cố ý để
   phần chuyển đổi ở dạng chú thích cho người chạy tay, vì chuyển sang PEDIGREE
   sẽ xoá luôn weight (CK_HORSE_pedigree). Đó là quyết định của người viết V6.
   ============================================================================= */

/* ---------- 1. Tài khoản mới ----------
   Dùng đúng chuỗi băm của V2/V3 nên mật khẩu demo giống hệt tài khoản cũ.

   NOT EXISTS để file chạy được cả trên database đã có sẵn vài tài khoản này
   (ví dụ ai đó đã tạo tay). Không có nó thì UNIQUE trên username sẽ làm hỏng
   cả migration vì một dòng trùng. */

INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone, status)
SELECT r.role_id, v.username,
       '$2b$10$W0zKT4yUrmErFfpx9KvfN.QSGp0t7eA3ueZpY8hnJjsiwfJy.fQ66',
       v.full_name, v.email, v.phone, 'ACTIVE'
FROM (VALUES
    ('HORSE_OWNER',  'huong.nt',      N'Nguyễn Thị Hương',  'huong.nt@tenma.vn',      '0901000017'),
    ('HORSE_OWNER',  'dung.pv',       N'Phạm Văn Dũng',     'dung.pv@tenma.vn',       '0901000018'),
    ('HORSE_OWNER',  'thao.lk',       N'Lưu Khánh Thảo',    'thao.lk@tenma.vn',       '0901000019'),
    ('HORSE_OWNER',  'quan.vm',       N'Vũ Minh Quân',      'quan.vm@tenma.vn',       '0901000020'),

    /* nga.bt CỐ Ý không có con ngựa nào. Màn hình chủ ngựa có một trạng thái
       rỗng kèm nút gửi yêu cầu thêm ngựa; mọi chủ đều có ngựa thì không còn
       đường nào xem trạng thái ấy. cuong.bm cũng rỗng nhưng đang SUSPENDED
       nên không đăng nhập được, không thay thế được vai trò này. */
    ('HORSE_OWNER',  'nga.bt',        N'Bùi Thanh Nga',     'nga.bt@tenma.vn',        '0901000021'),

    /* Pháp nhân đứng tên các bản ghi phả hệ. Chúng là ngựa giống đời trước,
       không phải ngựa học viện, nên để một pháp nhân riêng đúng hơn là lẫn
       vào danh sách ngựa của một chủ thật. */
    ('HORSE_OWNER',  'bloodstock.tm', N'Tenma Bloodstock',  'bloodstock.tm@tenma.vn', '0901000022'),

    ('HEAD_TRAINER', 'kien.hm',       N'Hoàng Minh Kiên',   'kien.hm@tenma.vn',       '0901000023'),
    ('HEAD_TRAINER', 'oanh.tt',       N'Tạ Thu Oanh',       'oanh.tt@tenma.vn',       '0901000024')
) AS v(role_name, username, full_name, email, phone)
JOIN ROLE r ON r.role_name = v.role_name
WHERE NOT EXISTS (SELECT 1 FROM APP_USER a WHERE a.username = v.username);

PRINT CONCAT('V7.1 - tai khoan moi da tao: ', @@ROWCOUNT);
GO

/* Tám tài khoản này là thứ V7 tự kiểm soát, nên thiếu một cái là lỗi thật:
   hoặc tên ROLE sai, hoặc email/phone đụng hàng với dòng đã có. */
IF (SELECT COUNT(*) FROM APP_USER WHERE username IN
        ('huong.nt','dung.pv','thao.lk','quan.vm','nga.bt','bloodstock.tm','kien.hm','oanh.tt')) <> 8
    THROW 50070, N'V7: thieu tai khoan trong 8 tai khoan V7 phai co.', 1;
GO

/* ---------- 2. Chuyển bản ghi phả hệ sang Tenma Bloodstock ----------
   Lọc theo tiền tố mã TM-A% chứ không theo tuổi: V3 cấp dải mã riêng cho
   nhóm tổ tiên nên đây là phép lọc chính xác, còn lọc theo tuổi (gợi ý trong
   V6) sẽ quét trúng cả ngựa học viện già.

   KHÔNG chốt cứng số dòng. Bao nhiêu bản ghi phả hệ là chuyện của từng
   database; việc của câu này là dồn hết chúng về một chỗ, dù là 23 hay 11. */

DECLARE @pedigree_before INT =
    (SELECT COUNT(*) FROM HORSE WHERE registration_code LIKE 'TM-A%');

UPDATE HORSE
SET owner_id = (SELECT user_id FROM APP_USER WHERE username = 'bloodstock.tm')
WHERE registration_code LIKE 'TM-A%';

PRINT CONCAT('V7.2 - ban ghi pha he chuyen sang bloodstock.tm: ', @@ROWCOUNT,
             ' (database nay co ', @pedigree_before, ' ban ghi TM-A%)');

/* Đây mới là điều kiện thật sự quan trọng, và nó không phụ thuộc số lượng:
   sau câu trên, không được còn bản ghi phả hệ nào đứng tên người khác. */
IF EXISTS (
    SELECT 1 FROM HORSE h
    JOIN APP_USER u ON u.user_id = h.owner_id
    WHERE h.registration_code LIKE 'TM-A%' AND u.username <> 'bloodstock.tm'
)
    THROW 50071, N'V7: van con ban ghi pha he dung ten chu khac.', 1;
GO

/* ---------- 3. Chia lại những con ngựa của V2 ----------
   TM-0481 (Symboli Rudolf) GIỮ NGUYÊN cho anh.dq: frontend đang hardcode mã
   này trong HorseOwnerDashboard.js và GroomDashboard.js, chuyển đi sẽ làm hai
   màn hình ấy nói sai mà không ai thấy ngay. TM-0410 cũng giữ lại để anh.dq
   không bị rỗng.

   Tra theo registration_code chứ không theo horse_id: IDENTITY phụ thuộc thứ
   tự chèn nên id trên máy mỗi người một khác.

   Con nào không còn trong database thì bỏ qua - không THROW. Đó là trạng thái
   database, không phải lỗi của file này. Nhưng số con tìm thấy được in ra để
   người chạy đối chiếu. */

DECLARE @reassign TABLE (reg_code VARCHAR(30), owner_username VARCHAR(50));
INSERT INTO @reassign VALUES
    ('TM-0332', 'trang.lt'),   -- Air Groove (INJURED, đang có lệnh khoá)
    ('TM-0298', 'khanh.nv'),   -- Gold Ship
    ('TM-0507', 'huong.nt'),   -- Kitasan Black
    ('TM-0276', 'dung.pv'),    -- Vodka (QUARANTINE)
    ('TM-0455', 'thao.lk'),    -- Daiwa Scarlet
    ('TM-0389', 'quan.vm'),    -- TM Opera O
    ('TM-0523', 'mai.ht'),     -- Almond Eye
    ('TM-0344', 'son.dv');     -- Orfevre

/* Chủ ngựa đích phải tồn tại hết - đó là thứ V7 kiểm soát, khác với con ngựa.
   mai.ht và son.dv đến từ V3; nếu database thiếu chúng thì việc chia lại sẽ
   lặng lẽ bỏ sót hai con mà không ai biết. */
IF EXISTS (
    SELECT 1 FROM @reassign r
    WHERE NOT EXISTS (SELECT 1 FROM APP_USER u WHERE u.username = r.owner_username)
)
    THROW 50072, N'V7: mot chu ngua dich khong ton tai - kiem tra V3 da chay chua.', 1;

UPDATE h
SET h.owner_id = u.user_id
FROM HORSE h
JOIN @reassign r  ON r.reg_code = h.registration_code
JOIN APP_USER u   ON u.username = r.owner_username;

PRINT CONCAT('V7.3 - ngua cua V2 doi chu: ', @@ROWCOUNT, '/8 (con lai khong co trong DB nay)');
GO

/* ---------- 4. Thêm ngựa học viện cho các chủ mới ----------
   record_type để mặc định ACADEMY (DF_HORSE_rtype của V6) nên có chuồng và
   cân nặng là hợp lệ với CK_HORSE_pedigree.

   Hai phép phòng va chạm, vì database mỗi người một khác:
     - mã đăng ký đã tồn tại thì bỏ qua cả dòng;
     - ô chuồng đã có ngựa thì vẫn chèn nhưng để stall_no NULL, vì
       UX_HORSE_stall là chỉ mục duy nhất có điều kiện trên stall_no. Thà một
       con ngựa chưa xếp chuồng còn hơn cả migration đổ. */

INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender,
                   date_of_birth, color, weight, stall_no, status)
SELECT u.user_id, v.horse_name, v.registration_code, v.breed, v.horse_gender,
       v.date_of_birth, v.color, v.weight,
       CASE WHEN EXISTS (SELECT 1 FROM HORSE x WHERE x.stall_no = v.stall_no)
            THEN NULL ELSE v.stall_no END,
       v.status
FROM (VALUES
    ('huong.nt', N'Maruzensky',      'TM-0701', 'Thoroughbred', 'STALLION', '2021-05-18', N'Chestnut', 492.0, 'A-02', 'ELIGIBLE'),
    ('dung.pv',  N'Tokai Teio',      'TM-0708', 'Thoroughbred', 'COLT',     '2022-04-20', N'Bay',      468.5, 'A-04', 'ELIGIBLE'),
    ('thao.lk',  N'Mihono Bourbon',  'TM-0715', 'Thoroughbred', 'COLT',     '2022-04-25', N'Dark bay', 475.0, 'B-04', 'MONITOR'),
    ('quan.vm',  N'Rice Shower',     'TM-0722', 'Thoroughbred', 'GELDING',  '2021-03-05', N'Black',    462.0, 'B-09', 'ELIGIBLE'),
    ('yen.pt',   N'Fine Motion',     'TM-0729', 'Thoroughbred', 'MARE',     '2020-02-14', N'Bay',      470.5, 'C-03', 'ELIGIBLE'),
    ('son.dv',   N'Agnes Tachyon',   'TM-0736', 'Thoroughbred', 'STALLION', '2021-04-13', N'Chestnut', 488.0, 'A-06', 'ELIGIBLE'),
    ('trang.lt', N'Sakura Bakushin', 'TM-0743', 'Thoroughbred', 'COLT',     '2023-04-05', N'Chestnut', 444.0, 'C-06', 'ELIGIBLE'),
    ('khanh.nv', N'Haru Urara',      'TM-0750', 'Thoroughbred', 'FILLY',    '2023-02-27', N'Bay',      431.0, 'C-07', 'ELIGIBLE')
) AS v(owner_username, horse_name, registration_code, breed, horse_gender,
       date_of_birth, color, weight, stall_no, status)
JOIN APP_USER u ON u.username = v.owner_username
WHERE NOT EXISTS (SELECT 1 FROM HORSE x WHERE x.registration_code = v.registration_code);

PRINT CONCAT('V7.4 - ngua moi da chen: ', @@ROWCOUNT);
GO

/* Tám con này do chính V7 chèn nên phải có đủ. Thiếu nghĩa là một username
   chủ ngựa sai, và con ngựa đó rơi mất không một lời báo. */
IF (SELECT COUNT(*) FROM HORSE WHERE registration_code IN
        ('TM-0701','TM-0708','TM-0715','TM-0722','TM-0729','TM-0736','TM-0743','TM-0750')) <> 8
    THROW 50073, N'V7: thieu ngua trong 8 con V7 phai chen - kiem tra username chu ngua.', 1;
GO

/* ---------- 5. Chia giáo án cho nhiều huấn luyện viên ----------
   Giáo án nào không còn thì bỏ qua: số giáo án phụ thuộc database. Nhưng
   huấn luyện viên đích phải tồn tại hết. */

DECLARE @plans TABLE (reg_code VARCHAR(30), trainer_username VARCHAR(50));
INSERT INTO @plans VALUES
    ('TM-0298', 'lam.tb'),
    ('TM-0507', 'tuan.ng'),
    ('TM-0410', 'kien.hm');

IF EXISTS (
    SELECT 1 FROM @plans p
    WHERE NOT EXISTS (
        SELECT 1 FROM APP_USER u
        JOIN ROLE r ON r.role_id = u.role_id
        WHERE u.username = p.trainer_username AND r.role_name = 'HEAD_TRAINER')
)
    THROW 50074, N'V7: mot huan luyen vien dich khong ton tai hoac sai vai tro.', 1;

UPDATE p
SET p.created_by = u.user_id
FROM TRAINING_PLAN p
JOIN HORSE h      ON h.horse_id = p.horse_id
JOIN @plans t     ON t.reg_code = h.registration_code
JOIN APP_USER u   ON u.username = t.trainer_username;

PRINT CONCAT('V7.5 - giao an doi nguoi tao: ', @@ROWCOUNT, '/3');
GO

/* ---------- 5b. Một giáo án nháp cho huấn luyện viên còn lại ----------
   V2 chỉ có 4 giáo án, chia ở mục 5 là hết; oanh.tt sẽ không có gì để xem.
   Giáo án DRAFT chưa có giai đoạn là trạng thái có thật - giáo án đang soạn
   dở - nên không cần bịa thêm PLAN_PHASE. PLAN_PHASE trỏ vào TRAINING_PLAN
   chứ không ngược lại, nên không ràng buộc nào bị hở.

   TM-0715 do mục 4 vừa chèn và mục 4 có guard 50073, nên chắc chắn tồn tại. */

INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
SELECT h.horse_id,
       (SELECT user_id FROM APP_USER WHERE username = 'oanh.tt'),
       N'Giáo án nền tảng mùa xuân - ' + h.horse_name,
       CAST(DATEADD(WEEK, 2,  GETDATE()) AS DATE),
       CAST(DATEADD(WEEK, 14, GETDATE()) AS DATE),
       'DRAFT'
FROM HORSE h
WHERE h.registration_code = 'TM-0715'
  AND NOT EXISTS (
      SELECT 1 FROM TRAINING_PLAN p
      WHERE p.horse_id = h.horse_id
        AND p.created_by = (SELECT user_id FROM APP_USER WHERE username = 'oanh.tt'));

PRINT CONCAT('V7.5b - giao an nhap cho oanh.tt: ', @@ROWCOUNT);
GO

/* ---------- 6. Bất biến cuối ----------
   Phải còn ít nhất một chủ ngựa ACTIVE không sở hữu con nào, nếu không màn
   hình trạng thái rỗng của chủ ngựa mất đường kiểm thử. Đây là thứ V7 kiểm
   soát được (nó vừa tạo nga.bt và không gán ngựa cho ai không có trong danh
   sách), nên THROW là đúng. */

IF NOT EXISTS (
    SELECT 1 FROM APP_USER u
    JOIN ROLE r ON r.role_id = u.role_id
    WHERE r.role_name = 'HORSE_OWNER' AND u.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM HORSE h WHERE h.owner_id = u.user_id)
)
    THROW 50076, N'V7: khong con chu ngua ACTIVE nao rong - mat duong kiem thu trang thai rong.', 1;

PRINT '--- V7 hoan tat. Phan bo ngua theo chu: ---';
SELECT u.username, u.full_name, COUNT(h.horse_id) AS so_ngua
FROM APP_USER u
JOIN ROLE r       ON r.role_id = u.role_id
LEFT JOIN HORSE h ON h.owner_id = u.user_id
WHERE r.role_name = 'HORSE_OWNER'
GROUP BY u.username, u.full_name
ORDER BY so_ngua DESC;
GO
