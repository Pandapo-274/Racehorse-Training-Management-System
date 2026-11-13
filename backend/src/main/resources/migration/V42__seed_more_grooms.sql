/* =============================================================================
   V41 - Tạo thêm 7 tài khoản Groom cho mục đích kiểm thử phần mềm.
   Mật khẩu mẫu cho tất cả các tài khoản là: 123456
   BCrypt hash: $2b$10$W0zKT4yUrmErFfpx9KvfN.QSGp0t7eA3ueZpY8hnJjsiwfJy.fQ66
   ============================================================================= */

INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone, status, must_change_password)
SELECT r.role_id, v.username,
       '$2b$10$W0zKT4yUrmErFfpx9KvfN.QSGp0t7eA3ueZpY8hnJjsiwfJy.fQ66',
       v.full_name, v.email, v.phone, 'ACTIVE', 0
FROM (VALUES
    ('phuc.nd',   N'Nguyễn Đình Phúc', 'phuc.nd@tracen.vn',   '0901000025'),
    ('dat.pt',    N'Phan Tiến Đạt',    'dat.pt@tracen.vn',    '0901000026'),
    ('long.vh',   N'Vũ Hoàng Long',    'long.vh@tracen.vn',   '0901000027'),
    ('hung.tv',   N'Trần Văn Hùng',    'hung.tv@tracen.vn',   '0901000028'),
    ('tri.nm',    N'Ngô Minh Trí',     'tri.nm@tracen.vn',    '0901000029'),
    ('khoa.da',   N'Đặng Anh Khoa',    'khoa.da@tracen.vn',   '0901000030'),
    ('thanh.bm',  N'Bùi Minh Thành',   'thanh.bm@tracen.vn',  '0901000031')
) AS v(username, full_name, email, phone)
CROSS JOIN ROLE r
WHERE r.role_name = 'GROOM'
  AND NOT EXISTS (SELECT 1 FROM APP_USER u WHERE u.username = v.username OR u.email = v.email);
GO

PRINT 'V41: Added 7 groom accounts for testing.';
GO
