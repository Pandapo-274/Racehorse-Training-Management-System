// src/features/care/careApi.js
// Flow 4 - stable care and daily nutrition.
//
//   Rations      GET /api/feeding, PUT /api/feeding/horse/{id}   (Head Trainer / Vet approve)
//   Daily tasks  GET/POST /api/daily-tasks, PUT .../complete|undo, DELETE  (Groom ticks off own tasks)
//   Incidents    POST /api/incident-reports (multipart, optional photo), GET for "my reports"
//   Supplies     GET/POST/PUT /api/supplies, PUT /api/supplies/{id}/count
//
// The backend decides who sees what (a Groom only gets their own tasks and
// reports); these helpers just wrap the calls.
import { ApiError, authFetch, getToken } from "../auth/authService";

/* ------------------------------------------------------------------ *
 * Rations
 * ------------------------------------------------------------------ */

export function listFeeding(horseId, signal) {
  const q = horseId ? `?horseId=${horseId}` : "";
  return authFetch(`/api/feeding${q}`, { signal });
}

/** Replaces the whole ration of one horse; the caller becomes the approver. */
export function saveRation(horseId, items) {
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

export function assignTasks({ horseId, assignedTo, taskDate, taskTypes, note }) {
  return authFetch("/api/daily-tasks", {
    method: "POST",
    body: JSON.stringify({ horseId, assignedTo, taskDate, taskTypes, note }),
  });
}

export function completeTask(id) {
  return authFetch(`/api/daily-tasks/${id}/complete`, { method: "PUT" });
}

export function undoTask(id) {
  return authFetch(`/api/daily-tasks/${id}/undo`, { method: "PUT" });
}

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

export const INCIDENT_STATUS = {
  PENDING: { label: "Waiting for a vet", tone: "warning" },
  RECEIVED: { label: "Vet on the case", tone: "serious" },
  EXAMINED: { label: "Examined", tone: "good" },
};

/** Same check as the backend (ImageFiles): only JPEG, PNG, WebP up to 2 MB. */
export const PHOTO_MAX_BYTES = 2 * 1024 * 1024;
export const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp"];

/* ------------------------------------------------------------------ *
 * Supplies
 * ------------------------------------------------------------------ */

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

export function countSupply(id, currentQty) {
  return authFetch(`/api/supplies/${id}/count`, {
    method: "PUT",
    body: JSON.stringify({ currentQty }),
  });
}

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
