/* =============================================================================
   V37 - Nâng cấp và thắt chặt nghiệp vụ luồng huấn luyện ngựa (Training Flow):

   1. Quản lý Groom theo Head Trainer (tối đa 3 Groom / 1 Head Trainer):
      - APP_USER.trainer_id: liên kết GROOM với HEAD_TRAINER phụ trách, do
        CLUB_MANAGER phê duyệt và điều phối.

   2. Điều phối ngựa cho Head Trainer (Club Manager quyết định):
      - APP_USER.max_horses: hạn mức số ngựa tối đa mà mỗi HEAD_TRAINER được
        quản lý (mặc định 10).
      - HORSE.trainer_id: HEAD_TRAINER được giao phụ trách huấn luyện ngựa.

   3. Thời lượng buổi tập (planned_duration, tối đa 12 tiếng = 720 phút, không
      qua ngày hôm sau), tự động kết thúc khi quá giờ và kết thúc sớm bởi Groom:
      - TRAINING_SESSION.planned_duration (15..720 phút, mặc định 60).
      - TRAINING_SESSION.started_at, ended_at.
      - TRAINING_SESSION.early_stop_reason: lý do kết thúc sớm do Groom chọn.

   4. Thời hạn review 2 ngày (48 giờ), báo cáo sơ bộ tự động & khu lưu trữ:
      - TRAINING_SESSION.review_status:
          'NONE'          - buổi tập chưa kết thúc (SCHEDULED / RUNNING / CANCELLED)
          'PENDING'       - đã kết thúc, đang chờ Head Trainer review (trong hạn 48h)
          'ARCHIVED_AUTO' - quá hạn 48h chưa review, hệ thống đã tự gửi báo cáo
                            sơ bộ cho chủ ngựa và chuyển vào khu lưu trữ chờ
                            Head Trainer xem xét lại
          'REVIEWED'      - Head Trainer đã gửi review chính thức
      - TRAINING_SESSION.auto_reviewed_at: thời điểm hệ thống tạo báo cáo tự động.

   Chạy lại được.
   ============================================================================= */

/* ---------- 1. APP_USER: trainer_id & max_horses ---------- */

IF COL_LENGTH('APP_USER', 'trainer_id') IS NULL
    ALTER TABLE APP_USER ADD trainer_id INT NULL;
IF COL_LENGTH('APP_USER', 'max_horses') IS NULL
    ALTER TABLE APP_USER ADD max_horses INT NULL CONSTRAINT DF_USER_max_horses DEFAULT 10;
GO

IF OBJECT_ID('FK_USER_trainer', 'F') IS NULL
    ALTER TABLE APP_USER ADD CONSTRAINT FK_USER_trainer
        FOREIGN KEY (trainer_id) REFERENCES APP_USER(user_id);
IF OBJECT_ID('CK_USER_max_horses', 'C') IS NULL
    ALTER TABLE APP_USER ADD CONSTRAINT CK_USER_max_horses
        CHECK (max_horses IS NULL OR max_horses BETWEEN 1 AND 100);
GO

/* ---------- 2. HORSE: trainer_id ---------- */

IF COL_LENGTH('HORSE', 'trainer_id') IS NULL
    ALTER TABLE HORSE ADD trainer_id INT NULL;
GO

IF OBJECT_ID('FK_HORSE_trainer', 'F') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT FK_HORSE_trainer
        FOREIGN KEY (trainer_id) REFERENCES APP_USER(user_id);
GO

/* ---------- 3. TRAINING_SESSION: duration, timestamps, early stop & review archive ---------- */

IF COL_LENGTH('TRAINING_SESSION', 'planned_duration') IS NULL
    ALTER TABLE TRAINING_SESSION ADD planned_duration INT NOT NULL CONSTRAINT DF_SESSION_planned_duration DEFAULT 60;
IF COL_LENGTH('TRAINING_SESSION', 'started_at') IS NULL
    ALTER TABLE TRAINING_SESSION ADD started_at DATETIME2 NULL;
IF COL_LENGTH('TRAINING_SESSION', 'ended_at') IS NULL
    ALTER TABLE TRAINING_SESSION ADD ended_at DATETIME2 NULL;
