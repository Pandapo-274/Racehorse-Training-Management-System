/* =============================================================================
   V36 - Lệ phí đăng ký giải thành chi phí của chủ ngựa.

   VẤN ĐỀ
   RACE.entry_fee có từ V22 và được nêu trong thông báo xin chủ ngựa duyệt,
   nhưng duyệt xong thì khoản phí không đi đâu cả: chủ ngựa không thấy nó
   trong chi phí của ngựa mình, không khiếu nại được (V30), và rút ngựa khỏi
   giải cũng không có gì để hoàn.

   GIẢI PHÁP
   Chủ ngựa duyệt -> RaceService ghi một dòng EXPENSE_LOG (hạng mục OTHER,
   "Race entry fee: ...") và lưu id của nó ở RACE_REGISTRATION.fee_expense_id,
   để khi rút ngựa biết đúng dòng nào cần huỷ (V19, huỷ có lý do chứ không xoá):
     - rút trước hạn đăng ký, hoặc CLB huỷ cả giải: huỷ khoản phí (hoàn tiền);
     - rút sau hạn đăng ký: giữ khoản phí (mất phí, như lệ thường của giải đua).
   Không suy ra từ nội dung ghi chú: ghi chú là chữ, sửa tay là mất liên kết.

   Chạy lại được.
   ============================================================================= */

IF COL_LENGTH('RACE_REGISTRATION', 'fee_expense_id') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD fee_expense_id INT NULL;
GO

IF OBJECT_ID('FK_REG_fee_expense', 'F') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT FK_REG_fee_expense
        FOREIGN KEY (fee_expense_id) REFERENCES EXPENSE_LOG(expense_id);
GO

PRINT 'V36 done.';
GO
