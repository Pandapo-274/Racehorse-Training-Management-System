// src/features/horse/horseRequestApi.js
import { authFetch, getToken, ApiError } from "../auth/authService";

// Yêu cầu đăng ký ngựa (HORSE_REQUEST): chủ ngựa gửi / huỷ; Club Manager duyệt (cấp mã + chuồng) / từ chối.
const API = "/api/horse-requests";

export function listRequests(signal) {
  return authFetch(API, { signal });
}

// Số nguyên dương hoặc null (ô để trống).
const num = (v) => (v === "" || v === null || v === undefined ? null : Number(v));

// Chuỗi rỗng đổi thành null để backend lưu NULL (không lưu chuỗi trống).
export function submitRequest(form) {
  const club = (form.recordType || "ACADEMY") === "ACADEMY";
  return authFetch(API, {
    method: "POST",
    body: JSON.stringify({
      horseName: form.horseName.trim(),
      breed: form.breed.trim() || null,
      horseGender: form.horseGender || null,
      dateOfBirth: form.dateOfBirth || null,
      color: form.color.trim() || null,
      note: form.note.trim() || null,
      recordType: form.recordType || "ACADEMY",
      sireId: form.sireId ? Number(form.sireId) : null,
      damId: form.damId ? Number(form.damId) : null,
      // V48: only a club horse carries measures, aptitudes and a stay.
      weight: club ? num(form.weight) : null,
      heightCm: club ? num(form.heightCm) : null,
      surface: club ? form.surface || null : null,
      distancePref: club ? form.distancePref || null : null,
      runningStyle: club ? form.runningStyle || null : null,
      preferredStart: club ? form.preferredStart || null : null,
      trainingWeeks: club ? num(form.trainingWeeks) : null,
      contractMonths: club ? num(form.contractMonths) : null,
      preferredTrainerId: club && form.preferredTrainerId ? Number(form.preferredTrainerId) : null,
    }),
  });
}

