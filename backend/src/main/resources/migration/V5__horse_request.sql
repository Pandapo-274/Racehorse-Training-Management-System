/* =============================================================================
   V5 - Yêu cầu đăng ký ngựa.

   VẤN ĐỀ
   Chủ ngựa không tự đăng ký ngựa được: HorseController.create gắn
   @RequireRole({"HEAD_TRAINER","CLUB_MANAGER"}), và HorseService còn bắt
   ownerId phải trỏ tới một tài khoản HORSE_OWNER. Thiết kế đó đúng - hồ sơ
   ngựa là giấy tờ của học viện, không phải thứ người ngoài tự khai - nhưng nó
   để lại một khoảng trống: chủ ngựa mới vào không có cách nào nói với học
   viện rằng mình có ngựa muốn gửi.

   Bảng này lấp đúng khoảng trống ấy. Nó KHÔNG phải bản sao của HORSE: nó là
   lời đề nghị, còn HORSE là hồ sơ chính thức. Hai thứ khác nhau ở chỗ ai
   được ghi và ghi được gì.

   VÌ SAO KHÔNG GỘP VÀO BẢNG HORSE
   Thêm một cột status = 'PENDING' vào HORSE nghe gọn hơn, nhưng mọi truy vấn
   đang có - danh sách ngựa, bản đồ chuồng, giáo án, cảnh báo - sẽ phải nhớ
   lọc bỏ những dòng chưa duyệt. Quên một chỗ là ngựa chưa duyệt lọt vào sổ
   như ngựa thật. Để riêng thì không ai phải nhớ gì cả.
   ============================================================================= */

IF OBJECT_ID('HORSE_REQUEST', 'U') IS NULL
BEGIN
    CREATE TABLE HORSE_REQUEST (
        request_id    INT IDENTITY(1,1) PRIMARY KEY,
        owner_id      INT           NOT NULL,

        /* Những gì chủ ngựa biết. Chỉ horse_name là bắt buộc - người gửi yêu
           cầu có thể chưa nắm đủ giấy tờ, và bắt khai đủ sẽ chặn đúng việc mà
           bảng này sinh ra để mở. Mã đăng ký cố ý KHÔNG có ở đây: nó do học
           viện cấp lúc duyệt, không phải thứ chủ ngựa tự đặt. */
        horse_name    NVARCHAR(100) NOT NULL,
        breed         NVARCHAR(50)  NULL,
        horse_gender  VARCHAR(10)   NULL,
        date_of_birth DATE          NULL,
        color         NVARCHAR(50)  NULL,
        note          NVARCHAR(500) NULL,

        status        VARCHAR(20)   NOT NULL
            CONSTRAINT DF_HREQ_status DEFAULT 'PENDING',
        created_at    DATETIME2     NOT NULL
            CONSTRAINT DF_HREQ_created DEFAULT SYSUTCDATETIME(),

        reviewed_by   INT           NULL,
        reviewed_at   DATETIME2     NULL,
        review_note   NVARCHAR(255) NULL,

        /* Khi duyệt, con ngựa thật được tạo trong HORSE và id của nó ghi vào
           đây. Nhờ vậy nhìn một yêu cầu đã duyệt là biết nó thành con nào,
           không phải dò theo tên. */
        horse_id      INT           NULL,

        CONSTRAINT FK_HREQ_owner    FOREIGN KEY (owner_id)    REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HREQ_reviewer FOREIGN KEY (reviewed_by) REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HREQ_horse    FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),

        CONSTRAINT CK_HREQ_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
        CONSTRAINT CK_HREQ_gender CHECK (horse_gender IS NULL OR horse_gender IN
            ('COLT', 'FILLY', 'STALLION', 'MARE', 'GELDING')),

        /* Đã xử lý thì phải biết ai xử lý và lúc nào. Không có ràng buộc này
           thì một yêu cầu có thể mang trạng thái REJECTED mà không ai chịu
           trách nhiệm, và sau đó không ai dựng lại được chuyện gì đã xảy ra. */
        CONSTRAINT CK_HREQ_reviewed CHECK (
            status = 'PENDING'
            OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        ),

        /* Duyệt thì bắt buộc có con ngựa; từ chối thì bắt buộc không có. */
        CONSTRAINT CK_HREQ_horse CHECK (
            (status = 'APPROVED' AND horse_id IS NOT NULL)
            OR (status <> 'APPROVED' AND horse_id IS NULL)
        )
    );

    /* Hàng chờ duyệt: học viện mở màn hình là lọc đúng status = 'PENDING'.
       Index lọc vì phần lớn hàng trong bảng rồi sẽ là đã xử lý. */
    CREATE INDEX IX_HREQ_pending ON HORSE_REQUEST(created_at)
        WHERE status = 'PENDING';

    /* Chủ ngựa xem lại các yêu cầu của chính mình. */
    CREATE INDEX IX_HREQ_owner ON HORSE_REQUEST(owner_id, created_at DESC);

    /* Một chủ ngựa không gửi hai yêu cầu đang chờ cho cùng một cái tên. Bấm
       nút hai lần vì tưởng lần đầu chưa ăn là chuyện rất thường, và hai dòng
       giống hệt nhau trong hàng chờ thì người duyệt không biết nên xử lý cái
       nào. Index lọc nên vẫn cho phép gửi lại sau khi yêu cầu cũ bị từ chối. */
    CREATE UNIQUE INDEX UX_HREQ_open ON HORSE_REQUEST(owner_id, horse_name)
        WHERE status = 'PENDING';

    PRINT 'Da tao bang HORSE_REQUEST.';
END
ELSE
BEGIN
    PRINT 'Bang HORSE_REQUEST da co san, bo qua.';
END
GO

IF OBJECT_ID('HORSE_REQUEST', 'U') IS NULL
    THROW 50005, 'Khong tao duoc bang HORSE_REQUEST.', 1;
GO

/* ---------- Dữ liệu demo ----------
   Ba yêu cầu để màn hình nào cũng có cả ba trạng thái mà xem. Dùng các tài
   khoản chủ ngựa V3 đã tạo. */

INSERT INTO HORSE_REQUEST (owner_id, horse_name, breed, horse_gender, date_of_birth,
                           color, note, status, created_at)
SELECT u.user_id, v.horse_name, v.breed, v.horse_gender,
       CAST(v.date_of_birth AS DATE), v.color, v.note, 'PENDING',
       DATEADD(DAY, v.day_offset, SYSUTCDATETIME())
FROM (VALUES
    ('mai.ht',  N'Sakura Bakushin O', 'Thoroughbred', 'FILLY', '2024-03-18', N'Chestnut',
     N'Mới mua từ trại Hokkaido, giấy tờ đang chuyển về.', -3),
    ('son.dv',  N'Tokai Teio',        'Thoroughbred', 'COLT',  '2024-04-02', N'Bay',
     N'Nhờ học viện xem giúp trước khi nhận chuồng.', -1),
    ('trang.lt', N'Nice Nature',      'Thoroughbred', 'COLT',  '2023-12-11', N'Chestnut',
     NULL, -6)
) AS v(owner_username, horse_name, breed, horse_gender, date_of_birth, color, note, day_offset)
JOIN APP_USER u ON u.username = v.owner_username;
GO

PRINT 'V5 hoan tat.';
GO
