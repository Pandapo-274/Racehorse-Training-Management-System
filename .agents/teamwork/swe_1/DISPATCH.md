# DISPATCH Log

## 2026-11-07T16:56:11Z
This is a single self-contained fix; keep it small and focused. Kiểm tra toàn bộ 4 nghiệp vụ luồng huấn luyện (training flow) vừa được bổ sung, khắc phục các sai sót mã nguồn nếu có và hoàn thiện bộ kiểm thử tự động để đảm bảo hệ thống hoạt động chính xác theo đúng yêu cầu nghiệp vụ.

Working directory: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System
Integrity mode: demo
The team must NOT: use pre-built libraries or frameworks for core functionality

## Verification Resources
- Database migration: `backend/src/main/resources/migration/V37__training_flow_enhancements.sql`
- Test suite: `backend/src/test/java/racehorse/backend/BusinessRulesTest.java`
- Backend modules:
  - `backend/src/main/java/racehorse/backend/staff/` (StaffRow, StaffRepository, StaffService, StaffController)
  - `backend/src/main/java/racehorse/backend/horse/` (HorseResponse, HorseIdentityRequest, HorseRepository, HorseService, HorseController)
  - `backend/src/main/java/racehorse/backend/horserequest/` (ReviewRequest, HorseRequestService)
  - `backend/src/main/java/racehorse/backend/training/` (TrainingPlanService, FinishSessionRequest, TrainingSessionRequest, TrainingSessionScheduleRequest, TrainingSessionResponse, TrainingSessionRepository, TrainingSessionService, TrainingSessionController)
  - `backend/src/main/java/racehorse/backend/incident/` (IncidentRepository, IncidentService)
  - `backend/src/main/java/racehorse/backend/session/` (SessionResponse, SessionRepository, SessionService)
  - `backend/src/main/java/racehorse/backend/care/MissedTaskJob.java`
- Frontend components:
  - `frontend/src/features/manager/staffApi.js`, `StaffPage.js`
  - `frontend/src/features/horse/horseApi.js`, `horseRequestApi.js`, `HorseDetailPage.js`, `HorseReviewPage.js`
  - `frontend/src/features/trainer/TrainingPlanPage.js`, `SchedulePage.js`, `SessionEvaluationPage.js`
  - `frontend/src/features/groom/groomApi.js`, `GroomDashboard.js`
  - `frontend/src/features/horse-owner/OwnerTrainingPage.js`

## Requirements

### R1. Rà soát và kiểm tra nghiệp vụ giới hạn quản lý Groom của Head Trainer
- 1 tài khoản Head Trainer chỉ được quản lý tối đa 3 Groom tại một thời điểm.
- Việc đăng ký và phê duyệt phân công Groom cho Head Trainer phải do Club Manager thực hiện; kiểm tra và chặn các thao tác tự phân công vượt quá số lượng cho phép.

### R2. Rà soát và kiểm tra nghiệp vụ thời hạn Session Review và tự động gửi báo cáo
- Session Review có thời hạn tối đa 2 ngày tính từ thời điểm buổi tập (session) kết thúc.
- Nếu quá hạn 2 ngày mà Head Trainer chưa gửi review, hệ thống phải tự động tổng hợp 1 bản báo cáo sơ bộ về các chỉ số của ngựa (nhịp tim, tốc độ) do Groom ghi nhận trong buổi tập, kèm cảnh báo rõ ràng đây là review tự động không phải từ Head Trainer.
- Bản review tự động phải được chuyển vào khu lưu trữ chờ Head Trainer xem xét lại trước khi thông báo tới Horse Owner.

