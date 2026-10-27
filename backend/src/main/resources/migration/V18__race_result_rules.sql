/* =============================================================================
   V18 - Luật cho kết quả giải đua (Flow 5).

   RACE_REGISTRATION có từ V1, giữ cả đăng ký lẫn kết quả trong một dòng
   (result NULL = chưa đua). Cột result để chữ tự do, nên trước khi có chức năng
   nhập kết quả thì phải chốt tập giá trị:
     WON       - thắng giải
     PLACED    - về trong nhóm có giải
     UNPLACED  - không có giải
     SCRATCHED - rút khỏi giải trước ngày đua (ngựa ốm, bị khoá tập...)

   Tiền thưởng chỉ có nghĩa khi đã có kết quả.

   Dữ liệu demo của V3 chỉ dùng WON / PLACED / UNPLACED / NULL nên không dòng
   nào vi phạm. Chạy lại được.
   ============================================================================= */

IF OBJECT_ID('CK_REG_result', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_result
        CHECK (result IS NULL OR result IN ('WON', 'PLACED', 'UNPLACED', 'SCRATCHED'));
GO

IF OBJECT_ID('CK_REG_prize_result', 'C') IS NULL
    ALTER TABLE RACE_REGISTRATION ADD CONSTRAINT CK_REG_prize_result
        CHECK (prize_money IS NULL OR result IS NOT NULL);
GO

PRINT 'V18 hoan tat.';
GO
