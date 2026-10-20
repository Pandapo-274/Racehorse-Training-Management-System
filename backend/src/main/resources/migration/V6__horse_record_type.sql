/* =============================================================================
   V6 - Loại hồ sơ ngựa: ACADEMY (ngựa của học viện) hoặc PEDIGREE (chỉ để làm
   cha mẹ / tổ tiên).

   VẤN ĐỀ
   Danh sách ngựa trộn lẫn ngựa đang tập với ngựa chỉ có tên trong phả hệ (ví dụ
   con ngựa cái 33 tuổi). Nhìn vào thấy cái chip "Eligible" là tưởng ngựa đua.

   Cột này tách hai loại. Mọi hàng đang có mặc định là ACADEMY, nên không đổi
   hành vi nào của dữ liệu cũ. Phân loại theo tuổi (Foal / Racing / Retired) KHÔNG
   lưu ở đây: nó suy ra từ ngày sinh mỗi lần hiển thị, nên không bao giờ lỗi thời.
   ============================================================================= */

IF COL_LENGTH('HORSE', 'record_type') IS NULL
    ALTER TABLE HORSE ADD record_type VARCHAR(20) NOT NULL
        CONSTRAINT DF_HORSE_rtype DEFAULT 'ACADEMY';
GO

IF COL_LENGTH('HORSE_REQUEST', 'record_type') IS NULL
    ALTER TABLE HORSE_REQUEST ADD record_type VARCHAR(20) NOT NULL
        CONSTRAINT DF_HREQ_rtype DEFAULT 'ACADEMY';
GO

IF OBJECT_ID('CK_HORSE_rtype', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_rtype
        CHECK (record_type IN ('ACADEMY', 'PEDIGREE'));
GO

IF OBJECT_ID('CK_HREQ_rtype', 'C') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_rtype
        CHECK (record_type IN ('ACADEMY', 'PEDIGREE'));
GO

/* Hồ sơ PEDIGREE không có chuồng và cân nặng. */
IF OBJECT_ID('CK_HORSE_pedigree', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_pedigree
        CHECK (record_type = 'ACADEMY' OR (stall_no IS NULL AND weight IS NULL));
GO

/* ---------------------------------------------------------------------------
   TÙY CHỌN - chạy tay nếu muốn: chuyển những con quá già (trên 25 tuổi) đang
   nằm trong học viện thành hồ sơ PEDIGREE. Chạy sẽ GỠ chuồng và cân nặng của
   chúng, nên xem kết quả SELECT trước khi bỏ comment UPDATE.

   SELECT horse_id, horse_name, date_of_birth, stall_no
   FROM HORSE WHERE DATEDIFF(YEAR, date_of_birth, GETDATE()) > 25;

   UPDATE HORSE SET record_type = 'PEDIGREE', stall_no = NULL, weight = NULL
   WHERE DATEDIFF(YEAR, date_of_birth, GETDATE()) > 25;
   --------------------------------------------------------------------------- */
PRINT 'V6 hoan tat.';
GO