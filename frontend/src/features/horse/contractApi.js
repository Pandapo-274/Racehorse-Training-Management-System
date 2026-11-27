// src/features/horse/contractApi.js
// Hợp đồng gửi ngựa (V49): xem, ký, trả tiền qua payOS, nhận ngựa, gia hạn, huỷ.
import { authFetch } from "../auth/authService";

const API = "/api/contracts";

export function listContracts(signal) {
  return authFetch(API, { signal });
}

export function getContract(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

export function contractsForHorse(horseId, signal) {
  return authFetch(`${API}/horse/${horseId}`, { signal });
}

/** Điều 11 của hợp đồng: đã giao bao nhiêu buổi so với cam kết. */
export function getCommitment(id, signal) {
  return authFetch(`${API}/${id}/commitment`, { signal });
}

export const COMMITMENT_STATUS = {
  NOT_STARTED: { label: "No sessions due yet", tone: "neutral" },
  ON_TRACK: { label: "On track", tone: "good" },
  AT_RISK: { label: "Behind the commitment", tone: "warning" },
  MET: { label: "Commitment met", tone: "good" },
  NOT_MET: { label: "Commitment not met", tone: "critical" },
};

export function signContract(id, { fullName, agree, termsSha256 }) {
  return authFetch(`${API}/${id}/sign`, {
    method: "POST",
    body: JSON.stringify({ fullName: fullName.trim(), agree: !!agree, termsSha256 }),
  });
}

/** Trả { checkoutUrl, amount, alreadyPaid }. checkoutUrl là trang thanh toán payOS. */
export function payContract(id) {
  return authFetch(`${API}/${id}/pay`, { method: "POST" });
}

/** Hỏi lại payOS xem đã trả chưa (khi quay về từ trang thanh toán). */
export function syncContract(id) {
  return authFetch(`${API}/${id}/sync`, { method: "POST" });
}

export function receiveHorse(id, form) {
  const num = (v) => (v === "" || v === null || v === undefined ? null : Number(v));
  return authFetch(`${API}/${id}/receive`, {
    method: "POST",
    body: JSON.stringify({
      registrationCode: form.registrationCode.trim(),
      stallNo: form.stallNo.trim(),
      trainerId: form.trainerId ? Number(form.trainerId) : null,
      bcs: num(form.bcs),
      weight: num(form.weight),
      heightCm: num(form.heightCm),
      note: form.note?.trim() || null,
    }),
  });
}

export function cancelContract(id, reason) {
  return authFetch(`${API}/${id}/cancel`, {
    method: "POST",
    body: JSON.stringify({ reason: reason?.trim() || null }),
  });
}

export function renewContract(horseId, months) {
  return authFetch(`${API}/renew`, {
    method: "POST",
    body: JSON.stringify({ horseId: Number(horseId), months: Number(months) }),
  });
}

const STATUS = {
  SENT: { label: "Waiting for signature", tone: "warning" },
  SIGNED: { label: "Waiting for payment", tone: "warning" },
  PAID: { label: "Paid - waiting for the horse", tone: "good" },
  ACTIVE: { label: "Active", tone: "good" },
  EXPIRED: { label: "Ended - not renewed", tone: "critical" },
  ENDED: { label: "Closed", tone: "neutral" },
  VOID: { label: "Lapsed / cancelled", tone: "neutral" },
};

export function toContractStatus(status) {
  return STATUS[status] || { label: status || "Unknown", tone: "neutral" };
}

/** Một dòng tóm tắt thời hạn: "Until 21 Feb 2027 · 92 days left". */
export function stayLine(c, formatDate) {
  if (!c?.endDate) return null;
  if (c.status === "EXPIRED") return `Ended on ${formatDate(c.endDate)} - renew or withdraw`;
  const left = c.daysLeft;
  if (left === null || left === undefined) return `Until ${formatDate(c.endDate)}`;
  return `Until ${formatDate(c.endDate)} · ${left} day${left === 1 ? "" : "s"} left`;
}

export const REMINDER_DAYS = 14;

/** V61: the buyer's contract for the rest of a stay the seller paid for. */
export function isTakeover(c) {
  return !!c?.takesOverContractId;
}

/** " · renewal" / " · change of owner" after the horse name. */
export function contractKind(c) {
  if (c?.renewsContractId) return " · renewal";
  if (isTakeover(c)) return " · change of owner";
  return "";
}
