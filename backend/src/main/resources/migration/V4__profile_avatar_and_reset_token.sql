/* =============================================================================
   V4 - Bổ sung lược đồ cho UC4 và UC5.

   VÌ SAO CẦN FILE NÀY
   Mã nguồn backend của UC4 và UC5 đã nằm trên main, nhưng phần lược đồ đi kèm
   thì chưa bao giờ được viết. Hai chỗ thiếu:

     1. APP_USER.avatar_url
        ProfileRepository đọc và ghi cột này. Thiếu nó thì GET /api/profile trả
        500 ngay lần gọi đầu:
            Invalid column name 'avatar_url'
        Nghĩa là UC4 hiện không mở được màn hồ sơ.

     2. Bảng PASSWORD_RESET_TOKEN
        PasswordRepository chèn, vô hiệu hoá và tiêu thụ token ở bảng này.
        Thiếu nó thì POST /api/auth/forgot-password sẽ hỏng y hệt, chỉ là chưa
        ai bấm tới nên chưa lộ ra.

   Lỗi thứ hai chưa ai gặp, nhưng nó đang nằm đó chờ. Sửa cả hai cùng lúc để
   không phải reset database thêm một lần nữa.

   KHÔNG SỬA V1
   V1 đã chạy trên mọi máy. Thêm cột vào đó là đổi checksum và cả nhóm lại
   không khởi động được, đúng chuyện đã xảy ra hôm 04/10.

   CÁC CÂU LỆNH ĐỀU CÓ THỂ CHẠY LẠI
   Nếu ai đó đã tự thêm cột hoặc bảng bằng tay để chữa cháy, file này nhận ra
   và bỏ qua thay vì báo lỗi "đã tồn tại" rồi chặn khởi động.
   ============================================================================= */

/* ---------- 1. Ảnh đại diện ----------
   VARCHAR(255) khớp với photo_url của INCIDENT_REPORT. ProfileService sinh
   đường dẫn dạng /uploads/avatars/<user_id>-<uuid>.<ext>, dài khoảng 60 ký tự,
   nên 255 là rộng rãi.

   Cho phép NULL: tài khoản chưa tải ảnh thì giao diện hiện chữ viết tắt. */

IF COL_LENGTH('APP_USER', 'avatar_url') IS NULL
BEGIN
    ALTER TABLE APP_USER ADD avatar_url VARCHAR(255) NULL;
    PRINT 'Da them cot APP_USER.avatar_url.';
END
ELSE
BEGIN
    PRINT 'Cot APP_USER.avatar_url da co san, bo qua.';
END
GO

/* ---------- 2. Token đặt lại mật khẩu ----------

   Cơ sở dữ liệu chỉ giữ BẢN BĂM của token, không giữ token gốc. Token gốc chỉ
   tồn tại đúng một lần trong liên kết gửi cho người dùng. PasswordService băm
   bằng SHA-256 rồi mới tra, nên kẻ đọc được cả bảng này cũng không dựng lại
   được liên kết nào.

   token_hash là chuỗi hex của SHA-256, luôn đúng 64 ký tự, chỉ gồm 0-9 và a-f.
   Dùng CHAR(64) thay vì VARCHAR: độ dài cố định thì không phải lưu thêm phần
   mô tả độ dài, và so sánh nhanh hơn một chút.

   expires_at và used_at đều do SYSUTCDATETIME() của SQL Server sinh ra, không
   phải đồng hồ của Java - nếu hai máy lệch múi giờ hoặc lệch giờ, hạn dùng vẫn
   tính theo một nguồn duy nhất. */

IF OBJECT_ID('PASSWORD_RESET_TOKEN', 'U') IS NULL
BEGIN
    CREATE TABLE PASSWORD_RESET_TOKEN (
        token_id   INT IDENTITY(1,1) PRIMARY KEY,
        user_id    INT       NOT NULL,
        token_hash CHAR(64)  NOT NULL,
        expires_at DATETIME2 NOT NULL,
        used_at    DATETIME2 NULL,
        created_at DATETIME2 NOT NULL
            CONSTRAINT DF_PRT_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT FK_PRT_user FOREIGN KEY (user_id) REFERENCES APP_USER(user_id),
        /* Hạn dùng phải ở sau lúc tạo. Chặn luôn trường hợp ai đó gọi
           insertToken với validMinutes âm hoặc bằng 0, sinh ra một token chết
           ngay từ lúc sinh ra mà không báo gì. */
        CONSTRAINT CK_PRT_expiry CHECK (expires_at > created_at)
    );

    /* consumeValidToken tra theo token_hash trên toàn bảng, không kèm user_id,
       nên cột này cần index. Để UNIQUE luôn: hai token khác nhau mà trùng băm
       SHA-256 là chuyện không xảy ra trên thực tế, nên nếu nó xảy ra thì đó là
       lỗi lập trình - chặn ngay còn hơn để hai người dùng chung một token. */
    CREATE UNIQUE INDEX UX_PRT_hash ON PASSWORD_RESET_TOKEN(token_hash);

    /* invalidateOpenTokens quét theo user_id và used_at IS NULL. Index lọc chỉ
       chứa các token còn hiệu lực - phần lớn hàng trong bảng là token đã dùng
       hoặc đã hết hạn, không cần nằm trong index này. */
    CREATE INDEX IX_PRT_open ON PASSWORD_RESET_TOKEN(user_id)
        WHERE used_at IS NULL;

    PRINT 'Da tao bang PASSWORD_RESET_TOKEN.';
END
ELSE
BEGIN
    PRINT 'Bang PASSWORD_RESET_TOKEN da co san, bo qua.';
END
GO

/* ---------- 3. Xác nhận ----------
   Dừng ngay tại migration nếu vì lý do nào đó hai thứ trên vẫn chưa có. Không
   có câu này thì lỗi chỉ lộ ra lúc ai đó mở màn hồ sơ và nhận 500. */

IF COL_LENGTH('APP_USER', 'avatar_url') IS NULL
    THROW 50003, 'Khong tao duoc cot APP_USER.avatar_url.', 1;

IF OBJECT_ID('PASSWORD_RESET_TOKEN', 'U') IS NULL
    THROW 50004, 'Khong tao duoc bang PASSWORD_RESET_TOKEN.', 1;
GO

PRINT 'V4 hoan tat.';
GO
