/* =============================================================================
   V15 - Rút ngựa khỏi học viện, và chủ ngựa huỷ yêu cầu đăng ký đang chờ.

   VẤN ĐỀ
   Ngựa vào hệ thống qua yêu cầu đăng ký (V5) nhưng không có đường ra. Chủ ngựa
   muốn đưa ngựa về thì học viện chỉ còn cách xoá hồ sơ - mà xoá thì mất luôn
   lịch sử khám, tập, chấn thương của con ngựa đó.

   HAI THỨ ĐƯỢC THÊM
   1. HORSE.activity_status: ACTIVE (đang ở học viện) hoặc WITHDRAWN (đã rút).
      KHÔNG gộp vào HORSE.status: cột đó là tình trạng SỨC KHOẺ do bác sĩ thú y
      đặt (ELIGIBLE, MONITOR...). Một con ngựa đã rút vẫn có thể đang bị
      thương, và bác sĩ đổi sức khoẻ không được vô tình "đưa ngựa trở lại".
   2. HORSE_WITHDRAWAL: chủ ngựa gửi yêu cầu rút, Club Manager duyệt hoặc từ
      chối. Giống HORSE_REQUEST: lời đề nghị tách khỏi hồ sơ chính thức.

   Khi duyệt (HorseWithdrawalService.approve), trong cùng một giao dịch:
     - huỷ các buổi tập còn SCHEDULED của con ngựa, báo cho Groom phụ trách;
     - kết thúc mọi giáo án chưa COMPLETED;
     - trả chuồng (stall_no = NULL), số chuồng cũ lưu lại ở released_stall;
     - activity_status = 'WITHDRAWN'.

   Cùng bản này: HORSE_REQUEST (V5) thêm trạng thái CANCELLED, cho chủ ngựa tự
   huỷ yêu cầu đăng ký còn chờ duyệt (xem cuối file).

   Chạy lại được.
   ============================================================================= */

IF COL_LENGTH('HORSE', 'activity_status') IS NULL
    ALTER TABLE HORSE ADD activity_status VARCHAR(20) NOT NULL
        CONSTRAINT DF_HORSE_activity DEFAULT 'ACTIVE';
GO

IF COL_LENGTH('HORSE', 'withdrawn_at') IS NULL
    ALTER TABLE HORSE ADD withdrawn_at DATETIME2 NULL;
GO

IF OBJECT_ID('CK_HORSE_activity', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_activity
        CHECK (activity_status IN ('ACTIVE', 'WITHDRAWN'));
GO

/* Đã rút thì phải biết rút lúc nào. */
IF OBJECT_ID('CK_HORSE_withdrawn_at', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_withdrawn_at
        CHECK (activity_status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL);
GO

IF OBJECT_ID('HORSE_WITHDRAWAL', 'U') IS NULL
BEGIN
    CREATE TABLE HORSE_WITHDRAWAL (
        withdrawal_id      INT IDENTITY(1,1) PRIMARY KEY,
        horse_id           INT           NOT NULL,
        requested_by       INT           NOT NULL,
        reason             NVARCHAR(500) NOT NULL,

        status             VARCHAR(20)   NOT NULL
            CONSTRAINT DF_HWD_status DEFAULT 'PENDING',
        created_at         DATETIME2     NOT NULL
            CONSTRAINT DF_HWD_created DEFAULT SYSUTCDATETIME(),

        /* Người xử lý: Club Manager khi duyệt/từ chối, chính chủ ngựa khi tự huỷ. */
        reviewed_by        INT           NULL,
        reviewed_at        DATETIME2     NULL,
        review_note        NVARCHAR(255) NULL,

        /* Ghi lại những gì việc duyệt đã làm, để sau này nhìn yêu cầu là biết
           con ngựa từng ở chuồng nào và bao nhiêu việc bị huỷ theo. */
        released_stall     VARCHAR(20)   NULL,
        cancelled_sessions INT           NULL,
        ended_plans        INT           NULL,

        CONSTRAINT FK_HWD_horse     FOREIGN KEY (horse_id)     REFERENCES HORSE(horse_id),
        CONSTRAINT FK_HWD_requester FOREIGN KEY (requested_by) REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HWD_reviewer  FOREIGN KEY (reviewed_by)  REFERENCES APP_USER(user_id),

        CONSTRAINT CK_HWD_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
        CONSTRAINT CK_HWD_reviewed CHECK (
            status = 'PENDING'
            OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        )
    );

    /* Một con ngựa chỉ có một yêu cầu rút đang chờ. Bấm hai lần thì lần sau
       bị chặn ở đây, kể cả khi hai lần gần như đồng thời. */
    CREATE UNIQUE INDEX UX_HWD_open ON HORSE_WITHDRAWAL(horse_id)
        WHERE status = 'PENDING';

    CREATE INDEX IX_HWD_requester ON HORSE_WITHDRAWAL(requested_by, created_at DESC);

    PRINT 'Da tao bang HORSE_WITHDRAWAL.';
END
ELSE
BEGIN
    PRINT 'Bang HORSE_WITHDRAWAL da co san, bo qua.';
END
GO

IF OBJECT_ID('HORSE_WITHDRAWAL', 'U') IS NULL
    THROW 50015, 'Khong tao duoc bang HORSE_WITHDRAWAL.', 1;
GO

/* Thông báo trỏ được tới yêu cầu rút ngựa (hàng chờ của Club Manager).
   Phải XOÁ rồi tạo lại: SQL Server không cho sửa biểu thức của CHECK. */
IF OBJECT_ID('CK_NOTIF_target', 'C') IS NOT NULL
    ALTER TABLE NOTIFICATION DROP CONSTRAINT CK_NOTIF_target;
GO

ALTER TABLE NOTIFICATION ADD CONSTRAINT CK_NOTIF_target CHECK (target_entity IS NULL OR target_entity IN
    ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT', 'HORSE_WITHDRAWAL'));
GO

/* -----------------------------------------------------------------------------
   Huỷ yêu cầu đăng ký ngựa.

   HORSE_REQUEST chỉ có PENDING, APPROVED, REJECTED. Chủ ngựa gửi nhầm hoặc đổi
   ý thì không rút lại được: yêu cầu nằm trong hàng chờ của Club Manager tới khi
   bị từ chối, và UX_HREQ_open còn chặn gửi lại cùng tên trong lúc đó.

   Thêm CANCELLED. Người "xử lý" (reviewed_by) là chính chủ ngựa, nên
   CK_HREQ_reviewed giữ nguyên. CK_HREQ_horse cũng không đổi: chỉ APPROVED mới
   có horse_id. UX_HREQ_open lọc theo status = 'PENDING' nên huỷ xong là gửi
   lại được ngay.
   ----------------------------------------------------------------------------- */

IF OBJECT_ID('CK_HREQ_status', 'C') IS NOT NULL
    ALTER TABLE HORSE_REQUEST DROP CONSTRAINT CK_HREQ_status;
GO

ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_HREQ_status' AND [definition] LIKE '%CANCELLED%'
)
    THROW 50151, N'V15: CK_HREQ_status chua nhan gia tri CANCELLED.', 1;
GO

PRINT 'V15 hoan tat.';
GO
