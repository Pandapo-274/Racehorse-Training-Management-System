# Kiểm thử end-to-end: 3 flow bắt buộc

`flows_1_3_e2e.py` chạy trọn Flow 1 (hồ sơ ngựa), Flow 2 (giáo án, buổi tập, cảnh báo), Flow 3 (y tế, chấn thương), gọi bác sĩ từ cảnh báo (V27), phần rút ngựa và tài khoản, qua HTTP API như người dùng thật. Script có khoảng 130 bước, gồm cả luồng đúng lẫn các ca ngoại lệ hệ thống phải chặn (trùng lịch, khoá tập, hồi phục sớm, phả hệ sai...).

Script **ghi dữ liệu thật** và dời ngày bằng SQL, nên chỉ chạy trên một database thử nghiệm riêng. Script tự từ chối nếu tên database không chứa `e2e` hoặc `test`.

## Cần có

- SQL Server với tài khoản Windows có quyền sysadmin (để tạo DB thử và dời ngày bằng `sqlcmd -E`).
- Python 3.10 trở lên (chỉ dùng thư viện chuẩn).
- `sqlcmd` trong PATH.

## Cách chạy

Chạy từ thư mục `backend`.

1. Tạo database thử và cấp quyền cho tài khoản ứng dụng (thay `rtms_app` bằng `DB_USERNAME` trong `.env`):

   ```
   sqlcmd -S localhost,1433 -E -C -Q "CREATE DATABASE rtms_e2e"
   sqlcmd -S localhost,1433 -E -C -d rtms_e2e -Q "CREATE USER [rtms_app] FOR LOGIN [rtms_app]; ALTER ROLE db_owner ADD MEMBER [rtms_app]"
   ```

2. Chạy một backend thứ hai trỏ vào DB thử, ở cổng 8081. Flyway tự dựng schema và dữ liệu mẫu từ toàn bộ migration:

   ```
   .\mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081 --spring.datasource.url=jdbc:sqlserver://localhost:1433;databaseName=rtms_e2e;encrypt=true;trustServerCertificate=true"
   ```

3. Chạy kịch bản:

   ```
   python dev\e2e\flows_1_3_e2e.py --base http://localhost:8081 --db rtms_e2e
   ```

   Cuối kết quả có dòng `TOTAL x/y passed`, kèm danh sách bước lỗi nếu có. Mã thoát là 0 khi mọi bước đều đạt. Có thể chạy lại nhiều lần trên cùng DB, vì mỗi lần dùng tên ngựa, mã và chuồng khác nhau.

4. Xong thì tắt backend thử và xoá DB:

   ```
   sqlcmd -S localhost,1433 -E -C -Q "ALTER DATABASE rtms_e2e SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE rtms_e2e"
   ```

## Tài khoản dùng trong kịch bản

Lấy từ dữ liệu mẫu (V2, V3), mật khẩu `123456`: `hoa.tm` (Club Manager), `thang.nd` (Head Trainer), `ha.pt` (bác sĩ), `binh.lv` (Groom), `anh.dq` và `son.dv` (chủ ngựa).

## Unit test

Các luật chính còn có unit test chạy không cần database: `src/test/java/racehorse/backend/BusinessRulesTest.java`. Chạy bằng `.\mvnw test -Dtest=BusinessRulesTest`.