// Ảnh của yêu cầu (V48). FormData nên không đi qua authFetch (xem uploadHorsePhoto).
export async function uploadRequestPhoto(id, file) {
  const body = new FormData();
  body.append("file", file);
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${API}/${id}/photo`, { method: "POST", headers, body });
  if (!res.ok) {
    let payload = null;
    try {
      payload = await res.json();
    } catch {
      /* thân rỗng */
    }
    throw new ApiError(res.status, payload?.message || `Upload failed (HTTP ${res.status})`, payload?.errors || {});
  }
  return res.json();
}

/** Head Trainer chủ ngựa có thể xin (chỉ người còn chỗ, chỉ tên). */
export function listTrainerOptions(signal) {
  return authFetch(`${API}/trainer-options`, { signal });
}

/** Phí tháng + chiết khấu theo gói, để tính trước giá trị hợp đồng. */
export function getPricing(signal) {
  return authFetch(`${API}/pricing`, { signal });
}

/** Cùng công thức với ContractTerms.priceFor ở backend: phí × tháng × (1 − chiết khấu của bậc gần nhất ≤ số tháng). */
export function estimatePrice(pricing, months) {
  const m = Number(months);
  if (!pricing || !m) return null;
  let pct = 0;
  Object.entries(pricing.discounts || {}).forEach(([from, p]) => {
    if (m >= Number(from)) pct = Math.max(pct, Number(p));
  });
  const total = Math.round((pricing.monthlyFee * m * (100 - pct)) / 100);
  return { months: m, monthlyFee: pricing.monthlyFee, discountPct: pct, total };
}

export function formatVnd(amount) {
  if (amount === null || amount === undefined) return "—";
  return `${Number(amount).toLocaleString("en-US")} VND`;
}

/* ---- Breeds, aptitudes, stay rules (same numbers as HorsePolicy.java) ---- */

export const STAY_RULES = { minTrainingWeeks: 3, minMonths: 1, maxMonths: 24, weeksPerMonth: 4 };
export const CONTRACT_MONTH_OPTIONS = [1, 3, 6, 12];

// Default aptitudes per breed: a starting point the owner can change, not a rule.
export const BREED_PRESETS = {
  Thoroughbred: { surface: "TURF", distancePref: "MEDIUM", runningStyle: "MIDDLE" },
  Arabian: { surface: "DIRT", distancePref: "LONG", runningStyle: "MIDDLE" },
  "Quarter Horse": { surface: "DIRT", distancePref: "SPRINT", runningStyle: "FRONT" },
};
export const SURFACES = { TURF: "Turf", DIRT: "Dirt" };
export const DISTANCES = { SPRINT: "Sprint", MEDIUM: "Medium", LONG: "Long" };
export const RUNNING_STYLES = { FRONT: "Front-runner", MIDDLE: "Mid-pack", BACK: "Closer" };

export const GROWTH = {
  BELOW: { label: "Below range for age", tone: "warning" },
  WITHIN: { label: "Within range for age", tone: "good" },
  ABOVE: { label: "Above range for age", tone: "warning" },
};
export function toGrowth(g) {
  return GROWTH[g] || { label: "No growth reference", tone: "neutral" };
}

/** Kiểm thời hạn ở trình duyệt, cùng luật với HorsePolicy.termProblems. */
export function termProblems(trainingWeeks, contractMonths) {
  const e = {};
  const w = trainingWeeks === "" ? null : Number(trainingWeeks);
  const m = contractMonths === "" ? null : Number(contractMonths);
  if (!m) e.contractMonths = "Choose how many months the horse stays";
  else if (m < STAY_RULES.minMonths || m > STAY_RULES.maxMonths) e.contractMonths = "Between 1 and 24 months";
  if (w === null || Number.isNaN(w)) e.trainingWeeks = "Say how many weeks of training you want";
  else if (w < STAY_RULES.minTrainingWeeks) e.trainingWeeks = `Training is at least ${STAY_RULES.minTrainingWeeks} weeks`;
  else if (m && w > m * STAY_RULES.weeksPerMonth) {
    e.trainingWeeks = `Training cannot run longer than the stay (${m * STAY_RULES.weeksPerMonth} weeks for ${m} month${m === 1 ? "" : "s"})`;
  }
  return e;
}

/**
 * Duyệt (V49). Ngựa vào CLB: chỉ cần hai ô xác nhận, duyệt xong là gửi hợp đồng.
 * Hồ sơ pedigree: vẫn cấp mã ngay như trước.
 */
export function approveRequest(id, { registrationCode, note, photoChecked, papersChecked }) {
  return authFetch(`${API}/${id}/approve`, {
    method: "PUT",
    body: JSON.stringify({
      registrationCode: registrationCode?.trim() || null,
      note: note?.trim() || null,
      photoChecked: !!photoChecked,
      papersChecked: !!papersChecked,
    }),
  });
}

export function cancelRequest(id) {
  return authFetch(`${API}/${id}/cancel`, { method: "PUT" });
}

export function rejectRequest(id, note) {
  return authFetch(`${API}/${id}/reject`, {
    method: "PUT",
    body: JSON.stringify({ note: note.trim() }),
  });
}

const STATUS = {
  PENDING:  { label: "Waiting for the club", tone: "warning" },
  APPROVED: { label: "Approved", tone: "good" },
  REJECTED: { label: "Turned down", tone: "critical" },
  CANCELLED: { label: "Cancelled by owner", tone: "neutral" },
};

// Sau khi duyệt, bước tiếp theo nằm ở hợp đồng (V49): hiện trạng thái hợp đồng thay cho "Approved".
const CONTRACT_STEP = {
  SENT: { label: "Approved - sign the contract", tone: "warning" },
  SIGNED: { label: "Signed - waiting for payment", tone: "warning" },
  PAID: { label: "Paid - bring the horse in", tone: "good" },
  ACTIVE: { label: "At the club", tone: "good" },
  EXPIRED: { label: "Stay ended", tone: "critical" },
  ENDED: { label: "Stay closed", tone: "neutral" },
  VOID: { label: "Contract lapsed", tone: "critical" },
};

export function toRequestStatus(status, contractStatus) {
  if (status === "APPROVED" && contractStatus && CONTRACT_STEP[contractStatus]) {
    return CONTRACT_STEP[contractStatus];
  }
  return STATUS[status] || { label: status || "Unknown", tone: "neutral" };
}

export function pendingOnly(rows) {
  return (rows || []).filter((r) => r.status === "PENDING");
}
