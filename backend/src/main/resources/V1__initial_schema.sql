/* =============================================================================
   Racehorse Training & Management System
   Schema - Microsoft SQL Server (T-SQL)
   Theo database-schema.md, đã bỏ CLUB: 18 entity.
   Hệ thống phục vụ một câu lạc bộ duy nhất, nên club_id là hằng số trên mọi
   dòng - một cột không bao giờ đổi giá trị thì không mang thông tin gì.

   CHẠY TRÊN DATABASE TRỐNG. Nếu đã chạy bản V1 cũ (8 bảng), xoá trước:
       DROP DATABASE rtms;
   rồi chạy lại 00-create-database.sql và file này.

   GHI CHÚ BẮT BUỘC
   - Bảng USER trong tài liệu được tạo ở đây tên APP_USER. USER là hàm niladic
     dành riêng của T-SQL; đặt tên bảng là USER thì mọi câu lệnh phải viết
     [USER]. Tên logic trong ERD vẫn là USER.
   - Mọi FK để ON DELETE NO ACTION. SQL Server từ chối schema có nhiều đường
     cascade tới cùng một bảng, mà APP_USER và HORSE đều bị hơn chục bảng trỏ
     vào. Xoá xử lý ở tầng service.
   - Các cột enum dùng CHECK constraint, không dùng bảng tra cứu.
     Bên JPA map bằng @Enumerated(EnumType.STRING).
   ============================================================================= */

USE rtms;
GO

/* =============================================================================
   1. LÕI - ROLE, USER, HORSE
   ============================================================================= */

CREATE TABLE ROLE (
    role_id     INT IDENTITY(1,1) PRIMARY KEY,
    role_name   VARCHAR(50)   NOT NULL UNIQUE,
    description NVARCHAR(255) NULL,
    CONSTRAINT CK_ROLE_name CHECK (role_name IN
        ('HEAD_TRAINER','VETERINARIAN','GROOM','HORSE_OWNER','CLUB_MANAGER'))
);
GO

/* Phân quyền không có bảng riêng: ứng dụng đọc ROLE.role_name và chặn theo
   code. Club Manager "phân quyền" bằng cách đổi role_id của USER.
   club_id NULL = Horse Owner không thuộc biên chế câu lạc bộ. */
CREATE TABLE APP_USER (
    user_id       INT IDENTITY(1,1) PRIMARY KEY,
    role_id       INT           NOT NULL,
    username      VARCHAR(50)   NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,
    full_name     NVARCHAR(100) NOT NULL,
    email         VARCHAR(100)  NOT NULL UNIQUE,
    phone         VARCHAR(20)   NULL,
    status        VARCHAR(20)   NOT NULL CONSTRAINT DF_USER_status DEFAULT 'ACTIVE',
    created_at    DATETIME2     NOT NULL CONSTRAINT DF_USER_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_USER_role   FOREIGN KEY (role_id) REFERENCES ROLE(role_id),
    CONSTRAINT CK_USER_status CHECK (status IN ('ACTIVE','SUSPENDED'))
);
GO
CREATE INDEX IX_USER_role ON APP_USER(role_id);
GO

/* stall_no gộp thẳng vào HORSE thay vì tạo bảng STABLE riêng.
   sire_id / dam_id là hai FK đệ quy tách biệt để phả hệ phân biệt được cha mẹ. */
CREATE TABLE HORSE (
    horse_id          INT IDENTITY(1,1) PRIMARY KEY,
    owner_id          INT           NOT NULL,
    sire_id           INT           NULL,
    dam_id            INT           NULL,
    horse_name        NVARCHAR(100) NOT NULL,
    registration_code VARCHAR(30)   NOT NULL UNIQUE,
    breed             NVARCHAR(50)  NULL,
    horse_gender      VARCHAR(10)   NULL,
    date_of_birth     DATE          NULL,
    color             NVARCHAR(50)  NULL,
    weight            DECIMAL(6,2)  NULL,
    stall_no          VARCHAR(20)   NULL,
    status            VARCHAR(30)   NOT NULL CONSTRAINT DF_HORSE_status DEFAULT 'ELIGIBLE',
    created_at        DATETIME2     NOT NULL CONSTRAINT DF_HORSE_created DEFAULT SYSUTCDATETIME(),
    updated_at        DATETIME2     NULL,
    CONSTRAINT FK_HORSE_owner   FOREIGN KEY (owner_id) REFERENCES APP_USER(user_id),
    CONSTRAINT FK_HORSE_sire    FOREIGN KEY (sire_id)  REFERENCES HORSE(horse_id),
    CONSTRAINT FK_HORSE_dam     FOREIGN KEY (dam_id)   REFERENCES HORSE(horse_id),
    CONSTRAINT CK_HORSE_status  CHECK (status IN ('ELIGIBLE','MONITOR','INJURED','QUARANTINE')),
    CONSTRAINT CK_HORSE_gender  CHECK (horse_gender IN ('COLT','FILLY','STALLION','MARE','GELDING')),
    CONSTRAINT CK_HORSE_notself CHECK (sire_id <> horse_id AND dam_id <> horse_id)
);
GO
/* Một ô chuồng chỉ chứa một con ngựa. Filtered vì nhiều con chưa xếp chuồng
   (NULL) - UNIQUE thường sẽ chặn luôn trường hợp đó. */
