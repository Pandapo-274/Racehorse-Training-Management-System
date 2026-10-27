/* =============================================================================
   V16 (trước đây đặt là V14, đổi số vì trùng V14 trên main) - Hai thứ còn thiếu cho "dừng buổi tập vì ngựa bất thường" và "video chạy thử".

   1) INCIDENT_REPORT
      - session_id : báo cáo này sinh ra từ buổi tập nào (NULL = báo cáo không gắn buổi tập,
                     như dữ liệu demo cũ). Có cột này thì bác sĩ mới biết con ngựa bị dừng giữa buổi.
      - received_by / received_at : bác sĩ nào đã nhận ca (PENDING -> RECEIVED).
      - Mỗi buổi tập chỉ sinh tối đa MỘT báo cáo (UX_INC_session).
      - IX_INC_open : tra "ngựa này còn báo cáo chưa khám không" thật nhanh, vì câu hỏi này được
                      hỏi mỗi lần xếp lịch / bấm Start một buổi tập.

   2) TRAINING_SESSION
      - is_trial_run : buổi này là lượt chạy thử.
      - video_url    : link video ghi hình lượt chạy thử (chỉ lưu đường dẫn, không lưu file).
      - CK_SESSION_video_trial : chỉ lượt chạy thử mới có video. Ràng buộc đặt ở DB để sửa tay
                      bằng SQL cũng không tạo ra video "mồ côi".

   Mọi lệnh đều kiểm tra "đã có chưa" nên chạy lại không lỗi. Dòng cũ không đổi:
   báo cáo cũ có session_id NULL, buổi tập cũ is_trial_run = 0 và không có video.
   ============================================================================= */

IF COL_LENGTH('INCIDENT_REPORT', 'session_id') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD session_id INT NULL;
IF COL_LENGTH('INCIDENT_REPORT', 'received_by') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD received_by INT NULL;
IF COL_LENGTH('INCIDENT_REPORT', 'received_at') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD received_at DATETIME2 NULL;
GO

IF OBJECT_ID('FK_INC_session', 'F') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT FK_INC_session
        FOREIGN KEY (session_id) REFERENCES TRAINING_SESSION(session_id);
IF OBJECT_ID('FK_INC_receiver', 'F') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT FK_INC_receiver
        FOREIGN KEY (received_by) REFERENCES APP_USER(user_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_INC_session' AND object_id = OBJECT_ID('INCIDENT_REPORT'))
    CREATE UNIQUE INDEX UX_INC_session ON INCIDENT_REPORT(session_id) WHERE session_id IS NOT NULL;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_INC_open' AND object_id = OBJECT_ID('INCIDENT_REPORT'))
    CREATE INDEX IX_INC_open ON INCIDENT_REPORT(horse_id) WHERE status <> 'EXAMINED';
GO

IF COL_LENGTH('TRAINING_SESSION', 'is_trial_run') IS NULL
    ALTER TABLE TRAINING_SESSION ADD is_trial_run BIT NOT NULL
        CONSTRAINT DF_SESSION_trial DEFAULT 0;
IF COL_LENGTH('TRAINING_SESSION', 'video_url') IS NULL
    ALTER TABLE TRAINING_SESSION ADD video_url VARCHAR(500) NULL;
GO

IF OBJECT_ID('CK_SESSION_video_trial', 'C') IS NULL
    ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_video_trial
        CHECK (video_url IS NULL OR is_trial_run = 1);
GO

/* Kiểm lại: thiếu cột nào thì nổ ngay ở đây thay vì để đến lúc API chạy mới lỗi. */
IF COL_LENGTH('INCIDENT_REPORT', 'session_id') IS NULL
   OR COL_LENGTH('INCIDENT_REPORT', 'received_by') IS NULL
   OR COL_LENGTH('TRAINING_SESSION', 'is_trial_run') IS NULL
   OR COL_LENGTH('TRAINING_SESSION', 'video_url') IS NULL
    THROW 50160, N'V16: thieu cot moi tren INCIDENT_REPORT hoac TRAINING_SESSION.', 1;

PRINT 'V16 hoan tat.';
GO
