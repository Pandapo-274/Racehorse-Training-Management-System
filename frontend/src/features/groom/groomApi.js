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
  return authFetch(`/api/sessions/${sessionId}/vitals`, {
    method: "POST",
    body: JSON.stringify({ heartRate, velocity }),
  });
}

/* ------------------------------------------------------------------ *
 * Từ vựng hiển thị
 * ------------------------------------------------------------------ */

/** slug là lớp CSS của chip trạng thái trong groom.css. */
const SESSION_STATUS = {
  SCHEDULED:     { label: "Scheduled",     slug: "queued" },
  RUNNING:       { label: "Running",       slug: "in-progress" },
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
  const d = new Date(`${sessionDay(iso)}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short" });
}
