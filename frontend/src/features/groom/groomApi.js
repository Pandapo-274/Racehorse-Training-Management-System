// src/features/groom/groomApi.js
// Phần việc của Groom trong Flow 2: nhận buổi tập Head Trainer giao, bắt đầu
// buổi tập, và gửi số đo trong lúc ngựa chạy để hệ thống sinh cảnh báo (UC11).

import { authFetch } from "../auth/authService";

/** Backend tự lọc: Groom chỉ nhận buổi tập giao cho chính mình. */
export function listMySessions(signal) {
  return authFetch("/api/training-sessions", { signal });
}

/** SCHEDULED -> RUNNING. Backend chặn nếu ngựa bị khoá tập hoặc giáo án không còn ACTIVE. */
export function startSession(sessionId) {
  return authFetch(`/api/training-sessions/${sessionId}/start`, { method: "PUT" });
}

/**
 * Một lần đo khi buổi tập đang RUNNING. Trả về VitalsResult:
 * { heartRate, heartRateCeiling, alertRaised, severity, alertId }.
 */
export function sendVitals(sessionId, { heartRate, velocity }) {
  // POST /api/sessions/{id}/vitals (AlertController -> AlertService): so heartRate với trần nhịp tim của giai
  // đoạn giáo án, velocity với app.alert.velocity-max (18 m/s); vượt thì tạo THRESHOLD_ALERT và báo Head Trainer.
  return authFetch(`/api/sessions/${sessionId}/vitals`, {
    method: "POST",
    body: JSON.stringify({ heartRate, velocity }),
  });
}

/**
 * PUT /api/training-sessions/{id}/finish: buổi tập kết thúc bình thường hoặc kết thúc sớm.
 * Nếu body = { earlyStop: true, earlyStopReason } thì yêu cầu đã báo cáo cả HR & Speed >= 1 lần.
 */
export function finishSession(sessionId, body = null) {
  // Không có body -> kết thúc bình thường (COMPLETED). Có earlyStop/earlyStopReason -> STOPPED_EARLY.
  // Lưu ý: backend cũng coi là kết thúc sớm nếu bấm Finish trước giờ kết thúc dự kiến, dù không gửi cờ.
  const options = { method: "PUT" };
  if (body) {
    options.body = JSON.stringify(body);
  }
  return authFetch(`/api/training-sessions/${sessionId}/finish`, options);
}

/** V50: RUNNING -> RECOVERY: bắt đầu đếm ngược thời gian hồi phục của Phase. */
export function enterRecoverySession(sessionId) {
  return authFetch(`/api/training-sessions/${sessionId}/enter-recovery`, { method: "PUT" });
}

/** V50: RECOVERY -> COMPLETED: Groom nhập actualHrr (bpm) và hoàn tất buổi tập. */
export function completeRecoverySession(sessionId, { actualHrr, remarks }) {
  return authFetch(`/api/training-sessions/${sessionId}/complete-recovery`, {
    method: "PUT",
    body: JSON.stringify({
      actualHrr: Number(actualHrr),
      remarks: remarks?.trim() || null,
    }),
  });
}

/**
 * PUT /api/training-sessions/{id}/stop: ngựa có dấu hiệu bất thường / chấn thương giữa buổi.
 * Buổi chuyển STOPPED_EARLY và backend mở báo cáo sự cố cho bác sĩ thú y; tới
 * khi bác sĩ khám xong, con ngựa không được bắt đầu buổi tập nào nữa.
 * Trả về { sessionId, sessionStatus, report }.
 */
export function stopSession(sessionId, { symptoms, description, urgency }) {
  return authFetch(`/api/training-sessions/${sessionId}/stop`, {
    method: "PUT",
    body: JSON.stringify({ symptoms, description: description || null, urgency }),
  });
}

/** Danh sách lý do kết thúc sớm buổi tập cho Groom chọn. */
// value phải nằm trong EARLY_STOP_REASONS của TrainingSessionService (backend từ chối mã lạ).
export const EARLY_STOP_REASON_OPTIONS = [
  { value: "TARGET_REACHED", label: "Target fitness reached" },
  { value: "HORSE_FATIGUED", label: "Horse fatigued" },
  { value: "WEATHER_CONDITION", label: "Weather / track condition" },
  { value: "EQUIPMENT_ISSUE", label: "Equipment issue" },
  { value: "INJURY", label: "Horse injury / abnormality — Call Vet" },
  { value: "NO_HEAD_TRAINER", label: "No Head Trainer to supervise (asked by the club)" },
];

/** Triệu chứng hay gặp, để Groom chọn nhanh; vẫn sửa tay được. */
export const COMMON_SYMPTOMS = [
  "Limping / lame",
  "Laboured breathing",
  "Refusing to run",
  "Signs of colic (pain in the belly)",
  "Bleeding from the nose",
  "Swelling on a leg",
];

// Mức khẩn cấp lưu vào báo cáo sự cố (INCIDENT_REPORT) mà backend mở khi Groom bấm Stop.
export const URGENCY_OPTIONS = [
  { value: "HIGH", label: "Urgent – vet should come now" },
  { value: "MEDIUM", label: "Soon – today" },
  { value: "LOW", label: "Routine – when available" },
];

/* ------------------------------------------------------------------ *
 * Từ vựng hiển thị
 * ------------------------------------------------------------------ */

/** slug là lớp CSS của chip trạng thái trong groom.css. */
const SESSION_STATUS = {
  SCHEDULED:     { label: "Scheduled",     slug: "queued" },
  UNASSIGNED:    { label: "Unassigned",    slug: "stopped" },
  REST:          { label: "Rest day",      slug: "cancelled" },
  RUNNING:       { label: "Running",       slug: "in-progress" },
  RECOVERY:      { label: "Recovery",      slug: "in-progress" },
  COMPLETED:     { label: "Completed",     slug: "done" },
  STOPPED_EARLY: { label: "Stopped early", slug: "stopped" },
  CANCELLED:     { label: "Cancelled",     slug: "cancelled" },
};

export function toSessionStatus(status) {
  return SESSION_STATUS[status] || { label: status || "—", slug: "queued" };
}

/** Buổi đã kết thúc: không bắt đầu lại, không gửi số đo được nữa. */
export function isFinished(status) {
  return status === "COMPLETED" || status === "STOPPED_EARLY" || status === "CANCELLED";
}

const SEVERITY_TEXT = { HIGH: "High", MEDIUM: "Medium", LOW: "Low" };

export function toSeverityLabel(s) {
  return SEVERITY_TEXT[s] || s;
}

/* ------------------------------------------------------------------ *
 * Ngày giờ
 * ------------------------------------------------------------------ */

/** "2026-10-22" theo giờ máy, để so với phần ngày của sessionDate. */
// Groom chỉ được Start buổi tập trong đúng ngày của buổi (backend kiểm lại); so chuỗi yyyy-mm-dd là đủ.
export function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** sessionDate là LocalDateTime không múi giờ ("2026-10-22T06:30:00"): cắt chuỗi, không đổi múi. */
export function sessionDay(iso) {
  return String(iso || "").slice(0, 10);
}

export function sessionTime(iso) {
  return String(iso || "").slice(11, 16) || "—";
}

export function formatSessionDay(iso) {
  // Thêm "T00:00:00" (không có "Z") để Date hiểu là nửa đêm giờ ĐỊA PHƯƠNG, tránh hiện lùi một ngày.
  const d = new Date(`${sessionDay(iso)}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short" });
}
