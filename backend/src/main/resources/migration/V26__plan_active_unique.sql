/* =============================================================================
   V26 - Mỗi ngựa chỉ có MỘT giáo án ACTIVE, chốt bằng database.

   VẤN ĐỀ
   TrainingPlanService.checkCanActivate kiểm existsActivePlan rồi mới UPDATE
   (check-then-act). Hai Head Trainer cùng kích hoạt hai giáo án của một ngựa
   đúng cùng lúc thì cả hai đều qua phép kiểm. Khoá tập đã có UX_LOCK_active từ
   V1; giáo án thì chưa.

   Trước khi tạo index: nếu một ngựa lỡ có nhiều giáo án ACTIVE (dữ liệu cũ),
   giữ giáo án mới nhất, chuyển các giáo án còn lại sang PAUSED. PAUSED giữ
   nguyên mọi buổi tập, Head Trainer xem lại và kết thúc sau. Không có bước này
   thì CREATE UNIQUE INDEX sẽ lỗi trên DB đang có dữ liệu trùng.

   Chạy lại được.
   ============================================================================= */

UPDATE p
SET p.status = 'PAUSED'
FROM TRAINING_PLAN p
WHERE p.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM TRAINING_PLAN newer
              WHERE newer.horse_id = p.horse_id AND newer.status = 'ACTIVE'
                AND newer.plan_id > p.plan_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_PLAN_active'
               AND object_id = OBJECT_ID('TRAINING_PLAN'))
    CREATE UNIQUE INDEX UX_PLAN_active ON TRAINING_PLAN(horse_id) WHERE status = 'ACTIVE';
GO

PRINT 'V26 hoan tat.';
GO