CREATE UNIQUE INDEX UX_HORSE_stall ON HORSE(stall_no) WHERE stall_no IS NOT NULL;
CREATE INDEX IX_HORSE_owner  ON HORSE(owner_id);
CREATE INDEX IX_HORSE_status ON HORSE(status);
GO

/* =============================================================================
   2. FLOW 2 - GIÁO ÁN HUẤN LUYỆN
   ============================================================================= */

CREATE TABLE TRAINING_PLAN (
    plan_id    INT IDENTITY(1,1) PRIMARY KEY,
    horse_id   INT           NOT NULL,
    created_by INT           NOT NULL,
    plan_name  NVARCHAR(150) NOT NULL,
    start_date DATE          NOT NULL,
    end_date   DATE          NOT NULL,
    status     VARCHAR(30)   NOT NULL CONSTRAINT DF_PLAN_status DEFAULT 'DRAFT',
    created_at DATETIME2     NOT NULL CONSTRAINT DF_PLAN_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_PLAN_horse   FOREIGN KEY (horse_id)   REFERENCES HORSE(horse_id),
    CONSTRAINT FK_PLAN_creator FOREIGN KEY (created_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_PLAN_status  CHECK (status IN ('DRAFT','ACTIVE','COMPLETED','PAUSED')),
    CONSTRAINT CK_PLAN_dates   CHECK (end_date >= start_date)
);
GO

/* heart_rate_ceiling là ngưỡng THRESHOLD_ALERT đối chiếu sau mỗi buổi tập. */
CREATE TABLE PLAN_PHASE (
    phase_id           INT IDENTITY(1,1) PRIMARY KEY,
    plan_id            INT           NOT NULL,
    phase_no           INT           NOT NULL,
    phase_name         NVARCHAR(100) NOT NULL,
    distance           INT           NULL,
    workload           NVARCHAR(50)  NULL,
    surface            VARCHAR(50)   NULL,
    sessions_per_week  INT           NULL,
    heart_rate_ceiling INT           NULL,
    start_date         DATE          NULL,
    end_date           DATE          NULL,
    CONSTRAINT FK_PHASE_plan    FOREIGN KEY (plan_id) REFERENCES TRAINING_PLAN(plan_id),
    CONSTRAINT UQ_PHASE_no      UNIQUE (plan_id, phase_no),
    CONSTRAINT CK_PHASE_surface CHECK (surface IN ('TURF','DIRT','SYNTHETIC')),
    CONSTRAINT CK_PHASE_hr      CHECK (heart_rate_ceiling BETWEEN 100 AND 260)
);
GO

/* horse_id giữ lại để query nhanh, không phải join qua 2 lớp.
   Đánh đổi: nó có thể lệch với TRAINING_PLAN.horse_id của phase tương ứng.
   Service layer phải set horse_id từ phase khi tạo session, đừng cho nhập tay.
   peak_heart_rate là cột THRESHOLD_ALERT đọc để so ngưỡng. */
CREATE TABLE TRAINING_SESSION (
    session_id          INT IDENTITY(1,1) PRIMARY KEY,
    phase_id            INT           NOT NULL,
    horse_id            INT           NOT NULL,
    assigned_to         INT           NOT NULL,
    session_date        DATETIME2     NOT NULL,
    planned_distance    INT           NULL,
    actual_distance     INT           NULL,
    duration_minutes    INT           NULL,
    avg_heart_rate      INT           NULL,
    peak_heart_rate     INT           NULL,
    recovery_heart_rate INT           NULL,
    peak_velocity       DECIMAL(5,2)  NULL,
    evaluation          VARCHAR(30)   NULL,
    remarks             NVARCHAR(MAX) NULL,
    evaluated_by        INT           NULL,
    status              VARCHAR(30)   NOT NULL CONSTRAINT DF_SESSION_status DEFAULT 'SCHEDULED',
    CONSTRAINT FK_SESSION_phase     FOREIGN KEY (phase_id)     REFERENCES PLAN_PHASE(phase_id),
    CONSTRAINT FK_SESSION_horse     FOREIGN KEY (horse_id)     REFERENCES HORSE(horse_id),
    CONSTRAINT FK_SESSION_groom     FOREIGN KEY (assigned_to)  REFERENCES APP_USER(user_id),
    CONSTRAINT FK_SESSION_evaluator FOREIGN KEY (evaluated_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_SESSION_status CHECK (status IN ('SCHEDULED','RUNNING','COMPLETED','STOPPED_EARLY')),
    CONSTRAINT CK_SESSION_eval   CHECK (evaluation IN ('POOR','FAIR','GOOD','EXCELLENT'))
);
GO
CREATE INDEX IX_SESSION_horse ON TRAINING_SESSION(horse_id, session_date DESC);
CREATE INDEX IX_SESSION_groom ON TRAINING_SESSION(assigned_to);
GO

/* Sinh cảnh báo NGAY SAU buổi tập bằng cách so peak_heart_rate / peak_velocity
   của TRAINING_SESSION với heart_rate_ceiling của PLAN_PHASE - không theo dõi
   realtime từng giây, vì không còn bảng metric. */
CREATE TABLE THRESHOLD_ALERT (
    alert_id        INT IDENTITY(1,1) PRIMARY KEY,
    session_id      INT           NOT NULL,
    metric_type     VARCHAR(30)   NOT NULL,
    threshold_value DECIMAL(8,2)  NOT NULL,
    actual_value    DECIMAL(8,2)  NOT NULL,
    severity        VARCHAR(20)   NOT NULL,
    triggered_at    DATETIME2     NOT NULL CONSTRAINT DF_ALERT_time DEFAULT SYSUTCDATETIME(),
    action_taken    VARCHAR(50)   NULL,
    resolved_by     INT           NULL,
    CONSTRAINT FK_ALERT_session  FOREIGN KEY (session_id)  REFERENCES TRAINING_SESSION(session_id),
    CONSTRAINT FK_ALERT_resolver FOREIGN KEY (resolved_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_ALERT_metric   CHECK (metric_type IN ('HEART_RATE','VELOCITY','WORKLOAD')),
    CONSTRAINT CK_ALERT_severity CHECK (severity IN ('HIGH','MEDIUM','LOW'))
);
GO

/* =============================================================================
   3. FLOW 3 - Y TẾ VÀ CHẤN THƯƠNG
   ============================================================================= */

/* Đầu vào của Groom, tách khỏi MEDICAL_RECORD vì đó là đầu ra của Vet.
   status đóng luôn vai hàng chờ khám, nên không cần bảng thông báo riêng. */
CREATE TABLE INCIDENT_REPORT (
    report_id   INT IDENTITY(1,1) PRIMARY KEY,
    horse_id    INT           NOT NULL,
    reporter_id INT           NOT NULL,
    symptoms    NVARCHAR(255) NOT NULL,
    description NVARCHAR(MAX) NULL,
    photo_url   VARCHAR(255)  NULL,
    urgency     VARCHAR(20)   NOT NULL,
    reported_at DATETIME2     NOT NULL CONSTRAINT DF_INC_time DEFAULT SYSUTCDATETIME(),
    status      VARCHAR(30)   NOT NULL CONSTRAINT DF_INC_status DEFAULT 'PENDING',
    CONSTRAINT FK_INC_horse    FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_INC_reporter FOREIGN KEY (reporter_id) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_INC_urgency  CHECK (urgency IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT CK_INC_status   CHECK (status IN ('PENDING','RECEIVED','EXAMINED'))
);
GO
CREATE INDEX IX_INC_status ON INCIDENT_REPORT(status);
GO

/* Gánh cả khám bệnh lẫn chăm sóc phòng ngừa, phân biệt bằng record_type.
   next_due_date là cột query nhắc lịch tiêm phòng / tẩy giun / farrier. */
CREATE TABLE MEDICAL_RECORD (
    record_id          INT IDENTITY(1,1) PRIMARY KEY,
    horse_id           INT           NOT NULL,
    vet_id             INT           NOT NULL,
    report_id          INT           NULL,
    record_type        VARCHAR(30)   NOT NULL CONSTRAINT DF_MED_type DEFAULT 'EXAM',
    exam_date          DATETIME2     NOT NULL,
    diagnosis          NVARCHAR(255) NULL,
    health_status      VARCHAR(30)   NULL,
    injury_confirmed   BIT           NOT NULL CONSTRAINT DF_MED_injury DEFAULT 0,
    expected_rest_days INT           NULL,
    next_due_date      DATE          NULL,
    notes              NVARCHAR(MAX) NULL,
    CONSTRAINT FK_MED_horse  FOREIGN KEY (horse_id)  REFERENCES HORSE(horse_id),
    CONSTRAINT FK_MED_vet    FOREIGN KEY (vet_id)    REFERENCES APP_USER(user_id),
    CONSTRAINT FK_MED_report FOREIGN KEY (report_id) REFERENCES INCIDENT_REPORT(report_id),
    CONSTRAINT CK_MED_type   CHECK (record_type IN ('EXAM','VACCINATION','DEWORMING','FARRIER')),
    CONSTRAINT CK_MED_status CHECK (health_status IN ('ELIGIBLE','MONITOR','INJURED','QUARANTINE'))
);
GO
CREATE INDEX IX_MED_horse_date ON MEDICAL_RECORD(horse_id, exam_date DESC);
CREATE INDEX IX_MED_due        ON MEDICAL_RECORD(next_due_date) WHERE next_due_date IS NOT NULL;
GO

/* Tách khỏi MEDICAL_RECORD vì vòng lặp tái khám sinh nhiều bản ghi khám cho
   CÙNG một vết thương. coordinate_x/y là toạ độ đánh dấu trên mô hình 3D. */
CREATE TABLE INJURY (
    injury_id     INT IDENTITY(1,1) PRIMARY KEY,
    record_id     INT           NOT NULL,
    body_location NVARCHAR(100) NOT NULL,
    coordinate_x  DECIMAL(6,2)  NULL,
    coordinate_y  DECIMAL(6,2)  NULL,
    severity      VARCHAR(30)   NOT NULL,
    status        VARCHAR(30)   NOT NULL CONSTRAINT DF_INJ_status DEFAULT 'TREATING',
    marked_at     DATETIME2     NOT NULL CONSTRAINT DF_INJ_marked DEFAULT SYSUTCDATETIME(),
    recovered_at  DATETIME2     NULL,
    CONSTRAINT FK_INJ_record    FOREIGN KEY (record_id) REFERENCES MEDICAL_RECORD(record_id),
    CONSTRAINT CK_INJ_severity  CHECK (severity IN ('GRADE_1','GRADE_2','GRADE_3')),
    CONSTRAINT CK_INJ_status    CHECK (status IN ('TREATING','PARTIAL','RECOVERED')),
    CONSTRAINT CK_INJ_recovered CHECK (status <> 'RECOVERED' OR recovered_at IS NOT NULL)
);
GO

CREATE TABLE TREATMENT (
    treatment_id  INT IDENTITY(1,1) PRIMARY KEY,
    record_id     INT           NOT NULL,
    medicine_name NVARCHAR(150) NULL,
    dosage        NVARCHAR(50)  NULL,
    frequency     NVARCHAR(50)  NULL,
    instruction   NVARCHAR(MAX) NULL,
    start_date    DATE          NOT NULL,
    end_date      DATE          NULL,
    CONSTRAINT FK_TRT_record FOREIGN KEY (record_id) REFERENCES MEDICAL_RECORD(record_id),
    CONSTRAINT CK_TRT_dates  CHECK (end_date IS NULL OR end_date >= start_date)
);
GO

/* Lệnh khoá có vòng đời riêng: ai ra, mức nào, ai gỡ, gỡ lúc nào. */
CREATE TABLE TRAINING_LOCK (
    lock_id     INT IDENTITY(1,1) PRIMARY KEY,
    horse_id    INT           NOT NULL,
    injury_id   INT           NULL,
    issued_by   INT           NOT NULL,
    reason      NVARCHAR(255) NOT NULL,
    lock_level  VARCHAR(30)   NOT NULL,
    issued_at   DATETIME2     NOT NULL CONSTRAINT DF_LOCK_issued DEFAULT SYSUTCDATETIME(),
    released_by INT           NULL,
    released_at DATETIME2     NULL,
    status      VARCHAR(20)   NOT NULL CONSTRAINT DF_LOCK_status DEFAULT 'ACTIVE',
    CONSTRAINT FK_LOCK_horse    FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_LOCK_injury   FOREIGN KEY (injury_id)   REFERENCES INJURY(injury_id),
    CONSTRAINT FK_LOCK_issuer   FOREIGN KEY (issued_by)   REFERENCES APP_USER(user_id),
    CONSTRAINT FK_LOCK_releaser FOREIGN KEY (released_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_LOCK_level    CHECK (lock_level IN ('FULL','HEAVY_WORK_ONLY')),
    CONSTRAINT CK_LOCK_status   CHECK (status IN ('ACTIVE','RELEASED')),
    CONSTRAINT CK_LOCK_released CHECK (status <> 'RELEASED' OR (released_by IS NOT NULL AND released_at IS NOT NULL))
);
GO
/* Mỗi con ngựa chỉ có tối đa một lệnh khoá ACTIVE. */
CREATE UNIQUE INDEX UX_LOCK_active ON TRAINING_LOCK(horse_id) WHERE status = 'ACTIVE';
GO

/* =============================================================================
   4. HỆ THỐNG
   ============================================================================= */

/* target_entity + target_id là khoá ngoại đa hình - không vẽ được đường quan hệ
   trong ERD, nêu miệng khi bảo vệ. user_id NULL khi đăng nhập thất bại chưa xác
   định được tài khoản. */
CREATE TABLE AUDIT_LOG (
    log_id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    user_id       INT          NULL,
    action        VARCHAR(100) NOT NULL,
    target_entity VARCHAR(50)  NULL,
    target_id     INT          NULL,
    result        VARCHAR(20)  NOT NULL,
    ip_address    VARCHAR(45)  NULL,
    created_at    DATETIME2    NOT NULL CONSTRAINT DF_AUD_time DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_AUD_user   FOREIGN KEY (user_id) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_AUD_result CHECK (result IN ('SUCCESS','DENIED'))
);
GO
CREATE INDEX IX_AUD_time ON AUDIT_LOG(created_at DESC);
CREATE INDEX IX_AUD_user ON AUDIT_LOG(user_id);
GO

/* =============================================================================
   5. CHỨC NĂNG NHỎ - mỗi chức năng đúng 1 bảng, không quy trình duyệt
   ============================================================================= */

/* Một dòng = một loại thức ăn trong một bữa. Ba loại trong bữa sáng = ba dòng. */
CREATE TABLE FEEDING_SCHEDULE (
    feeding_id  INT IDENTITY(1,1) PRIMARY KEY,
    horse_id    INT           NOT NULL,
    meal_time   VARCHAR(20)   NOT NULL,
    feed_type   NVARCHAR(50)  NOT NULL,
    quantity    DECIMAL(6,2)  NOT NULL,
    unit        NVARCHAR(20)  NOT NULL,
    approved_by INT           NULL,
    note        NVARCHAR(255) NULL,
    CONSTRAINT FK_FEED_horse    FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_FEED_approver FOREIGN KEY (approved_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_FEED_meal     CHECK (meal_time IN ('MORNING','NOON','EVENING')),
    CONSTRAINT CK_FEED_qty      CHECK (quantity > 0)
);
GO

CREATE TABLE DAILY_TASK (
    task_id      INT IDENTITY(1,1) PRIMARY KEY,
    horse_id     INT           NOT NULL,
    task_type    VARCHAR(50)   NOT NULL,
    assigned_to  INT           NOT NULL,
    task_date    DATE          NOT NULL,
    is_completed BIT           NOT NULL CONSTRAINT DF_TASK_done DEFAULT 0,
    completed_at DATETIME2     NULL,
    note         NVARCHAR(255) NULL,
    CONSTRAINT FK_TASK_horse FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_TASK_user  FOREIGN KEY (assigned_to) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_TASK_type  CHECK (task_type IN ('FEEDING','CLEANING','BATHING','ICE_BATH')),
    /* đánh dấu xong thì phải có mốc thời gian, và ngược lại */
    CONSTRAINT CK_TASK_done  CHECK ((is_completed = 1 AND completed_at IS NOT NULL)
                                 OR (is_completed = 0 AND completed_at IS NULL))
);
GO
CREATE INDEX IX_TASK_user_date ON DAILY_TASK(assigned_to, task_date);
GO

/* "Đề xuất bổ sung" = query current_qty < min_threshold, không có bảng duyệt.
   Cột needs_restock tính sẵn để khỏi lặp điều kiện ở mọi câu query. */
CREATE TABLE SUPPLY_STOCK (
    supply_id     INT IDENTITY(1,1) PRIMARY KEY,
    supply_name   NVARCHAR(100) NOT NULL,
    category      VARCHAR(30)   NOT NULL,
    unit          NVARCHAR(20)  NOT NULL,
    min_threshold DECIMAL(8,2)  NOT NULL CONSTRAINT DF_SUP_min DEFAULT 0,
    current_qty   DECIMAL(8,2)  NOT NULL CONSTRAINT DF_SUP_qty DEFAULT 0,
    area          NVARCHAR(100) NULL,
    updated_by    INT           NOT NULL,
    updated_at    DATETIME2     NOT NULL CONSTRAINT DF_SUP_time DEFAULT SYSUTCDATETIME(),
    needs_restock AS (CASE WHEN current_qty < min_threshold THEN 1 ELSE 0 END) PERSISTED,
    CONSTRAINT FK_SUP_user     FOREIGN KEY (updated_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_SUP_category CHECK (category IN ('FEED','MEDICINE','TOOL')),
    CONSTRAINT CK_SUP_qty      CHECK (current_qty >= 0)
);
GO
CREATE INDEX IX_SUP_needs ON SUPPLY_STOCK(needs_restock) WHERE needs_restock = 1;
GO

/* Đăng ký và kết quả trong cùng một dòng. result NULL = chưa thi đấu. */
CREATE TABLE RACE_REGISTRATION (
    registration_id INT IDENTITY(1,1) PRIMARY KEY,
    horse_id        INT           NOT NULL,
    race_name       NVARCHAR(150) NOT NULL,
    race_date       DATE          NOT NULL,
    registered_by   INT           NOT NULL,
    result          VARCHAR(30)   NULL,
    prize_money     DECIMAL(12,2) NULL,
    note            NVARCHAR(255) NULL,
    CONSTRAINT FK_REG_horse FOREIGN KEY (horse_id)      REFERENCES HORSE(horse_id),
    CONSTRAINT FK_REG_user  FOREIGN KEY (registered_by) REFERENCES APP_USER(user_id),
    CONSTRAINT UQ_REG_entry UNIQUE (horse_id, race_name, race_date),
    CONSTRAINT CK_REG_prize CHECK (prize_money IS NULL OR prize_money >= 0)
);
GO
CREATE INDEX IX_REG_date ON RACE_REGISTRATION(race_date DESC);
GO

CREATE TABLE EXPENSE_LOG (
    expense_id   INT IDENTITY(1,1) PRIMARY KEY,
    horse_id     INT           NOT NULL,
    category     VARCHAR(30)   NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    expense_date DATE          NOT NULL,
    recorded_by  INT           NULL,
    note         NVARCHAR(255) NULL,
    CONSTRAINT FK_EXP_horse FOREIGN KEY (horse_id)    REFERENCES HORSE(horse_id),
    CONSTRAINT FK_EXP_user  FOREIGN KEY (recorded_by) REFERENCES APP_USER(user_id),
    CONSTRAINT CK_EXP_cat   CHECK (category IN ('FEEDING','MEDICAL','TRAINING','OTHER')),
    CONSTRAINT CK_EXP_amt   CHECK (amount >= 0)
);
GO
CREATE INDEX IX_EXP_date ON EXPENSE_LOG(expense_date DESC);
GO

/* =============================================================================
   6. SEED
   ============================================================================= */

INSERT INTO ROLE (role_name, description) VALUES
    ('HEAD_TRAINER', N'Huấn luyện viên trưởng'),
    ('VETERINARIAN', N'Bác sĩ thú y'),
    ('GROOM',        N'Nhân viên chăm sóc & chuồng trại'),
    ('HORSE_OWNER',  N'Chủ sở hữu ngựa'),
    ('CLUB_MANAGER', N'Quản lý câu lạc bộ');
GO

