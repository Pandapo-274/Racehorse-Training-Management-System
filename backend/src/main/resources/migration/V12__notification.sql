/* =============================================================================
   V12 - Thông báo trong ứng dụng (cái chuông trên thanh header).

   Mỗi dòng là MỘT thông báo cho MỘT người. Gửi cho cả một vai trò (ví dụ mọi
   Club Manager) thì chèn một dòng cho từng người, không lưu kiểu "gửi cho
   role X": mỗi người đọc thông báo vào lúc khác nhau, nên trạng thái đã đọc
   phải thuộc về từng người.

   read_at NULL = chưa đọc. Không thêm cột is_read riêng: hai cột cùng nói một
   chuyện thì sớm muộn sẽ lệch nhau.

   target_entity + target_id trỏ tới thứ thông báo nói đến (HORSE,
   HORSE_REQUEST, TRAINING_SESSION, THRESHOLD_ALERT). Backend KHÔNG lưu đường
   dẫn frontend: cùng một con ngựa thì mỗi vai trò mở ở một trang khác nhau,
   và đổi route bên frontend không được làm hỏng dữ liệu cũ. Frontend tự quy
   đổi (xem notificationApi.js).
   ============================================================================= */

IF OBJECT_ID('NOTIFICATION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTIFICATION (
        notification_id INT IDENTITY(1,1) PRIMARY KEY,
        user_id         INT           NOT NULL,
        notif_type      VARCHAR(40)   NOT NULL,
        title           NVARCHAR(150) NOT NULL,
        message         NVARCHAR(500) NULL,
        target_entity   VARCHAR(30)   NULL,
        target_id       INT           NULL,
        created_at      DATETIME2     NOT NULL
            CONSTRAINT DF_NOTIF_created DEFAULT SYSUTCDATETIME(),
        read_at         DATETIME2     NULL,

        CONSTRAINT FK_NOTIF_user FOREIGN KEY (user_id) REFERENCES APP_USER(user_id),
        CONSTRAINT CK_NOTIF_target CHECK (target_entity IS NULL OR target_entity IN
            ('HORSE', 'HORSE_REQUEST', 'TRAINING_SESSION', 'THRESHOLD_ALERT')),
        /* Có loại đối tượng thì phải có id, và ngược lại. */
        CONSTRAINT CK_NOTIF_target_pair CHECK (
            (target_entity IS NULL AND target_id IS NULL)
            OR (target_entity IS NOT NULL AND target_id IS NOT NULL)
        )
    );

    /* Danh sách thông báo của một người, mới nhất trước. */
    CREATE INDEX IX_NOTIF_user ON NOTIFICATION(user_id, created_at DESC);

    /* Đếm số chưa đọc: chuông hỏi câu này mỗi 30 giây với mọi người đang
       đăng nhập. Index lọc vì phần lớn thông báo rồi sẽ là đã đọc. */
    CREATE INDEX IX_NOTIF_unread ON NOTIFICATION(user_id) WHERE read_at IS NULL;

    PRINT 'Da tao bang NOTIFICATION.';
END
ELSE
BEGIN
    PRINT 'Bang NOTIFICATION da co san, bo qua.';
END
GO

IF OBJECT_ID('NOTIFICATION', 'U') IS NULL
    THROW 50012, 'Khong tao duoc bang NOTIFICATION.', 1;
GO

/* ---------- Dữ liệu demo ----------
   Sinh từ dữ liệu đang có chứ không bịa: chuông của từng người hiện đúng
   những việc đang thật sự chờ họ trong các bảng khác. */

/* Club Manager: mỗi yêu cầu đăng ký ngựa đang chờ duyệt. */
INSERT INTO NOTIFICATION (user_id, notif_type, title, message,
                          target_entity, target_id, created_at)
SELECT m.user_id, 'HORSE_REQUEST_SUBMITTED',
       N'New horse registration request',
       o.full_name + N' asked to register ' + q.horse_name + N'.',
       'HORSE_REQUEST', q.request_id, q.created_at
FROM HORSE_REQUEST q
JOIN APP_USER o ON o.user_id = q.owner_id
CROSS JOIN (
    SELECT u.user_id
    FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
    WHERE r.role_name = 'CLUB_MANAGER' AND u.status = 'ACTIVE'
) m
WHERE q.status = 'PENDING';
GO

/* Groom: mỗi buổi tập sắp tới đã được giao cho mình. */
INSERT INTO NOTIFICATION (user_id, notif_type, title, message,
                          target_entity, target_id, created_at)
SELECT s.assigned_to, 'TRAINING_SESSION_ASSIGNED',
       N'New training session assigned',
       h.horse_name + N' on ' + FORMAT(s.session_date, 'dd MMM yyyy HH:mm') + N'.',
       'TRAINING_SESSION', s.session_id, SYSUTCDATETIME()
FROM TRAINING_SESSION s
JOIN HORSE h ON h.horse_id = s.horse_id
WHERE s.status = 'SCHEDULED' AND s.session_date >= CAST(SYSUTCDATETIME() AS DATE);
GO

PRINT 'V12 hoan tat.';
GO
