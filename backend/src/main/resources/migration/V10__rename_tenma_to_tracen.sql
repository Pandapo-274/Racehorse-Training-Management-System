/* =============================================================================
   V10 - Đổi tên câu lạc bộ từ Tenma thành Tracen trong dữ liệu.

   VÌ SAO LÀ MIGRATION MỚI
   Chữ Tenma nằm trong dữ liệu mẫu của V2, V3 và V7. Ba file đó đã chạy trên
   database của cả nhóm, sửa thẳng vào chúng thì Flyway báo lệch checksum và
   backend không khởi động được. Nên đổi bằng UPDATE ở đây.

   PHẠM VI
     - APP_USER.email      : đuôi @tenma.vn  -> @tracen.vn
     - APP_USER.full_name  : Tenma Bloodstock -> Tracen Bloodstock
     - TRAINING_PLAN.plan_name, RACE_REGISTRATION.race_name: Tenma Cup ...
   Không đổi mã đăng ký TM-xxxx và username bloodstock.tm: đó là mã định danh,
   V7 còn lọc theo tiền tố TM-A%.

   COLLATE Latin1_General_CS_AS: collation mặc định không phân biệt hoa thường,
   REPLACE sẽ khớp cả 'TENMA' lẫn 'tenma' rồi ghi đè thành 'Tracen'.
   Chạy lại nhiều lần không sao: lần sau không còn dòng nào khớp.
   ============================================================================= */

UPDATE APP_USER
SET email = REPLACE(email COLLATE Latin1_General_CS_AS, '@tenma.vn', '@tracen.vn')
WHERE email COLLATE Latin1_General_CS_AS LIKE '%@tenma.vn';

UPDATE APP_USER
SET full_name = REPLACE(full_name COLLATE Latin1_General_CS_AS, N'Tenma', N'Tracen')
WHERE full_name COLLATE Latin1_General_CS_AS LIKE N'%Tenma%';

UPDATE TRAINING_PLAN
SET plan_name = REPLACE(plan_name COLLATE Latin1_General_CS_AS, N'Tenma', N'Tracen')
WHERE plan_name COLLATE Latin1_General_CS_AS LIKE N'%Tenma%';

UPDATE RACE_REGISTRATION
SET race_name = REPLACE(race_name COLLATE Latin1_General_CS_AS, N'Tenma', N'Tracen')
WHERE race_name COLLATE Latin1_General_CS_AS LIKE N'%Tenma%';
GO
