# Tóm Tắt Học Nhanh Flow Training (Cheat-Sheet Tra Cứu Vị Trí Code)

> **Quy tắc chung của Backend (`racehorse.backend`):**
> - Nhận API: package `controller`
> - Xử lý nghiệp vụ / validate / gửi thông báo: package `service`
> - Truy vấn DB: package `repository`
> - Dữ liệu vào/ra: package `dto`
>
> **Frontend (`frontend/src/features/`):**
> - Trainer: `features/trainer/` (`TrainingPlanPage`, `SchedulePage`, `LiveSessionMonitorPage`, `SessionEvaluationPage`)
> - Groom: `features/groom/` (`groomApi.js`) | Chủ ngựa: `features/horse-owner/` (`ownerTrainingApi.js`)

---

## Tra Cứu Nhanh: "Hỏi Nghiệp Vụ Nào $\rightarrow$ Chỉ Ngay Ở Đó"

| Khi hội đồng hỏi về nghiệp vụ... | Backend Service (xử lý chính) | Controller / Job | Bảng DB chính |
| :--- | :--- | :--- | :--- |
| **Tạo / Sửa / Duyệt / Kích hoạt Giáo án** (`DRAFT` $\rightarrow$ `PENDING_APPROVAL` $\rightarrow$ `APPROVED` $\rightarrow$ `ACTIVE`) | `service.TrainingPlanService`<br/>(`create`, `changeStatus`, `ownerReview`) | `controller.TrainingPlanController`<br/>(`/api/training-plans`) | `TRAINING_PLAN`, `PLAN_PHASE` |
| **Sửa giáo án khi đang chạy (Pending Update & tự hủy sau 2 ngày)** | `service.TrainingPlanService`<br/>(`update`, `reviewPendingUpdate`, `expireOverduePendingUpdates`) | `controller.TrainingPlanController`<br/>(`PUT .../update-review`) & `service.MissedTaskJob` | `TRAINING_PLAN` |
| **Giới hạn 1 Groom tối đa 2 plan chưa xong** | `service.TrainingPlanService` $\rightarrow$ `repository.TrainingPlanRepository.countActivePlansByGroom()` | `controller.TrainingPlanController` | `TRAINING_PLAN` |
| **Xếp lịch / Đổi lịch / Hủy buổi tập & Check trùng lịch (1 ngựa 1 buổi/ngày, nghỉ 90p, quota tuần)** | `service.TrainingSessionService`<br/>(`create`, `reschedule`, `cancel`, `checkSlot`) | `controller.TrainingSessionController`<br/>(`/api/training-sessions`) | `TRAINING_SESSION` |
| **Groom bấm Start / Finish / Dừng sớm (`STOPPED_EARLY` bắt buộc lý do + HR & Speed)** | `service.TrainingSessionService`<br/>(`start`, `finish`) | `controller.TrainingSessionController`<br/>(`.../start`, `.../finish`) | `TRAINING_SESSION` |
| **Gửi nhịp tim / vận tốc lúc tập & Chống spam cảnh báo 60s** | `service.AlertService`<br/>(`recordVitals`, `raise`) | `controller.AlertController`<br/>(`POST /api/sessions/{id}/vitals`) | `THRESHOLD_ALERT`, `TRAINING_SESSION` |
| **Head Trainer xử lý Alert (Giảm tốc / Dừng khẩn cấp / Gọi Vet)** | `service.AlertService.resolve()` $\rightarrow$ `service.IncidentService.stopFromAlert()` | `controller.AlertController`<br/>(`PUT /api/alerts/{id}/resolve`) | `THRESHOLD_ALERT`, `INCIDENT_REPORT`, `VET_CALL` |
| **Khóa tập (`FULL` / `HEAVY_WORK_ONLY` $\le 1200\text{m}$) & Dừng buổi đang chạy** | `service.TrainingLockService.issue()` $\rightarrow$ `service.SessionStopper.stopRunningSessions()` | `controller.TrainingLockController` | `TRAINING_LOCK`, `TRAINING_SESSION` |
| **Chấm điểm / Đánh giá sau buổi tập** | `service.SessionService.evaluate()` | `controller.SessionController`<br/>(`PUT /api/sessions/{id}/evaluation`) | `TRAINING_SESSION` |
| **Gắn cờ chạy thử & Link Video (`http/https`)** | `service.TrialRunService.update()` | `controller.TrialRunController`<br/>(`PUT /api/sessions/{id}/trial-run`) | `TRAINING_SESSION` |
| **Tự động đóng plan hết hạn / auto-finish / báo cáo sơ bộ sau 48h (`ARCHIVED_AUTO`)** | `service.MissedTaskJob` gọi:<br/>• `TrainingPlanService.completeExpiredPlans()`<br/>• `TrainingSessionService.processOverdueSessions()`<br/>• `SessionService.processOverdueReviews()` | *(Chạy ngầm `@Scheduled` mỗi 60s & 00:15)* | `TRAINING_PLAN`, `TRAINING_SESSION` |

---

## Mẹo Nhớ 4 Cụm Tên Class (Chỉ Cần Thuộc 4 Từ Khóa)

1. **`TrainingPlan*`** (`Controller` / `Service` / `Repository`): Mọi thứ về **Giáo án & Phase** (tạo, duyệt, sửa pending 2 ngày, kích hoạt).
2. **`TrainingSession*`** (`Controller` / `Service` / `Repository`): Mọi thứ về **Lịch & Chạy buổi tập** (xếp lịch, check trùng slot, start, finish, dừng sớm).
3. **`Alert*` + `Incident*` + `TrainingLock*` + `SessionStopper`**: Mọi thứ về **Giám sát Live & An toàn** (nhịp tim, tốc độ, cảnh báo 60s, dừng khẩn cấp, khóa tập).
4. **`Session*` + `TrialRun*` + `MissedTaskJob`**: Mọi thứ về **Hậu kỳ & Tự động hóa** (chấm điểm, video chạy thử, tự động xử lý quá hạn 48h).