### R3. Rà soát và kiểm tra nghiệp vụ thời lượng Session và điều kiện kết thúc sớm
- Khi tạo buổi tập, bắt buộc có thời lượng (duration) với mức tối đa 12 tiếng và thời gian kết thúc không được vượt qua ngày hôm sau tính từ giờ bắt đầu.
- Nếu quá thời gian kết thúc dự kiến mà Groom chưa cập nhật trạng thái buổi tập, hệ thống tự động kết thúc session và gửi thông báo yêu cầu session review về Head Trainer.
- Groom được phép kết thúc session sớm hơn thời gian đặt với các điều kiện:
  - Phải hiển thị hộp thoại xác nhận khi chọn kết thúc sớm.
  - Phải có danh sách lý do kết thúc sớm để Groom lựa chọn (ví dụ: đạt thể trạng yêu cầu, chấn thương,...).
  - Có nút gọi bác sĩ thú y (Vet) nếu ngựa gặp chấn thương.
  - Chỉ cho phép kết thúc sớm nếu Groom đã ghi nhận cả hai chỉ số nhịp tim (heart rate) và tốc độ (speed) ít nhất 1 lần trong suốt buổi tập.

### R4. Rà soát và kiểm tra nghiệp vụ đặt lịch Training và hạn ngạch quản lý ngựa
- 1 ngựa không được phép có 2 session trong cùng 1 ngày và cùng khung giờ trùng lặp.
- Session chỉ được đặt vào ngày hiện tại hoặc tương lai (không cho phép đặt lịch về quá khứ).
- 1 Groom chỉ được chỉ định huấn luyện 1 ngựa tại một thời điểm nhất định và không đang thực hiện bất kỳ session nào khác.
- Head Trainer chỉ được quản lý số lượng ngựa tối đa theo hạn ngạch do Club Manager quyết định và điều phối.

### R5. Khắc phục lỗi mã nguồn và bổ sung kiểm thử tự động
- Nếu phát hiện bất kỳ lỗi logic, thiếu sót điều kiện hoặc chưa đồng bộ giữa backend và frontend đối với 4 nghiệp vụ trên, tiến hành sửa chữa mã nguồn triệt để.
- Bổ sung các bài kiểm thử tự động (unit/integration test) vào `BusinessRulesTest.java` (và các lớp test liên quan) bao phủ toàn bộ các kịch bản hợp lệ và kịch bản vi phạm (boundary/edge cases) của 4 nghiệp vụ.

## Acceptance Criteria

### Automated Testing & Code Verification
- [ ] Tất cả các test cases trong `backend/src/test/java/racehorse/backend/BusinessRulesTest.java` chạy thành công (exit code 0 khi chạy bằng `./mvnw test` hoặc `mvn test`).
- [ ] Có bài test tự động xác minh: Club Manager đăng ký Groom cho Head Trainer thành công khi <= 3 Groom, và ném lỗi từ chối khi gán Groom thứ 4 cho 1 Head Trainer.
- [ ] Có bài test tự động xác minh: Session kết thúc quá 2 ngày chưa có review từ Head Trainer sẽ được xử lý tự động (tạo bản báo cáo sơ bộ có gắn nhãn/cảnh báo tự động, tổng hợp đủ heart rate & speed, lưu trữ chờ Head Trainer duyệt lại).
- [ ] Có bài test tự động xác minh: Buổi tập có duration > 12h hoặc kết thúc sang ngày hôm sau bị từ chối khi tạo; buổi tập quá giờ tự động kết thúc; thao tác kết thúc sớm bị từ chối nếu thiếu 1 trong 2 chỉ số heart rate hoặc speed, và chấp nhận khi đã có cả 2 chỉ số cùng lý do hợp lệ.
- [ ] Có bài test tự động xác minh: Chặn đặt 2 session trùng khung giờ trong ngày cho cùng 1 ngựa; chặn đặt session trong quá khứ; chặn gán Groom đang bận session khác; chặn gán ngựa vượt quá quota của Head Trainer do Club Manager quy định.
- [ ] Mã nguồn backend và giao diện frontend tương ứng hoạt động nhất quán, biên dịch sạch sẽ không có lỗi biên dịch hoặc lỗi cú pháp.
