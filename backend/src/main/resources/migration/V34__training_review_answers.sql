/* =============================================================================
   V34 - Bốn chỗ hội đồng phản biện về flow huấn luyện.

   1. PLAN_PHASE.min_gap_minutes (câu hỏi 2)
      Khoảng nghỉ tối thiểu giữa hai buổi của cùng một ngựa, theo từng giai
      đoạn. NULL = mặc định 90 phút như trước. Giai đoạn tập nhẹ (đi bộ, khởi
      động) đặt ngắn hơn được, giai đoạn nặng đặt dài hơn.

   2. PLAN_PHASE.target_velocity (câu hỏi 4)
      Tốc độ mục tiêu (m/s) của giai đoạn. Có thì điểm Speed so vận tốc đỉnh
      với mục tiêu (đạt mục tiêu = 100); NULL thì vẫn so với 18 m/s như trước.

   3. INCIDENT_REPORT: trạng thái DISMISSED + dismissed_by/at/reason (câu hỏi 3)
      Head Trainer huỷ được báo động giả của một lần dừng buổi tập, khi bác sĩ
      chưa nhận ca (PENDING). Bắt buộc ghi lý do; báo cáo vẫn nằm trong lịch sử.

   4. TRAINING_PLAN: PENDING_APPROVAL, APPROVED + owner_note/owner_reviewed_at
      (câu hỏi 5). Giáo án phải được chủ ngựa duyệt trước khi kích hoạt:
        DRAFT -> PENDING_APPROVAL -> APPROVED -> ACTIVE
      Chủ ngựa từ chối thì về lại DRAFT kèm owner_note.
      Thông báo trỏ tới giáo án nên CK_NOTIF_target thêm TRAINING_PLAN.

   Chạy lại được.
   ============================================================================= */

/* ---------- 1 + 2. PLAN_PHASE ---------- */

IF COL_LENGTH('PLAN_PHASE', 'min_gap_minutes') IS NULL
    ALTER TABLE PLAN_PHASE ADD min_gap_minutes INT NULL;
IF COL_LENGTH('PLAN_PHASE', 'target_velocity') IS NULL
    ALTER TABLE PLAN_PHASE ADD target_velocity DECIMAL(5,2) NULL;
GO

IF OBJECT_ID('CK_PHASE_gap', 'C') IS NULL
    ALTER TABLE PLAN_PHASE ADD CONSTRAINT CK_PHASE_gap
        CHECK (min_gap_minutes IS NULL OR min_gap_minutes BETWEEN 15 AND 720);
IF OBJECT_ID('CK_PHASE_target_velocity', 'C') IS NULL
    ALTER TABLE PLAN_PHASE ADD CONSTRAINT CK_PHASE_target_velocity
        CHECK (target_velocity IS NULL OR target_velocity BETWEEN 1 AND 25);
GO

/* ---------- 3. INCIDENT_REPORT ---------- */

IF COL_LENGTH('INCIDENT_REPORT', 'dismissed_by') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD dismissed_by INT NULL;
IF COL_LENGTH('INCIDENT_REPORT', 'dismissed_at') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD dismissed_at DATETIME2 NULL;
IF COL_LENGTH('INCIDENT_REPORT', 'dismiss_reason') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD dismiss_reason NVARCHAR(500) NULL;
GO

IF OBJECT_ID('FK_INC_dismisser', 'F') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT FK_INC_dismisser
        FOREIGN KEY (dismissed_by) REFERENCES APP_USER(user_id);
GO

IF OBJECT_ID('CK_INC_status', 'C') IS NOT NULL
    ALTER TABLE INCIDENT_REPORT DROP CONSTRAINT CK_INC_status;
GO

ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT CK_INC_status
    CHECK (status IN ('PENDING', 'RECEIVED', 'EXAMINED', 'DISMISSED'));
GO

/* Huỷ thì phải có người huỷ, lúc huỷ và lý do; chưa huỷ thì không có. */
IF OBJECT_ID('CK_INC_dismissed', 'C') IS NULL
    ALTER TABLE INCIDENT_REPORT ADD CONSTRAINT CK_INC_dismissed CHECK (
        (status = 'DISMISSED' AND dismissed_by IS NOT NULL AND dismissed_at IS NOT NULL AND dismiss_reason IS NOT NULL)
        OR (status <> 'DISMISSED' AND dismissed_by IS NULL AND dismissed_at IS NULL AND dismiss_reason IS NULL));
GO

/* ---------- 4. TRAINING_PLAN ---------- */

IF COL_LENGTH('TRAINING_PLAN', 'owner_note') IS NULL
    ALTER TABLE TRAINING_PLAN ADD owner_note NVARCHAR(500) NULL;
IF COL_LENGTH('TRAINING_PLAN', 'owner_reviewed_at') IS NULL
    ALTER TABLE TRAINING_PLAN ADD owner_reviewed_at DATETIME2 NULL;
GO

IF OBJECT_ID('CK_PLAN_status', 'C') IS NOT NULL
    ALTER TABLE TRAINING_PLAN DROP CONSTRAINT CK_PLAN_status;
GO

ALTER TABLE TRAINING_PLAN ADD CONSTRAINT CK_PLAN_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'PAUSED', 'COMPLETED'));
GO

IF OBJECT_ID('CK_NOTIF_target', 'C') IS NOT NULL
    ALTER TABLE NOTIFICATION DROP CONSTRAINT CK_NOTIF_target;
GO

ALTER TABLE NOTIFICATION ADD CONSTRAINT CK_NOTIF_target CHECK (target_entity IS NULL OR target_entity IN
    ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT', 'HORSE_WITHDRAWAL', 'VET_CALL',
     'TRAINING_PLAN'));
GO

PRINT 'V34 done.';
GO
