-- Đăng nhập Google: lưu "sub" (mã định danh cố định của tài khoản Google) để nhận ra người dùng,
-- không dựa vào email vì email có thể đổi.
IF COL_LENGTH('APP_USER', 'google_sub') IS NULL
    ALTER TABLE APP_USER ADD google_sub VARCHAR(255) NULL;

-- EXEC để SQL Server không biên dịch dòng này trước khi cột google_sub kịp được tạo ở trên.
-- Chỉ ràng buộc UNIQUE trên các dòng có google_sub (user thường để NULL, nhiều NULL vẫn hợp lệ).
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_APP_USER_google_sub' AND object_id = OBJECT_ID('APP_USER'))
    EXEC('CREATE UNIQUE INDEX UX_APP_USER_google_sub ON APP_USER(google_sub) WHERE google_sub IS NOT NULL');
