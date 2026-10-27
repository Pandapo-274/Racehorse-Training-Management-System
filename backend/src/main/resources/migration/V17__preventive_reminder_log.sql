/* =============================================================================
   V17 - Sổ ghi nhớ "đã nhắc rồi" cho tác vụ nhắc lịch phòng ngừa hằng ngày.

   VẤN ĐỀ
   Tác vụ chạy mỗi sáng quét bảng UC17 rồi gửi thông báo cho bác sĩ thú y. Nếu
   nó không nhớ gì thì sáng nào bác sĩ cũng nhận lại đúng những tin hôm qua, và
   cái chuông học được cách bị phớt lờ - đúng như MedicalRecordService đã tránh
   khi chỉ báo lúc tình trạng sức khoẻ thật sự đổi.

   MỘT DÒNG = "ô này đã được nhắc ở giai đoạn này, vào ngày này"
     horse_id + care_type : ô trong bảng nhắc lịch (một ngựa x một loại).
     record_ref           : record_id của lần làm GẦN NHẤT lúc nhắc; 0 nếu ô chưa
                            từng được làm (NEVER). KHÔNG có khoá ngoại, vì 0 không
                            phải một record. Khi bác sĩ ghi một lần tiêm mới thì
                            record_ref của ô đó đổi, chu kỳ mới bắt đầu và nhắc
                            được lại - không cần xoá gì.
     stage                : DUE_SOON | OVERDUE | NEVER. Một ô đi từ DUE_SOON sang
                            OVERDUE là tin mới thật sự nên được nhắc thêm lần nữa.
     sent_on              : ngày nhắc gần nhất. OVERDUE và NEVER được nhắc lại sau
                            một tuần nếu vẫn chưa ai làm; DUE_SOON chỉ nhắc một lần.

   Khoá duy nhất (horse_id, care_type, record_ref, stage) là hàng rào cuối: hai
   lần chạy đồng thời (tác vụ định kỳ trùng với nút chạy tay) không thể cùng ghi
   một ô, lần chạy thua sẽ bị huỷ cả giao dịch kể cả thông báo của nó.
   ============================================================================= */

IF OBJECT_ID('PREVENTIVE_REMINDER_LOG', 'U') IS NULL
BEGIN
    CREATE TABLE PREVENTIVE_REMINDER_LOG (
        log_id     INT IDENTITY(1,1) PRIMARY KEY,
        horse_id   INT         NOT NULL,
        care_type  VARCHAR(20) NOT NULL,
        record_ref INT         NOT NULL CONSTRAINT DF_PRL_ref DEFAULT 0,
        stage      VARCHAR(20) NOT NULL,
        sent_on    DATE        NOT NULL,

        CONSTRAINT FK_PRL_horse FOREIGN KEY (horse_id) REFERENCES HORSE(horse_id),
        CONSTRAINT CK_PRL_type  CHECK (care_type IN ('VACCINATION', 'DEWORMING', 'FARRIER')),
        CONSTRAINT CK_PRL_stage CHECK (stage IN ('DUE_SOON', 'OVERDUE', 'NEVER'))
    );

    CREATE UNIQUE INDEX UX_PRL_cell
        ON PREVENTIVE_REMINDER_LOG(horse_id, care_type, record_ref, stage);

    /* Dọn các dòng cũ theo ngày nhắc. */
    CREATE INDEX IX_PRL_sent ON PREVENTIVE_REMINDER_LOG(sent_on);

    PRINT 'Da tao bang PREVENTIVE_REMINDER_LOG.';
END
ELSE
BEGIN
    PRINT 'Bang PREVENTIVE_REMINDER_LOG da co san, bo qua.';
END
GO

IF OBJECT_ID('PREVENTIVE_REMINDER_LOG', 'U') IS NULL
    THROW 50014, 'Khong tao duoc bang PREVENTIVE_REMINDER_LOG.', 1;
GO

PRINT 'V17 hoan tat.';
GO
