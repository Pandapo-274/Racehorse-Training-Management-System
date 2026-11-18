// src/features/care/careApi.js
// Flow 4 - stable care and daily nutrition.
//
//   Rations      GET /api/feeding, PUT /api/feeding/horse/{id}   (Head Trainer / Vet approve)
//   Daily tasks  GET/POST /api/daily-tasks, PUT .../complete|undo, DELETE  (Groom ticks off own tasks)
//   Incidents    POST /api/incident-reports (multipart, optional photo), GET for "my reports"
//   Supplies     GET/POST/PUT /api/supplies, PUT /api/supplies/{id}/count,
//                POST .../receive | .../waste, GET .../movements  (stock ledger, V21)
//   Missed tasks GET /api/daily-tasks/missed, reassign PUT /api/daily-tasks/{id}/assignee
//
// The backend decides who sees what (a Groom only gets their own tasks and
// reports); these helpers just wrap the calls.
import { ApiError, authFetch, getToken } from "../auth/authService";

/* ------------------------------------------------------------------ *
 * Rations
 * ------------------------------------------------------------------ */

// GET /api/feeding?horseId=: khẩu phần (bảng FEEDING_SCHEDULE) theo bữa MORNING / NOON / EVENING; mỗi dòng trỏ tới
// một món FEED trong kho. Không truyền horseId = mọi ngựa người gọi được xem.
export function listFeeding(horseId, signal) {
  const q = horseId ? `?horseId=${horseId}` : "";
  return authFetch(`/api/feeding${q}`, { signal });
}

/** Replaces the whole ration of one horse; the caller becomes the approver. */
export function saveRation(horseId, items) {
  // Thay TOÀN BỘ khẩu phần: backend kiểm từng dòng trước rồi mới xoá khẩu phần cũ và ghi khẩu phần mới.
  // Ngựa INJURED / QUARANTINE có khẩu phần do bác sĩ đặt thì Head Trainer không sửa được (409, CareService).
  // Người liên quan nhận thông báo RATION_CHANGED.
  return authFetch(`/api/feeding/horse/${horseId}`, {
    method: "PUT",
    body: JSON.stringify({ items }),
  });
}

/* ------------------------------------------------------------------ *
 * Daily tasks
 * ------------------------------------------------------------------ */

export function listTasks({ date, horseId } = {}, signal) {
  const p = new URLSearchParams();
  if (date) p.set("date", date);
  if (horseId) p.set("horseId", horseId);
  const q = p.toString();
  return authFetch(`/api/daily-tasks${q ? `?${q}` : ""}`, { signal });
}

// POST /api/daily-tasks (Head Trainer): giao một hoặc nhiều loại việc (taskTypes) của một ngựa trong một ngày
// cho một Groom.
export function assignTasks({ horseId, assignedTo, taskDate, taskTypes, note }) {
  return authFetch("/api/daily-tasks", {
    method: "POST",
    body: JSON.stringify({ horseId, assignedTo, taskDate, taskTypes, note }),
  });
}

/**
 * POST /api/daily-tasks/bulk: several horses, fromDate..toDate (at most 14 days), one groom.
 * Returns { created, skipped, notes }. Tasks a horse already has that day are skipped.
 */
export function assignTasksBulk({ horseIds, assignedTo, fromDate, toDate, taskTypes, note }) {
  return authFetch("/api/daily-tasks/bulk", {
    method: "POST",
    body: JSON.stringify({ horseIds, assignedTo, fromDate, toDate, taskTypes, note }),
  });
}

// Tick hoàn thành / mở lại. Việc FEEDING khi tick sẽ trừ kho theo khẩu phần (CONSUME), mở lại thì hoàn
// (REVERSAL) - xem SupplyService.
export function completeTask(id) {
  return authFetch(`/api/daily-tasks/${id}/complete`, { method: "PUT" });
}

export function undoTask(id) {
  return authFetch(`/api/daily-tasks/${id}/undo`, { method: "PUT" });
}

/** Hand an open task to another groom. Returns { created: [task], warnings }. */
export function reassignTask(id, assignedTo) {
  return authFetch(`/api/daily-tasks/${id}/assignee`, {
    method: "PUT",
    body: JSON.stringify({ assignedTo }),
  });
}

/** Tasks never ticked between two days (defaults: last 7 days). */
export function listMissedTasks({ from, to } = {}, signal) {
  const p = new URLSearchParams();
  if (from) p.set("from", from);
  if (to) p.set("to", to);
  const q = p.toString();
  return authFetch(`/api/daily-tasks/missed${q ? `?${q}` : ""}`, { signal });
}

