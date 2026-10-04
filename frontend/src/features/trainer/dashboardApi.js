// src/features/trainer/dashboardApi.js
// Nối màn Head Trainer (UC6) với backend.
//
// Giao diện trong TrainerDashboard.js do nhóm dựng theo Figma và có từ vựng
// hiển thị riêng (form S/A/B/C, trạng thái Active / Monitor / Locked /
// Ready to plan). Backend lại trả về từ vựng của cơ sở dữ liệu
// (EXCELLENT/GOOD/FAIR/POOR, ELIGIBLE/MONITOR/INJURED/QUARANTINE).
//
// Toàn bộ phép quy đổi giữa hai từ vựng nằm ở đây, không rải trong JSX - đổi
// cách hiển thị thì sửa một chỗ, và component chỉ việc render.

import { authFetch } from "../auth/authService";

const API = "/api/trainer/dashboard";

/**
 * Gọi API (có gắn Bearer token). weeks = số tuần hiển thị trên biểu đồ.
 * Lỗi HTTP -> ApiError (có .status); màn hình xử lý 401/403 bằng handleAuthError.
 */
export function fetchDashboard(weeks = 8, signal) {
  return authFetch(`${API}?weeks=${weeks}`, { signal });
}

/* ------------------------------------------------------------------ *
 * Quy đổi sang từ vựng hiển thị của giao diện
 * ------------------------------------------------------------------ */

const FORM_BY_EVALUATION = {
  EXCELLENT: "S",
  GOOD: "A",
  FAIR: "B",
  POOR: "C",
};

/** Huy hiệu phong độ. Trả null khi ngựa chưa có buổi tập nào được đánh giá. */
export function toFormBadge(evaluation) {
  return FORM_BY_EVALUATION[evaluation] || null;
}

/**
 * Chip trạng thái. Thứ tự kiểm tra quyết định ý nghĩa, nên nó cố ý xếp theo
 * mức độ cần chú ý giảm dần:
 *
 *   1. khoá huấn luyện - nặng nhất, chặn mọi việc xếp lịch
 *   2. sức khoẻ khác ELIGIBLE - cần theo dõi, cách ly, chấn thương
 *   3. chưa có giáo án - việc cần làm, nhưng con ngựa vẫn khoẻ
 *   4. còn lại - đang chạy giáo án bình thường
 *
 * Sức khoẻ phải đứng trước "chưa có giáo án": một con đang cách ly mà chưa lập
 * giáo án thì không phải "sẵn sàng lập giáo án", nó đang bị cách ly.
 *
 * CSS của nhóm chỉ định nghĩa 4 lớp: ready-to-plan, active, locked, monitor.
 * INJURED và QUARANTINE vì vậy gộp vào "Monitor" thay vì tự tạo lớp mới -
 * thêm lớp sẽ phải sửa trainer.css và dễ đụng với người khác.
 */
export function toStatusChip(horse) {
  if (horse.locked) return { label: "Locked", cls: "locked" };
  if (horse.status !== "ELIGIBLE") return { label: "Monitor", cls: "monitor" };
  if (!horse.currentPhase) return { label: "Ready to plan", cls: "ready-to-plan" };
  return { label: "Active", cls: "active" };
}

const METRIC_TEXT = {
  HEART_RATE: "Heart rate",
  VELOCITY: "Speed",
  WORKLOAD: "Workload",
};

/** "Heart rate 194, ceiling 180" - câu mô tả ngắn cho thẻ cảnh báo. */
export function toAlertMessage(alert) {
  const metric = METRIC_TEXT[alert.metricType] || alert.metricType;
  return `${metric} ${Number(alert.actualValue)}, ceiling ${Number(alert.thresholdValue)}`;
}

/** Severity từ backend là HIGH/MEDIUM/LOW; CSS dùng chữ thường, nhãn viết hoa đầu. */
export function toSeverity(severity) {
  const lower = String(severity || "").toLowerCase();
  return { label: lower.charAt(0).toUpperCase() + lower.slice(1), cls: lower };
}

/** Cự ly tuần: 0 km hiển thị thành gạch ngang cho đỡ rối mắt. */
export function toDistance(km) {
  const n = Number(km);
  return n > 0 ? `${n.toFixed(1)} km` : "—";
}

/** Biểu đồ của nhóm nhận {date, value}; API trả {label, fitnessIndex}. */
export function toChartData(fitnessTrend) {
  return (fitnessTrend || []).map((p) => ({
    date: p.label,
    value: p.fitnessIndex,
    sessions: p.sessionCount,
  }));
}

/** Chữ viết tắt cho vòng tròn avatar: "Kenta Morishita" -> "KM". */
export function toInitials(fullName) {
  if (!fullName) return "??";
  return fullName
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();
}
