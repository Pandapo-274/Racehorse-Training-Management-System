/* =============================================================================
   V40 - Gán bắt buộc Groom cho Training Plan và quy tắc giới hạn:

   1. TRAINING_PLAN.groom_id:
      - Khi tạo giáo án huấn luyện (Training Plan), Head Trainer bắt buộc phải chọn 1 Groom
        đang thuộc quyền quản lý của mình.
      - FK tham chiếu đến APP_USER(user_id).

   2. Giới hạn nghiệp vụ:
      - 1 Groom tiếp nhận tối đa 2 plan cùng 1 lúc (status <> 'COMPLETED').
      - Khi Groom đang tiếp nhận ít nhất 1 plan (status <> 'COMPLETED'),
        không được phép chuyển sự quản lý sang Head Trainer khác.

   3. Backfill dữ liệu có sẵn:
      - Gán groom_id cho các giáo án cũ dựa theo Groom thuộc Head Trainer tạo giáo án đó,
        hoặc Groom đang hoạt động trong hệ thống.
   ============================================================================= */

IF COL_LENGTH('TRAINING_PLAN', 'groom_id') IS NULL
BEGIN
    ALTER TABLE TRAINING_PLAN ADD groom_id INT NULL;
END
GO

IF OBJECT_ID('FK_PLAN_groom', 'F') IS NULL
BEGIN
    ALTER TABLE TRAINING_PLAN ADD CONSTRAINT FK_PLAN_groom
        FOREIGN KEY (groom_id) REFERENCES APP_USER(user_id);
END
GO

/* Backfill groom_id cho các giáo án hiện có */
UPDATE tp
SET tp.groom_id = COALESCE(
    (
        SELECT TOP 1 u.user_id
        FROM APP_USER u
        JOIN ROLE r ON r.role_id = u.role_id
        WHERE r.role_name = 'GROOM'
          AND u.status = 'ACTIVE'
          AND u.trainer_id = tp.created_by
        ORDER BY u.user_id
    ),
    (
        SELECT TOP 1 u.user_id
        FROM APP_USER u
        JOIN ROLE r ON r.role_id = u.role_id
        WHERE r.role_name = 'GROOM'
          AND u.status = 'ACTIVE'
        ORDER BY u.user_id
    )
)
FROM TRAINING_PLAN tp
WHERE tp.groom_id IS NULL;
GO

PRINT 'V40 done.';
GO