// state do backend tính khi đọc (CareRepository.stateOf): DONE_LATE = tick sau ngày của việc;
// MISSED = đã qua ngày mà chưa tick; OPEN = chưa tick và chưa qua ngày.
export const TASK_STATE = {
  OPEN: { label: "Open", tone: "neutral" },
  DONE: { label: "Done", tone: "good" },
  DONE_LATE: { label: "Done late", tone: "warning" },
  MISSED: { label: "Missed", tone: "critical" },
};

export function deleteTask(id) {
  return authFetch(`/api/daily-tasks/${id}`, { method: "DELETE" });
}

/** Active grooms for the assign form (Head Trainer only). */
export function listGrooms(signal) {
  return authFetch("/api/training-sessions/assignees", { signal });
}

/* ------------------------------------------------------------------ *
 * Incident reports
 * ------------------------------------------------------------------ */

/**
 * Multipart, so not through authFetch (it forces a JSON Content-Type; the
 * browser must set the multipart boundary itself). Same pattern as
 * uploadHorsePhoto in horseApi.js.
 */
export async function fileIncident({ horseId, symptoms, description, urgency, photo }) {
  // Tên từng phần (horseId, symptoms, urgency, description, photo) phải khớp @RequestParam của
  // IncidentFilingController. Báo cáo mới ở trạng thái PENDING, mọi bác sĩ được báo.
  const body = new FormData();
  body.append("horseId", horseId);
  body.append("symptoms", symptoms);
  body.append("urgency", urgency);
  if (description) body.append("description", description);
  if (photo) body.append("photo", photo);

  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch("/api/incident-reports", { method: "POST", headers, body });
  if (!res.ok) {
    let payload = null;
    try {
      payload = await res.json();
    } catch {
      /* empty or non-JSON body */
    }
    throw new ApiError(res.status, payload?.message || `Request failed (HTTP ${res.status})`, payload?.errors || {});
  }
  return res.json();
}

/** A Groom only gets the reports they filed (backend filter). */
export function listIncidents({ status = "ALL", limit = 50 } = {}, signal) {
  return authFetch(`/api/incident-reports?status=${status}&limit=${limit}`, { signal });
}

export const STABLE_SYMPTOMS = [
  "Off feed / not eating",
  "Signs of colic (pain in the belly)",
  "Fever / feels hot",
  "Limping / lame",
  "Swelling on a leg",
  "Cut or wound",
  "Coughing / nasal discharge",
];

export const URGENCY = {
  HIGH: { label: "Urgent", tone: "critical", hint: "Vet should come now" },
  MEDIUM: { label: "Soon", tone: "warning", hint: "Today" },
  LOW: { label: "Routine", tone: "neutral", hint: "When available" },
};

// Vòng đời báo cáo sự cố: PENDING (chờ bác sĩ nhận) -> RECEIVED (một bác sĩ nhận ca) -> EXAMINED (đã khám);
// hoặc DISMISSED (báo động giả, có lý do). Khi còn PENDING/RECEIVED, IncidentGuard chặn ngựa tập.
export const INCIDENT_STATUS = {
  PENDING: { label: "Waiting for a vet", tone: "warning" },
  RECEIVED: { label: "Vet on the case", tone: "serious" },
  EXAMINED: { label: "Examined", tone: "good" },
  DISMISSED: { label: "False alarm", tone: "neutral" },
};

/** Same check as the backend (ImageFiles): only JPEG, PNG, WebP up to 2 MB. */
// Ở đây chỉ kiểm theo file.type (đơn giản hơn profileApi); backend vẫn kiểm lại bằng magic bytes (ImageFiles).
export const PHOTO_MAX_BYTES = 2 * 1024 * 1024;
export const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp"];

/* ------------------------------------------------------------------ *
 * Supplies
 * ------------------------------------------------------------------ */

// GET /api/supplies: tồn kho (SUPPLY_STOCK). lowOnly = chỉ mặt hàng dưới định mức. Bác sĩ chỉ thấy MEDICINE.
export function listSupplies({ area, category, lowOnly } = {}, signal) {
  const p = new URLSearchParams();
  if (area) p.set("area", area);
  if (category) p.set("category", category);
  if (lowOnly) p.set("lowOnly", "true");
  const q = p.toString();
  return authFetch(`/api/supplies${q ? `?${q}` : ""}`, { signal });
}

export function createSupply(body) {
  return authFetch("/api/supplies", { method: "POST", body: JSON.stringify(body) });
}

