/* =============================================================================
   V14 - Mật khẩu tạm của tài khoản nhân viên.

   VẤN ĐỀ
   Từ khi chỉ HORSE_OWNER được tự đăng ký, tài khoản Head Trainer, Vet, Groom do
   Club Manager tạo trong trang Staff, kèm mật khẩu ban đầu. Manager biết mật
   khẩu đó, nên nó chỉ được dùng để đăng nhập lần đầu: người dùng phải đổi
   sang mật khẩu của riêng mình trước khi làm bất cứ việc gì khác.

   must_change_password = 1: tài khoản đang dùng mật khẩu tạm.
     - StaffService bật cờ khi tạo tài khoản.
     - Đổi mật khẩu, đặt lại mật khẩu qua email, hoặc gắn Google (mật khẩu bị
       thay bằng chuỗi ngẫu nhiên) đều tắt cờ.
     - AuthInterceptor chặn mọi API khác (428) khi cờ còn bật.

   Mọi tài khoản đang có mặc định 0: không ai bị bắt đổi mật khẩu đột ngột.
   Chạy lại được.
   ============================================================================= */

IF COL_LENGTH('APP_USER', 'must_change_password') IS NULL
    ALTER TABLE APP_USER ADD must_change_password BIT NOT NULL
        CONSTRAINT DF_USER_must_change DEFAULT 0;
GO

PRINT 'V14 hoan tat.';
GO
