// src/features/vet/vetApi.js
// Flow 3 phía bác sĩ thú y: UC14 (khám), UC15 (ghim chấn thương), UC16 (khoá tập),
// UC18 (xác nhận hồi phục).
//
// Mọi lời gọi đi qua authFetch nên tự có Bearer token; lỗi HTTP thành ApiError
// có .status và .fieldErrors. Backend lấy vetId / issuedBy từ token, nên không
// có trường nào trong số đó ở body.

import { authFetch } from "../auth/authService";
import { listHorses, getHorse } from "../horse/horseApi";

export { listHorses, getHorse };

const post = (url, body) =>
  authFetch(url, { method: "POST", body: JSON.stringify(body) });

/* ------------------------------------------------------------------ *
 * Endpoint
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * Báo cáo sự cố (hàng chờ khám)
 * ------------------------------------------------------------------ */

/**
 * GET /api/incident-reports. status mặc định OPEN = PENDING + RECEIVED, khẩn
 * cấp lên trước. Báo cáo sinh ra khi Groom / Head Trainer dừng buổi tập vì
 * ngựa bất thường.
 */
export function listIncidentReports(signal, status = "OPEN") {
  return authFetch(`/api/incident-reports?status=${status}`, { signal });
}

export function getIncidentReport(reportId, signal) {
  return authFetch(`/api/incident-reports/${reportId}`, { signal });
}

/** PUT /api/incident-reports/{id}/receive: bác sĩ nhận ca (PENDING -> RECEIVED). */
export function receiveIncidentReport(reportId) {
  return authFetch(`/api/incident-reports/${reportId}/receive`, { method: "PUT" });
}

/** Mức khẩn cấp của báo cáo. cls là lớp CSS của chip trong vet.css. */
const URGENCY = {
  HIGH: { label: "Urgent", cls: "urgent" },
  MEDIUM: { label: "Soon", cls: "soon" },
  LOW: { label: "Routine", cls: "normal" },
};

export function toUrgency(u) {
  return URGENCY[u] || { label: u || "—", cls: "normal" };
}

/** "27 Oct, 09:45" theo giờ máy. reportedAt là Instant (có Z) nên new Date đọc đúng. */
export function formatReportTime(iso) {
  const d = iso ? new Date(iso) : null;
  if (!d || Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

/** GET /api/medical-records?horseId= - mới nhất lên trước. */
export function listMedicalRecords(horseId, signal) {
  return authFetch(`/api/medical-records?horseId=${horseId}`, { signal });
}

/** POST /api/medical-records (chỉ VETERINARIAN). */
export function createMedicalRecord(body) {
  return post("/api/medical-records", body);
}

/** GET /api/injuries?horseId= - mọi dấu, kể cả đã lành. */
export function listInjuries(horseId, signal) {
  return authFetch(`/api/injuries?horseId=${horseId}`, { signal });
}

/** POST /api/injuries (chỉ VETERINARIAN). recordId phải là hồ sơ đã injuryConfirmed. */
export function createInjury(body) {
  return post("/api/injuries", body);
}

/** GET /api/training-locks?horseId= - bệnh sử khoá tập, kể cả lệnh đã mở. */
export function listLocksForHorse(horseId, signal) {
  return authFetch(`/api/training-locks?horseId=${horseId}`, { signal });
}

/** POST /api/training-locks (VETERINARIAN, HEAD_TRAINER, CLUB_MANAGER). */
export function issueLock(body) {
  return post("/api/training-locks", body);
}

/**
 * POST /api/training-locks/{id}/release (VETERINARIAN, CLUB_MANAGER).
 * Backend từ chối nếu lệnh dựa trên chấn thương chưa lành: lúc đó phải dùng
 * confirmRecovery.
 */
export function releaseLock(lockId) {
  return authFetch(`/api/training-locks/${lockId}/release`, { method: "POST" });
}

/** POST /api/recovery-confirmations (chỉ VETERINARIAN): khép chấn thương + mở khoá + ELIGIBLE. */
export function confirmRecovery(body) {
  return post("/api/recovery-confirmations", body);
}

/* ------------------------------------------------------------------ *
 * Từ vựng hiển thị
 * ------------------------------------------------------------------ */

const BOARD_STATUS = {
  ELIGIBLE: "fit",
  MONITOR: "monitor",
  INJURED: "injured",
  QUARANTINE: "quarantine",
};

const BOARD_LABEL = {
  fit: "Healthy",
  monitor: "Monitor",
  injured: "Injured",
  quarantine: "Quarantine",
};

/** HORSE.status -> lớp màu của sơ đồ chuồng (fit / monitor / injured / quarantine). */
export function toBoardStatus(status) {
  return BOARD_STATUS[status] || "monitor";
}

export function statusLabel(status) {
  return BOARD_LABEL[toBoardStatus(status)];
}

export const LOCK_LEVELS = [
  { value: "FULL", label: "Full lock", hint: "No training at all" },
  { value: "HEAVY_WORK_ONLY", label: "Heavy work only", hint: "Light work still allowed" },
];

export function lockLevelLabel(level) {
  return LOCK_LEVELS.find((l) => l.value === level)?.label || level || "—";
}

export const SEVERITIES = [
  { value: "GRADE_1", label: "Grade 1" },
  { value: "GRADE_2", label: "Grade 2" },
  { value: "GRADE_3", label: "Grade 3" },
];

/* ------------------------------------------------------------------ *
 * Ngày giờ
 * ------------------------------------------------------------------ */

const pad = (n) => String(n).padStart(2, "0");

/** yyyy-MM-dd theo giờ máy - định dạng của <input type="date">. */
export function todayInputValue(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function localDateTime(d) {
  return `${todayInputValue(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/**
 * Backend đòi examDate @PastOrPresent và nhận LocalDateTime (không múi giờ).
 * Lùi một phút để lệch đồng hồ nhỏ giữa trình duyệt và server không làm
 * lần khám bị từ chối vì "ở tương lai".
 */
export function nowForApi() {
  return localDateTime(new Date(Date.now() - 60 * 1000));
}

/** Ngày chọn trên form -> examDate. Hôm nay thì lấy giờ hiện tại, ngày khác thì 12:00. */
export function examTimestamp(dateValue) {
  if (!dateValue || dateValue === todayInputValue()) return nowForApi();
  return `${dateValue}T12:00:00`;
}

/** "13 Sep" */
export function formatShortDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
}

export function daysSince(iso) {
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? null : Math.floor((Date.now() - t) / 86400000);
}

/* ------------------------------------------------------------------ *
 * Lỗi
 * ------------------------------------------------------------------ */

/** ApiError -> { message, details[] } để hiện một khối lỗi duy nhất. */
export function describeError(err) {
  const message = err?.message || "Something went wrong";
  const details = Object.values(err?.fieldErrors || {}).filter(
    (m) => m && m !== message
  );
  return { message, details };
}