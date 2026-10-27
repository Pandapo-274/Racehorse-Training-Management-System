/* =============================================================================
   V9 - Ảnh ngựa.

   Chủ ngựa tải ảnh cho ngựa của mình (POST /api/horses/{id}/photo). File lưu
   trong thư mục upload, cột này chỉ giữ đường dẫn dạng
   /uploads/horses/<horse_id>-<uuid>.<ext>, giống APP_USER.avatar_url ở V4.

   Cho phép NULL: ngựa chưa có ảnh thì giao diện hiện chữ viết tắt.
   Chạy lại được, như V4 và V6.
   ============================================================================= */

IF COL_LENGTH('HORSE', 'photo_url') IS NULL
    ALTER TABLE HORSE ADD photo_url VARCHAR(255) NULL;
GO

PRINT 'V9 hoan tat.';
GO
