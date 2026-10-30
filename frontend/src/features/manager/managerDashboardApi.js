// src/features/manager/managerDashboardApi.js
// Trang "Club overview" của Club Manager: GET /api/manager/dashboard.
//
// Một lời gọi trả về hết: số liệu tổng, khối lượng tập theo tuần, sức khoẻ
// đàn ngựa và các việc đang chờ. Mọi số đều đọc từ database.

import { authFetch } from "../auth/authService";

export function getManagerDashboard({ weeks = 8, signal } = {}) {
  return authFetch(`/api/manager/dashboard?weeks=${weeks}`, { signal });
}

/** "₫184M": số gọn cho thẻ thống kê. Cùng đơn vị VND với báo cáo của chủ ngựa và trang Races. */
const compactMoney = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "VND",
  notation: "compact",
  maximumFractionDigits: 1,
});

export function formatCompactMoney(amount) {
  const n = Number(amount);
  return Number.isFinite(n) ? compactMoney.format(n) : "—";
}

/**
 * Việc đang chờ -> trang xử lý của Club Manager. Backend chỉ trả kind, không
 * trả đường dẫn (cùng cách với thông báo). Không có trang thì dòng không bấm được.
 */
const OPEN_ITEM_LINK = {
  REGISTRATION: "/manager/horse-requests",
  WITHDRAWAL: "/manager/withdrawals",
  SUPPLY: "/manager/supplies",
};

export function openItemLink(kind) {
  return OPEN_ITEM_LINK[kind] || null;
}

/** Lớp màu của chip trạng thái: việc của Manager màu vàng, sự cố và cảnh báo màu đỏ. */
export function openItemTone(kind) {
  return kind === "INCIDENT" || kind === "ALERT" ? "alert" : "pending";
}

/** Tuần ISO (thứ Hai là đầu tuần), cho dòng "2026 season · Week 44". */
export function isoWeek(d = new Date()) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  return Math.ceil(((t - yearStart) / 86400000 + 1) / 7);
}