IF COL_LENGTH('TRAINING_SESSION', 'early_stop_reason') IS NULL
    ALTER TABLE TRAINING_SESSION ADD early_stop_reason NVARCHAR(255) NULL;
IF COL_LENGTH('TRAINING_SESSION', 'review_status') IS NULL
    ALTER TABLE TRAINING_SESSION ADD review_status VARCHAR(30) NOT NULL CONSTRAINT DF_SESSION_review_status DEFAULT 'NONE';
IF COL_LENGTH('TRAINING_SESSION', 'auto_reviewed_at') IS NULL
    ALTER TABLE TRAINING_SESSION ADD auto_reviewed_at DATETIME2 NULL;
GO

IF OBJECT_ID('CK_SESSION_planned_duration', 'C') IS NULL
    ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_planned_duration
        CHECK (planned_duration BETWEEN 15 AND 720);
IF OBJECT_ID('CK_SESSION_review_status', 'C') IS NULL
    ALTER TABLE TRAINING_SESSION ADD CONSTRAINT CK_SESSION_review_status
        CHECK (review_status IN ('NONE', 'PENDING', 'ARCHIVED_AUTO', 'REVIEWED'));
GO

/* ---------- 4. Backfill dữ liệu sẵn có ---------- */

/* Đặt hạn mức ngựa mặc định cho các HEAD_TRAINER hiện có */
UPDATE APP_USER
SET max_horses = 10
WHERE max_horses IS NULL
  AND role_id = (SELECT role_id FROM ROLE WHERE role_name = 'HEAD_TRAINER');

/* Gán ngựa đã có giáo án cho chính HEAD_TRAINER tạo giáo án mới nhất của ngựa đó */
UPDATE h
SET h.trainer_id = p.created_by
FROM HORSE h
CROSS APPLY (
    SELECT TOP 1 tp.created_by
    FROM TRAINING_PLAN tp
    JOIN APP_USER tu ON tu.user_id = tp.created_by
    JOIN ROLE tr ON tr.role_id = tu.role_id
    WHERE tp.horse_id = h.horse_id AND tr.role_name = 'HEAD_TRAINER'
    ORDER BY tp.plan_id DESC
) p
WHERE h.trainer_id IS NULL
  AND h.record_type = 'ACADEMY'
  AND h.activity_status = 'ACTIVE';

/* Gán các GROOM hiện có (tối đa 3) cho HEAD_TRAINER đầu tiên nếu chưa có trainer_id */
DECLARE @defaultTrainerId INT = (
    SELECT TOP 1 u.user_id
    FROM APP_USER u
    JOIN ROLE r ON r.role_id = u.role_id
    WHERE r.role_name = 'HEAD_TRAINER' AND u.status = 'ACTIVE'
    ORDER BY u.user_id
);

IF @defaultTrainerId IS NOT NULL
BEGIN
    DECLARE @alreadyAssignedGrooms INT = (
        SELECT COUNT(*)
        FROM APP_USER u
        JOIN ROLE r ON r.role_id = u.role_id
        WHERE r.role_name = 'GROOM' AND u.status = 'ACTIVE' AND u.trainer_id = @defaultTrainerId
    );

    IF @alreadyAssignedGrooms < 3
    BEGIN
        ;WITH GroomsToAssign AS (
            SELECT TOP (3 - @alreadyAssignedGrooms) u.user_id
            FROM APP_USER u
            JOIN ROLE r ON r.role_id = u.role_id
            WHERE r.role_name = 'GROOM' AND u.status = 'ACTIVE' AND u.trainer_id IS NULL
            ORDER BY u.user_id
        )
        UPDATE u
        SET u.trainer_id = @defaultTrainerId
        FROM APP_USER u
        JOIN GroomsToAssign g ON g.user_id = u.user_id;
    END
END
GO

/* Cập nhật ended_at và review_status cho các buổi tập đã kết thúc trước đây */
UPDATE TRAINING_SESSION
SET ended_at = ISNULL(ended_at, DATEADD(MINUTE, ISNULL(duration_minutes, planned_duration), session_date)),
    review_status = CASE
        WHEN evaluation IS NOT NULL THEN 'REVIEWED'
        ELSE 'PENDING'
    END
WHERE status IN ('COMPLETED', 'STOPPED_EARLY')
  AND review_status = 'NONE';
GO

PRINT 'V37 done.';
GO