export function updateSupply(id, body) {
  return authFetch(`/api/supplies/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

/**
 * Why a count can be higher than the record. Stock does not appear by itself, so
 * the backend (SupplyService.count) refuses a higher count without one of these;
 * OTHER also needs a note. A lower count needs no reason.
 */
export const COUNT_UP_REASONS = {
  RECOUNT: "Earlier count was wrong",
  RETURNED: "Returned to the store",
  FOUND: "Found misplaced",
  NOT_USED: "Taken by the system but not used",
  OTHER: "Something else",
};

// Kiểm đếm: gửi số thực tế; backend ghi dòng ADJUST vào sổ kho bằng phần chênh lệch. Số cao hơn sổ phải có
// reason trong COUNT_UP_REASONS (OTHER cần thêm note).
export function countSupply(id, currentQty, reason = null, note = null) {
  return authFetch(`/api/supplies/${id}/count`, {
    method: "PUT",
    body: JSON.stringify({ currentQty, reason, note }),
  });
}

/** A delivery into the store / restock supply. */
export function receiveSupply(id, payload, note) {
  // Hai kiểu gọi: receiveSupply(id, { qty, note, unitPrice, unit, area }) hoặc receiveSupply(id, qty, note).
  // Lưu ý: SupplyRepository.addWithDetails nhận unit và area nhưng câu UPDATE hiện chỉ cập nhật số lượng
  // và đơn giá, nên hai trường này không được lưu.
  let body;
  if (typeof payload === 'object' && payload !== null) {
    body = {
      qty: payload.qty,
      note: payload.note || null,
      unitPrice: payload.unitPrice ?? null,
      unit: payload.unit || null,
      area: payload.area || null,
    };
  } else {
    body = { qty: payload, note: note || null };
  }
  return authFetch(`/api/supplies/${id}/receive`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** What a groom can flag about an item (POST /api/supplies/{id}/report). */
export const REPORT_REASONS = {
  LOW: "Running low",
  DAMAGED: "Damaged or spoiled",
  EXPIRED: "Expired",
  OTHER: "Something else",
};

/**
 * Flag an item to the club manager (and the vets, for medicine). Does not change
 * the quantity. body = { reason, qty?, note? }. Returns { message }.
 */
export function reportSupply(id, body) {
  return authFetch(`/api/supplies/${id}/report`, { method: "POST", body: JSON.stringify(body) });
}

/** Stock thrown away; a reason is required. */
// Huỷ hàng hỏng / hết hạn: dòng WASTE trong sổ kho, trừ tồn, bắt buộc lý do.
export function wasteSupply(id, qty, reason) {
  return authFetch(`/api/supplies/${id}/waste`, {
    method: "POST",
    body: JSON.stringify({ qty, reason }),
  });
}

// Sổ kho của một mặt hàng (SUPPLY_MOVEMENT, V21): mỗi thay đổi số lượng là một dòng (xem MOVEMENT_TEXT).
export function listMovements(id, signal) {
  return authFetch(`/api/supplies/${id}/movements`, { signal });
}

export const MOVEMENT_TEXT = {
  RECEIVE: "Received",
  CONSUME: "Used",
  REVERSAL: "Given back",
  ADJUST: "Count",
  WASTE: "Thrown away",
};

export const CATEGORY_TEXT = {
  FEED: "Feed",
  MEDICINE: "Medicine",
  TOOL: "Tools",
};

/* ------------------------------------------------------------------ *
 * Labels and small helpers
 * ------------------------------------------------------------------ */

export const MEALS = ["MORNING", "NOON", "EVENING"];

export const MEAL_TEXT = {
  MORNING: "Morning",
  NOON: "Noon",
  EVENING: "Evening",
};

export const TASK_TYPES = ["FEEDING", "CLEANING", "BATHING", "ICE_BATH"];

export const TASK_TEXT = {
  FEEDING: "Feeding",
  CLEANING: "Stall cleaning",
  BATHING: "Bathing",
  ICE_BATH: "Ice bath",
};

export function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** "A-06" -> "A". Horses without a stall go to "—". */
// Mã chuồng dạng <dãy><số> ("A-06"): lấy phần chữ đầu để gom ngựa theo dãy chuồng trên Stable map.
export function stallBlock(stallNo) {
  if (!stallNo) return "—";
  const m = /^([A-Za-z]+)/.exec(stallNo);
  return m ? m[1].toUpperCase() : "—";
}

/** Groups any list with horseId by horse, keeping the backend's order. */
export function byHorse(rows) {
  const map = new Map();
  for (const r of rows || []) {
    if (!map.has(r.horseId)) map.set(r.horseId, []);
    map.get(r.horseId).push(r);
  }
  return map;
}

/** Quantity without trailing zeros: 3.50 -> "3.5". */
export function formatQty(q) {
  const n = Number(q);
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : "—";
}

/** completedAt is an Instant (ends with Z). Shows local time "06:42". */
export function formatClock(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ""
    : d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}
