Khắc phục các lỗi phân quyền, hiển thị và phân công trong luồng quản lý huấn luyện (Racehorse Training Management System), bao gồm phân công Head Trainer cho Groom, giới hạn danh sách ngựa và groom theo Head Trainer phụ trách khi tạo session/plan, và bổ sung thông báo nội bộ hai chiều khi phân công.

Working directory: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System
Integrity mode: development

## Verification Resources
- Database migrations: `backend/src/main/resources/migration/` (bao gồm `V37__training_flow_enhancements.sql` và các migration liên quan)
- Test suite: `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`
- Backend source:
  - `backend/src/main/java/racehorse/backend/staff/` (StaffRow, StaffRepository, StaffService, StaffController)
  - `backend/src/main/java/racehorse/backend/horse/` (HorseResponse, HorseIdentityRequest, HorseRepository, HorseService, HorseController)
  - `backend/src/main/java/racehorse/backend/horserequest/` (ReviewRequest, HorseRequestService)
  - `backend/src/main/java/racehorse/backend/training/` (TrainingPlanService, TrainingSessionService, TrainingSessionRepository, TrainingSessionController)
  - `backend/src/main/java/racehorse/backend/session/` (SessionService, SessionRepository, SessionController)
  - `backend/src/main/java/racehorse/backend/notification/` (NotificationService, NotificationRepository, NotificationController)
- Frontend source:
  - `frontend/src/features/manager/StaffPage.js`, `staffApi.js`
  - `frontend/src/features/horse/HorseListPage.js`, `HorseDetailPage.js`, `HorseReviewPage.js`, `horseApi.js`
  - `frontend/src/features/trainer/TrainingPlanPage.js`, `SchedulePage.js`, `SessionEvaluationPage.js`

## Requirements

### R1. Phân công Head Trainer cho Groom trong trang Staff của Club Manager
- Trong trang Staff (`StaffPage.js`), tài khoản Club Manager có thể gán và cập nhật Head Trainer quản lý cho từng Groom một cách chính xác.
- Đảm bảo thực thi ràng buộc 1 Head Trainer chỉ quản lý tối đa 3 Groom tại một thời điểm; chặn thao tác phân công và hiển thị thông báo lỗi rõ ràng nếu vượt quá hạn mức.

### R2. Giới hạn hiển thị danh sách Ngựa theo Head Trainer quản lý
- Trong trang Horse (`HorseListPage.js` và API `/api/horses`), khi đăng nhập bằng tài khoản Head Trainer, hệ thống chỉ hiển thị danh sách các con ngựa mà Head Trainer đó được phân công quản lý, thay vì hiển thị toàn bộ 52 con ngựa của câu lạc bộ.

### R3. Giới hạn chọn Groom và Training Plan khi Head Trainer tạo Session huấn luyện
- Khi Head Trainer tạo buổi huấn luyện (training session) trong trang Schedule / Training Plan:
  - Dropdown/danh sách Groom chỉ hiển thị và cho phép chọn các Groom đang do chính Head Trainer đó trực tiếp quản lý (nếu Head Trainer chưa được gán Groom nào, không thể chọn bất kỳ Groom nào khác trong CLB).
  - Dropdown/danh sách Training Plan chỉ hiển thị và cho phép đặt lịch huấn luyện cho những con ngựa mà Head Trainer đó được phân công quản lý; chặn việc xem hoặc chọn plan của các con ngựa không thuộc quyền quản lý.

### R4. Hệ thống thông báo nội bộ (in-app notification) hai chiều khi phân công
- Khi Club Manager phân công Groom cho một Head Trainer:
  - Hệ thống tự động tạo thông báo nội bộ cho Groom: thông báo Groom đã được giao cho Head Trainer cụ thể nào quản lý.
  - Hệ thống tự động tạo thông báo nội bộ cho Head Trainer: thông báo Head Trainer đã tiếp nhận Groom cụ thể nào vào đội ngũ.
- Khi đăng ký / phân công Ngựa cho một Head Trainer:
  - Hệ thống tự động tạo thông báo nội bộ cho Head Trainer: thông báo Head Trainer được giao quản lý con ngựa nào.
  - Hệ thống tự động tạo thông báo nội bộ cho Horse Owner: thông báo con ngựa cụ thể của chủ sở hữu đã được bàn giao cho Head Trainer nào quản lý.

### R5. Bổ sung kiểm thử tự động và đảm bảo tính toàn vẹn hệ thống
- Viết bổ sung các bài kiểm thử tự động (unit test, integration test) trong `backend/src/test/java/racehorse/backend/BusinessRulesTest.java` (hoặc các lớp test liên quan) bao phủ đầy đủ các kịch bản:
  - Club Manager phân công Groom cho Head Trainer (thành công khi <= 3 Groom, lỗi khi gán Groom thứ 4).
  - Head Trainer chỉ truy xuất được danh sách ngựa được phân công.
  - Head Trainer tạo session chỉ chọn được Groom và Training Plan của ngựa do mình quản lý.
  - Cơ chế tự động tạo thông báo hai chiều cho cả 2 luồng phân công Groom và phân công Ngựa.
- Đảm bảo toàn bộ backend và frontend biên dịch sạch sẽ, không có lỗi cú pháp hoặc lỗi hồi quy.

## Acceptance Criteria

### Staff & Groom Assignment
- [ ] Club Manager thực hiện phân công Head Trainer cho Groom thành công trên giao diện StaffPage; dữ liệu liên kết giữa Groom và Head Trainer được lưu trữ chính xác ở database.
- [ ] Giới hạn tối đa 3 Groom cho 1 Head Trainer được kiểm soát chặt chẽ ở backend (trả về lỗi phù hợp khi vi phạm) và phản hồi cảnh báo rõ ràng trên frontend.

### Horse List Scoping
- [ ] Khi đăng nhập tài khoản Head Trainer, trang danh sách ngựa (`HorseListPage.js` / API `/api/horses`) chỉ trả về và hiển thị danh sách các con ngựa mà Head Trainer đó được phân công quản lý.

### Training Session Creation Scoping
- [ ] Khi Head Trainer tạo session huấn luyện, danh sách lựa chọn Groom chỉ chứa các Groom thuộc quyền quản lý của Head Trainer đó.
- [ ] Khi Head Trainer tạo session huấn luyện, danh sách lựa chọn Training Plan chỉ chứa các plan thuộc về những con ngựa mà Head Trainer đó quản lý. Không thể tạo session cho ngựa của trainer khác.

### Two-Way In-App Notifications
- [ ] Khi phân công Groom cho Head Trainer thành công, bản ghi thông báo nội bộ được tạo cho Groom (nội dung thể hiện rõ Head Trainer tiếp nhận) và cho Head Trainer (nội dung thể hiện rõ Groom được phân công).
- [ ] Khi phân công Ngựa cho Head Trainer thành công, bản ghi thông báo nội bộ được tạo cho Head Trainer (nội dung thể hiện rõ ngựa được giao) và cho Horse Owner (nội dung thể hiện rõ ngựa của họ được giao cho Head Trainer nào).

### Automated Testing & Build
- [ ] Tất cả các test cases mới và hiện có trong `backend/src/test/java/racehorse/backend/BusinessRulesTest.java` chạy thành công (exit code 0 khi chạy `./mvnw test` hoặc `mvn test`).
- [ ] Frontend biên dịch sạch sẽ không có lỗi syntax hay build error.
