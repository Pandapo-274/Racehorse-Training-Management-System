// src/features/horse/withdrawalApi.js
// Rút ngựa khỏi học viện (V15).
//
// Chủ ngựa gửi yêu cầu (kèm lý do) từ hồ sơ ngựa của mình; Club Manager duyệt
// hoặc từ chối ở /manager/withdrawals. Duyệt thì backend, trong một giao dịch:
// huỷ buổi tập còn SCHEDULED (báo Groom), kết thúc giáo án, trả chuồng và đánh
// dấu ngựa WITHDRAWN.

import { authFetch } from "../auth/authService";

// Các hàm gọi /api/horse-withdrawals; mọi kiểm quyền thật nằm ở WithdrawalService (backend).
const API = "/api/horse-withdrawals";

/** Chủ ngựa nhận yêu cầu về ngựa của mình; Club Manager nhận tất cả. */
export function listWithdrawals({ horseId, signal } = {}) {
  const qs = horseId ? `?horseId=${encodeURIComponent(horseId)}` : "";
  return authFetch(`${API}${qs}`, { signal });
}

/** V62: leaveOn = the day the owner collects the horse (ISO date). */
export function submitWithdrawal(horseId, reason, leaveOn) {
  return authFetch(API, {
    method: "POST",
    body: JSON.stringify({ horseId, reason: reason.trim(), leaveOn }),
  });
}

/** V62: what leaving on leaveOn would mean: notice, refund, costs, vet lock, races. */
export function previewWithdrawal(horseId, leaveOn, signal) {
  const q = new URLSearchParams({ horseId });
  if (leaveOn) q.set("leaveOn", leaveOn);
  return authFetch(`${API}/basis?${q}`, { signal });
}

/** V62: the same, for a request already sent (what approving it today would do). */
export function getWithdrawalBasis(id, signal) {
  return authFetch(`${API}/${id}/basis`, { signal });
}

/** V62: the club withdraws a horse whose stay ended without renewal. */
export function withdrawByClub(horseId, reason) {
  return authFetch(`${API}/by-club`, {
    method: "POST",
    body: JSON.stringify({ horseId, reason: reason.trim() }),
  });
}

export function cancelWithdrawal(id) {
  return authFetch(`${API}/${id}/cancel`, { method: "PUT" });
}

export function approveWithdrawal(id, note) {
  return authFetch(`${API}/${id}/approve`, {
    method: "PUT",
    body: JSON.stringify({ note: note?.trim() || null }),
  });
}

export function rejectWithdrawal(id, note) {
  return authFetch(`${API}/${id}/reject`, {
    method: "PUT",
    body: JSON.stringify({ note: note.trim() }),
  });
}

/* ------------------------------------------------------------------ *
 * Hiển thị
 * ------------------------------------------------------------------ */

const STATUS = {
  PENDING:   { label: "Waiting for the club", tone: "warning" },
  APPROVED:  { label: "Approved", tone: "good" },
  REJECTED:  { label: "Turned down", tone: "critical" },
  CANCELLED: { label: "Cancelled by owner", tone: "neutral" },
};

/** Contract clause 9: the stay ends with 15 days' notice. */
export const NOTICE_DAYS = 15;
export const MAX_LEAVE_AHEAD_DAYS = 90;

export function toWithdrawalStatus(status) {
  return STATUS[status] || { label: status || "Unknown", tone: "neutral" };
}

/** "1 session", "3 sessions". */
export function plural(n, noun) {
  const k = Number(n) || 0;
  return `${k} ${noun}${k === 1 ? "" : "s"}`;
}
