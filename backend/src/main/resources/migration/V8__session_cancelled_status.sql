/* =============================================================================
   V8 - Thêm trạng thái CANCELLED cho buổi tập.

   VẤN ĐỀ
   Khi một giáo án chuyển sang COMPLETED, những buổi tập còn SCHEDULED của nó
   sẽ không bao giờ chạy được nữa (COMPLETED là điểm cuối trong TRANSITIONS, và
   TrainingSessionService.start giờ chặn buổi tập của giáo án không ACTIVE).
   Nhưng chúng vẫn nằm đó ở trạng thái SCHEDULED, nên lịch của Groom vẫn hiện
   việc đã chết và không ai hiểu vì sao bấm Start lại báo lỗi.

   VÌ SAO CẦN TRẠNG THÁI MỚI
   CK_SESSION_status của V1 chỉ có SCHEDULED, RUNNING, COMPLETED và
   STOPPED_EARLY. Không có giá trị nào diễn đạt được "đã lên lịch rồi bỏ, chưa
   từng chạy":
     - COMPLETED nói buổi tập đã hoàn thành, sai hẳn;
     - STOPPED_EARLY nói đã bắt đầu rồi dừng giữa buổi, cũng sai - những buổi
       này chưa từng bắt đầu, và gộp chúng vào STOPPED_EARLY sẽ làm sai mọi
       thống kê về số buổi phải dừng sớm.

   Nên thêm CANCELLED thay vì tận dụng một giá trị gần đúng.

   Dữ liệu cũ không đổi: không dòng nào được chuyển sang CANCELLED ở đây, chỉ
   mở rộng tập giá trị cho phép.
   ============================================================================= */

/* Phải XOÁ rồi tạo lại: SQL Server không cho sửa biểu thức của một CHECK
   constraint đang tồn tại. Hai câu nằm trong cùng một migration nên Flyway bọc
   chúng trong một giao dịch - không có khoảnh khắc nào bảng mất ràng buộc mà
   vẫn nhận ghi từ ứng dụng. */
IF OBJECT_ID('CK_SESSION_status', 'C') IS NOT NULL
    ALTER TABLE TRAINING_SESSION DROP CONSTRAINT CK_SESSION_status;
GO

ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_status
    CHECK (status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'STOPPED_EARLY', 'CANCELLED'));
GO

/* Buổi tập bị huỷ thì không có nhận xét: nhận xét chỉ có nghĩa cho buổi đã
   diễn ra. Không chặn thì một buổi CANCELLED vẫn nhận được evaluation và bảng
   thống kê chất lượng tập sẽ đếm cả những buổi chưa từng xảy ra. */
IF OBJECT_ID('CK_SESSION_cancelled_clean', 'C') IS NULL
    ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_cancelled_clean
        CHECK (status <> 'CANCELLED' OR (evaluation IS NULL AND evaluated_by IS NULL));
GO

/* Kiểm lại: ràng buộc mới phải nhận CANCELLED. Nếu ALTER ở trên lặng lẽ không
   chạy thì câu này bắt được ngay, thay vì để lỗi nổ ra lúc một giáo án đầu
   tiên chuyển sang COMPLETED - lúc đó rất khó lần ra nguyên nhân. */
IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_SESSION_status' AND [definition] LIKE '%CANCELLED%'
)
    THROW 50080, N'V8: CK_SESSION_status chua nhan gia tri CANCELLED.', 1;

PRINT 'V8 hoan tat.';
GO
