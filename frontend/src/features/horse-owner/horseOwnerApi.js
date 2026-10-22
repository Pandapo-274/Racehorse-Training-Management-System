// src/features/horse-owner/horseOwnerApi.js
// Màn tổng quan của Horse Owner: một lời gọi trả cả màn
// (OwnerDashboardController ở backend). ownerId lấy từ token nên không gửi lên.

import { authFetch } from "../auth/authService";

const API = "/api/owner/dashboard";

/** period: một trong các key của COST_PERIODS. */
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

const COST_LABELS = {
  FEEDING: "Feeding and stabling",
  MEDICAL: "Veterinary",
  TRAINING: "Training",
  OTHER: "Other",
  PRIZE_MONEY: "Race prize money",
};

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
  const values = TRAINING_STATS.map((s) => stats?.[s.key]).filter((v) => v != null);
  if (values.length === 0) return null;
  const avg = values.reduce((a, b) => a + b, 0) / values.length;
  return Math.round(avg / 20);
}
