// src/features/horse-owner/horseOwnerApi.js
// Màn tổng quan của Horse Owner: một lời gọi trả cả màn
// (OwnerDashboardController ở backend). ownerId lấy từ token nên không gửi lên.

import { authFetch } from "../auth/authService";

const API = "/api/owner/dashboard";

/** period: một trong các key của COST_PERIODS. */
// Kết quả là record OwnerDashboard (dto/OwnerDashboard.java): thẻ từng ngựa (thông tin, khoá tập, điều trị,
// km 7 ngày, giải sắp tới, biểu đồ thể lực, 5 chỉ số), bảng chi phí theo hạng mục × ngựa trong khoảng period.
export function getOwnerDashboard(period, signal) {
  return authFetch(`${API}?period=${encodeURIComponent(period)}`, { signal });
}

/* ------------------------------------------------------------------ *
 * Từ vựng hiển thị
 * ------------------------------------------------------------------ */

/**
 * Năm chỉ số huấn luyện, theo đúng thứ tự hiện trên màn. key khớp tên trường
 * trong OwnerDashboard.Stats; cách tính nằm ở OwnerDashboardService.loadStats,
 * hint chỉ tóm tắt lại để người xem biết con số đến từ đâu.
 */
// icon: ảnh tĩnh trong frontend/public/icons/stats/ (đường dẫn bắt đầu bằng "/" = thư mục public).
export const TRAINING_STATS = [
  { key: "speed",   label: "Speed",   icon: "/icons/stats/speed.png",   hint: "Average peak speed" },
  { key: "stamina", label: "Stamina", icon: "/icons/stats/stamina.png", hint: "Heart-rate recovery" },
  { key: "power",   label: "Power",   icon: "/icons/stats/power.png",   hint: "Distance covered against the plan" },
  { key: "guts",    label: "Guts",    icon: "/icons/stats/guts.png",    hint: "Sessions finished without stopping early" },
  { key: "wit",     label: "Wit",     icon: "/icons/stats/wit.png",     hint: "Head trainer's evaluations" },
];

export const COST_PERIODS = {
  LAST_90_DAYS: "Last 90 days",
  THIS_YEAR: "This year",
  ALL: "All time",
};

export const DEFAULT_COST_PERIOD = "LAST_90_DAYS";

// PRIZE_MONEY không phải hạng mục của EXPENSE_LOG: backend thêm dòng tiền thưởng (RACE_REGISTRATION) vào
// cùng bảng để chủ ngựa thấy cả khoản chi lẫn khoản thu.
const COST_LABELS = {
  FEEDING: "Feeding and stabling",
  MEDICAL: "Veterinary",
  TRAINING: "Training",
  OTHER: "Other",
  PRIZE_MONEY: "Prize money paid to you",
};

/** #9: each cost line of the owner's own horses (voided lines excluded). */
export function listOwnerExpenses({ horseId, from, to, limit = 100 } = {}, signal) {
  // GET /api/owner/expenses (OwnerExpenseController): chỉ ngựa của chủ đang đăng nhập. Mỗi dòng có trạng thái
  // BILLED / DISPUTED; chủ khiếu nại một dòng bằng PUT /api/owner/expenses/{id}/dispute (V30).
  const p = new URLSearchParams();
  if (horseId) p.set("horseId", horseId);
  if (from) p.set("from", from);
  if (to) p.set("to", to);
  p.set("limit", limit);
  return authFetch(`/api/owner/expenses?${p.toString()}`, { signal });
}

// V57: trả online các khoản tính riêng ngoài tiền tháng (y tế, lệ phí giải, phí khác).
// GET: khoản đang phải trả + lần thanh toán đang chờ + các lần đã trả. POST: mở link payOS.
// POST /sync: hỏi lại payOS (khi quay về từ trang thanh toán).
export function getCostPayments(signal) {
  return authFetch("/api/owner/payments", { signal });
}

export function startCostPayment() {
  return authFetch("/api/owner/payments", { method: "POST" });
}

export function syncCostPayments() {
  return authFetch("/api/owner/payments/sync", { method: "POST" });
}

/**
 * V57: feed and training are part of the monthly boarding fee (the contract). Old cost lines of
 * these categories stay on record but are shown as included, never added to what the owner owes.
 */
export const INCLUDED_IN_FEE = new Set(["FEEDING", "TRAINING"]);

export function isIncludedInFee(category) {
  return INCLUDED_IN_FEE.has(category);
}

export function toCostLabel(category) {
  return COST_LABELS[category] || category;
}

export function isPrizeRow(category) {
  return category === "PRIZE_MONEY";
}

const EVALUATION_TEXT = {
  EXCELLENT: "Excellent",
  GOOD: "Good",
  FAIR: "Fair",
  POOR: "Poor",
};

export function toEvaluation(e) {
  return EVALUATION_TEXT[e] || "—";
}

/* ------------------------------------------------------------------ *
 * Định dạng
 * ------------------------------------------------------------------ */

// Tiền VND đầy đủ (không rút gọn): 1500000 -> "₫1,500,000". Tạo formatter một lần để dùng lại.
const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
});

/** 0 hiện "—" để bảng dễ đọc; tiền thưởng có dấu + đằng trước. */
export function formatMoney(amount, { prize = false } = {}) {
  const n = Number(amount);
  if (!Number.isFinite(n) || n === 0) return "—";
  return `${prize ? "+" : ""}${money.format(n)}`;
}

/** "22 Nov" cho ô số liệu nhỏ. */
export function formatShortDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
}

export function formatKm(km) {
  const n = Number(km);
  return Number.isFinite(n) ? `${n.toFixed(1)} km` : "—";
}

/** Số sao 0-5 từ trung bình các chỉ số đã có; null nếu chưa có chỉ số nào. */
export function toStars(stats) {
  // Ví dụ: trung bình 73 điểm -> 73 / 20 = 3.65 -> làm tròn thành 4 sao.
  const values = TRAINING_STATS.map((s) => stats?.[s.key]).filter((v) => v != null);
  if (values.length === 0) return null;
  const avg = values.reduce((a, b) => a + b, 0) / values.length;
  return Math.round(avg / 20);
}
