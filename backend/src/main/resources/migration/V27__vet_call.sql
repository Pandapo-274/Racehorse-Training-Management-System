/* =============================================================================
   V27 - Gọi bác sĩ từ cảnh báo buổi tập (VET_CALL).

   VẤN ĐỀ
   Nút "Call vet" trên màn Live session chỉ ghi action_taken = VET_CALLED vào
   THRESHOLD_ALERT rồi báo chuông cho bác sĩ. Bác sĩ không biết HLV thấy gì,
   bấm chuông cũng không mở được ca nào, và không có chỗ trả lời HLV phải làm gì.

   GIẢI PHÁP
   Mỗi lần gọi là một dòng VET_CALL gắn với đúng cảnh báo đó:
     - HLV ghi lý do, dấu hiệu quan sát, mức khẩn cấp, có dừng buổi tập ngay không.
     - Bác sĩ trả lời một lần (PENDING -> ANSWERED) với quyết định:
         CONTINUE_LIGHT  tiếp tục ở cường độ nhẹ
         TREAT_ON_SITE   dừng buổi tập, chữa tại chuồng (bắt buộc ghi thuốc)
         EMERGENCY_STOP  dừng khẩn cấp, đưa ngựa đi khám: mở báo cáo sự cố
                         (report_id) nên ngựa vào hàng chờ khám và bị chặn tập
                         như mọi báo cáo sự cố khác.
   alert_id UNIQUE: một cảnh báo chỉ gọi bác sĩ một lần.
   Thông báo trỏ tới VET_CALL nên CK_NOTIF_target phải thêm giá trị này.

   Chạy lại được.
   ============================================================================= */

IF OBJECT_ID('VET_CALL', 'U') IS NULL
CREATE TABLE VET_CALL (
    call_id         INT IDENTITY(1,1) PRIMARY KEY,
    alert_id        INT            NOT NULL,
    session_id      INT            NOT NULL,
    horse_id        INT            NOT NULL,
    called_by       INT            NOT NULL,
    reason          NVARCHAR(500)  NOT NULL,
    observations    NVARCHAR(1000) NULL,
    urgency         VARCHAR(20)    NOT NULL,
    session_stopped BIT            NOT NULL CONSTRAINT DF_VETCALL_stopped DEFAULT 0,
    called_at       DATETIME2      NOT NULL CONSTRAINT DF_VETCALL_time DEFAULT SYSUTCDATETIME(),
    status          VARCHAR(20)    NOT NULL CONSTRAINT DF_VETCALL_status DEFAULT 'PENDING',
    answered_by     INT            NULL,
    answered_at     DATETIME2      NULL,
    decision        VARCHAR(30)    NULL,
    instructions    NVARCHAR(1000) NULL,
    medication      NVARCHAR(500)  NULL,
    report_id       INT            NULL,
    CONSTRAINT UQ_VETCALL_alert    UNIQUE (alert_id),
    CONSTRAINT FK_VETCALL_alert    FOREIGN KEY (alert_id)    REFERENCES THRESHOLD_ALERT(alert_id),
    CONSTRAINT FK_VETCALL_session  FOREIGN KEY (session_id)  REFERENCES TRAINING_SESSION(session_id),
    CONSTRAINT FK_VETCALL_horse    FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_VETCALL_caller   FOREIGN KEY (called_by)   REFERENCES APP_USER(user_id),
    CONSTRAINT FK_VETCALL_vet      FOREIGN KEY (answered_by) REFERENCES APP_USER(user_id),
    CONSTRAINT FK_VETCALL_report   FOREIGN KEY (report_id)   REFERENCES INCIDENT_REPORT(report_id),
    CONSTRAINT CK_VETCALL_urgency  CHECK (urgency IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT CK_VETCALL_status   CHECK (status IN ('PENDING','ANSWERED')),
    CONSTRAINT CK_VETCALL_decision CHECK (decision IS NULL OR decision IN ('CONTINUE_LIGHT','TREAT_ON_SITE','EMERGENCY_STOP')),
    /* Đã trả lời thì phải có đủ người trả lời, quyết định và hướng dẫn. */
    CONSTRAINT CK_VETCALL_answer   CHECK (status = 'PENDING'
        OR (answered_by IS NOT NULL AND answered_at IS NOT NULL AND decision IS NOT NULL AND instructions IS NOT NULL))
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_VETCALL_status' AND object_id = OBJECT_ID('VET_CALL'))
    CREATE INDEX IX_VETCALL_status ON VET_CALL(status, called_at);
GO

IF OBJECT_ID('CK_NOTIF_target', 'C') IS NOT NULL
    ALTER TABLE NOTIFICATION DROP CONSTRAINT CK_NOTIF_target;
GO

ALTER TABLE NOTIFICATION ADD CONSTRAINT CK_NOTIF_target CHECK (target_entity IS NULL OR target_entity IN
    ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT', 'HORSE_WITHDRAWAL', 'VET_CALL'));
GO

PRINT 'V27 hoan tat.';
GO
