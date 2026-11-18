// src/features/manager/expenseApi.js
// Chi phí nuôi ngựa (bảng EXPENSE_LOG). Club Manager ghi ở trang Costs; chủ
// ngựa xem bản tổng hợp của ngựa mình trên dashboard (cùng nhãn hạng mục,
// lấy từ horseOwnerApi để hai nơi gọi tên giống nhau).

import { authFetch } from "../auth/authService";

// Re-export: trang Costs import toCostLabel / formatMoney từ đây, nhưng định nghĩa thật nằm ở horseOwnerApi.
export { toCostLabel, formatMoney } from "../horse-owner/horseOwnerApi";

const API = "/api/expenses";

/** Bốn hạng mục, khớp CK_EXP_cat ở backend. */
export const EXPENSE_CATEGORIES = ["FEEDING", "MEDICAL", "TRAINING", "OTHER"];

/** Mọi tham số đều tuỳ chọn. from / to dạng yyyy-mm-dd, tính cả hai đầu. */
// GET /api/expenses?horseId=&category=&from=&to= (CLUB_MANAGER). Chỉ trả các dòng chưa huỷ
// (ExpenseRepository lọc "cancelled_at IS NULL").
export function listExpenses({ horseId, category, from, to, signal } = {}) {
  const qs = new URLSearchParams();
  if (horseId) qs.set("horseId", horseId);
  if (category) qs.set("category", category);
  if (from) qs.set("from", from);
  if (to) qs.set("to", to);
  const query = qs.toString();
  return authFetch(query ? `${API}?${query}` : API, { signal });
}

// POST /api/expenses: ghi một khoản chi tay. Number(...) đổi chuỗi từ ô nhập sang số trước khi gửi.
// Backend chặn: số tiền < 1, ngựa PEDIGREE, ngày sau khi ngựa đã rút, ngày trước khi ngựa sinh.
export function createExpense({ horseId, category, amount, expenseDate, note }) {
  return authFetch(API, {
    method: "POST",
    body: JSON.stringify({
      horseId: Number(horseId),
      category,
      amount: Number(amount),
      expenseDate,
      note: note?.trim() || null,
    }),
  });
}

/**
 * Voids a cost. The backend keeps the row (V19) with who voided it and why,
 * so the reason is required - it is the only explanation the ledger will have.
 */
export function deleteExpense(id, reason) {
  // DELETE thực chất là "huỷ mềm" (đặt cancelled_at). Lý do đi qua query string vì body của DELETE không
  // chắc được trình duyệt/proxy chuyển đi; encodeURIComponent mã hoá dấu cách, tiếng Việt, "&"...
  return authFetch(`${API}/${id}?reason=${encodeURIComponent(reason)}`, { method: "DELETE" });
}

/* ------------------------------------------------------------------ *
 * Khoảng thời gian của bộ lọc
 * ------------------------------------------------------------------ */

/** yyyy-mm-dd theo giờ máy, không qua UTC (toISOString lệch ngày ở múi +7). */
export function isoDate(d) {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export const EXPENSE_PERIODS = {
  THIS_QUARTER: "This quarter",
  LAST_90_DAYS: "Last 90 days",
  THIS_YEAR: "This year",
  ALL: "All time",
};

/** { from, to } của một khoảng; ALL trả về hai giá trị null. */
// Các mốc tính theo giờ máy người dùng: quý = tháng đầu quý (0, 3, 6, 9); 90 ngày gồm cả hôm nay (lùi 89).
export function periodRange(key, now = new Date()) {
  const to = isoDate(now);
  switch (key) {
    case "THIS_QUARTER": {
      const start = new Date(now.getFullYear(), Math.floor(now.getMonth() / 3) * 3, 1);
      return { from: isoDate(start), to };
    }
    case "LAST_90_DAYS": {
      const start = new Date(now);
      start.setDate(start.getDate() - 89);
      return { from: isoDate(start), to };
    }
    case "THIS_YEAR":
      return { from: isoDate(new Date(now.getFullYear(), 0, 1)), to };
    default:
      return { from: null, to: null };
  }
}
