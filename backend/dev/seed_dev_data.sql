/* =============================================================================
   DỮ LIỆU MẪU CHO DEV: chạy tay trong SSMS / Azure Data Studio, chọn đúng DB (vd. rtms).
   KHÔNG phải migration. KHÔNG chạy trên môi trường thật.
   Chạy lại nhiều lần không bị trùng.
   Tài khoản mẫu (mật khẩu chung: Test1234):
     trainer.demo = HEAD_TRAINER | groom.demo = GROOM | vet.demo = VETERINARIAN | owner.demo = HORSE_OWNER
   ============================================================================= */
DECLARE @pwd VARCHAR(255) = '$2b$10$0CU9NdEmCVuCzVDsbYVR5uL8RcPtKt5X2i2r4GDXfqpn73iNRNnQW';

IF NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'trainer.demo')
    INSERT INTO APP_USER (role_id, username, password_hash, full_name, email)
    VALUES ((SELECT role_id FROM ROLE WHERE role_name = 'HEAD_TRAINER'), 'trainer.demo', @pwd, N'Demo Head Trainer', 'trainer.demo@rtms.local');
IF NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'groom.demo')
    INSERT INTO APP_USER (role_id, username, password_hash, full_name, email)
    VALUES ((SELECT role_id FROM ROLE WHERE role_name = 'GROOM'), 'groom.demo', @pwd, N'Demo Groom', 'groom.demo@rtms.local');
IF NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'vet.demo')
    INSERT INTO APP_USER (role_id, username, password_hash, full_name, email)
    VALUES ((SELECT role_id FROM ROLE WHERE role_name = 'VETERINARIAN'), 'vet.demo', @pwd, N'Demo Veterinarian', 'vet.demo@rtms.local');
IF NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'owner.demo')
    INSERT INTO APP_USER (role_id, username, password_hash, full_name, email)
    VALUES ((SELECT role_id FROM ROLE WHERE role_name = 'HORSE_OWNER'), 'owner.demo', @pwd, N'Demo Owner', 'owner.demo@rtms.local');

DECLARE @owner   INT = (SELECT user_id FROM APP_USER WHERE username = 'owner.demo');
DECLARE @trainer INT = (SELECT user_id FROM APP_USER WHERE username = 'trainer.demo');
DECLARE @groom   INT = (SELECT user_id FROM APP_USER WHERE username = 'groom.demo');
DECLARE @vet     INT = (SELECT user_id FROM APP_USER WHERE username = 'vet.demo');

/* 4 con ngựa đủ 4 trạng thái để thấy 4 màu trên bản đồ chuồng */
IF NOT EXISTS (SELECT 1 FROM HORSE WHERE registration_code = 'DEMO-001')
    INSERT INTO HORSE (owner_id, horse_name, registration_code, breed, horse_gender, weight, stall_no, status) VALUES
        (@owner, N'Thunder Bolt',  'DEMO-001', N'Thoroughbred', 'STALLION', 480, 'A1', 'ELIGIBLE'),
        (@owner, N'Silver Mist',   'DEMO-002', N'Thoroughbred', 'MARE',     455, 'A2', 'MONITOR'),
        (@owner, N'Red Dawn',      'DEMO-003', N'Thoroughbred', 'GELDING',  470, 'B1', 'INJURED'),
        (@owner, N'Night Whisper', 'DEMO-004', N'Thoroughbred', 'FILLY',    440, 'B2', 'QUARANTINE');

DECLARE @h1 INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DEMO-001');
DECLARE @h3 INT = (SELECT horse_id FROM HORSE WHERE registration_code = 'DEMO-003');

/* Giáo án + giai đoạn (ngưỡng nhịp tim 190) + 2 ca tập: 1 đang chạy, 1 đã lên lịch */
IF NOT EXISTS (SELECT 1 FROM TRAINING_PLAN WHERE plan_name = N'DEMO Plan - Thunder Bolt')
BEGIN
    INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date, status)
    VALUES (@h1, @trainer, N'DEMO Plan - Thunder Bolt', CAST(SYSUTCDATETIME() AS DATE),
            DATEADD(DAY, 30, CAST(SYSUTCDATETIME() AS DATE)), 'ACTIVE');
    DECLARE @plan INT = SCOPE_IDENTITY();

    INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface, sessions_per_week, heart_rate_ceiling)
    VALUES (@plan, 1, N'Base conditioning', 1600, N'MODERATE', 'TURF', 4, 190);
    DECLARE @phase INT = SCOPE_IDENTITY();

    INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance, status) VALUES
        (@phase, @h1, @groom, SYSUTCDATETIME(),                 1600, 'RUNNING'),
        (@phase, @h1, @groom, DATEADD(DAY, 1, SYSUTCDATETIME()), 1600, 'SCHEDULED');
END

/* Red Dawn: có hồ sơ khám + lệnh khoá huấn luyện để bản đồ có dữ liệu hiển thị */
IF NOT EXISTS (SELECT 1 FROM MEDICAL_RECORD WHERE horse_id = @h3 AND diagnosis = N'Suspected tendon strain')
    INSERT INTO MEDICAL_RECORD (horse_id, vet_id, record_type, exam_date, diagnosis, health_status, injury_confirmed, expected_rest_days)
    VALUES (@h3, @vet, 'EXAM', SYSUTCDATETIME(), N'Suspected tendon strain', 'INJURED', 1, 21);
IF NOT EXISTS (SELECT 1 FROM TRAINING_LOCK WHERE horse_id = @h3 AND status = 'ACTIVE')
    INSERT INTO TRAINING_LOCK (horse_id, issued_by, reason, lock_level)
    VALUES (@h3, @vet, N'Demo: suspected tendon strain', 'FULL');
