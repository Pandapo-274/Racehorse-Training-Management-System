// src/features/race/raceApi.js
// Flow 5 - race entries, results and performance.
//
//   GET  /api/races?year=&horseId=   entries (an owner only gets their own horses)
//   GET  /api/races/years            years that have entries
//   GET  /api/races/candidates?raceId=  horses the Head Trainer may enter, with every reason if not
//   GET  /api/races/summary?year=    club performance and prize total (Manager, Head Trainer)
//   GET  /api/races/events?upcoming= races (V22): distance, venue, deadline, fee, field size
//   POST /api/races/events           create a race (Head Trainer, Club Manager)
//   PUT  /api/races/events/{id}/cancel  cancel a race; its entries are scratched (Club Manager)
//   POST /api/races                  enter a horse in a race (Head Trainer)
//   PUT  /api/races/{id}/result      record the result (Head Trainer, Club Manager)
//   PUT  /api/races/{id}/scratch     withdraw from a race before it runs (Head Trainer)
//
// Prize money is paid to the horse's owner. It is not club revenue; the system
// does not model training fees or commission.
import { authFetch } from "../auth/authService";

export function listRaces({ year, horseId } = {}, signal) {
  const p = new URLSearchParams();
  if (year) p.set("year", year);
  if (horseId) p.set("horseId", horseId);
  const q = p.toString();
  return authFetch(`/api/races${q ? `?${q}` : ""}`, { signal });
}

export function listYears(signal) {
  return authFetch("/api/races/years", { signal });
}

export function listCandidates(raceId, signal) {
  return authFetch(`/api/races/candidates${raceId ? `?raceId=${raceId}` : ""}`, { signal });
}

export function listRaceEvents({ upcoming = false } = {}, signal) {
  return authFetch(`/api/races/events?upcoming=${upcoming}`, { signal });
}

export function createRaceEvent(body) {
  return authFetch("/api/races/events", { method: "POST", body: JSON.stringify(body) });
}

export function cancelRaceEvent(id, reason) {
  return authFetch(`/api/races/events/${id}/cancel`, { method: "PUT", body: JSON.stringify({ reason }) });
}

export function getSummary(year, signal) {
  return authFetch(`/api/races/summary?year=${year}`, { signal });
}

export function registerRace({ horseId, raceId, note }) {
  return authFetch("/api/races", {
    method: "POST",
    body: JSON.stringify({ horseId, raceId, note }),
  });
}

/** finishPosition / finishTimeMs optional; reason needed only for a locked result (Manager). */
export function recordResult(id, { result, prizeMoney, note, finishPosition, finishTimeMs, reason }) {
  return authFetch(`/api/races/${id}/result`, {
    method: "PUT",
    body: JSON.stringify({ result, prizeMoney, note, finishPosition, finishTimeMs, reason }),
  });
}

/** "1:58.4" or "118.4" (seconds) -> 118400 ms. Returns null for empty, NaN for bad input. */
export function parseRaceTime(text) {
  const t = (text || "").trim();
  if (!t) return null;
  const m = /^(?:(\d+):)?(\d+(?:\.\d{1,3})?)$/.exec(t);
  if (!m) return NaN;
  const minutes = m[1] ? Number(m[1]) : 0;
  return Math.round((minutes * 60 + Number(m[2])) * 1000);
}

/** 118400 -> "1:58.4" */
export function formatRaceTime(ms) {
  if (!ms) return "";
  const total = ms / 1000;
  const minutes = Math.floor(total / 60);
  const seconds = (total - minutes * 60).toFixed(1).padStart(4, "0");
  return minutes ? `${minutes}:${seconds}` : seconds;
}

export function ordinal(n) {
  const s = n % 100 >= 11 && n % 100 <= 13 ? "th" : { 1: "st", 2: "nd", 3: "rd" }[n % 10] || "th";
  return `${n}${s}`;
}

export function approveEntry(id) {
  return authFetch(`/api/races/${id}/approve`, {
    method: "PUT",
  });
}

export function rejectEntry(id, reason) {
  return authFetch(`/api/races/${id}/reject`, {
    method: "PUT",
    body: JSON.stringify({ reason }),
  });
}

export function scratchEntry(id, reason) {
  return authFetch(`/api/races/${id}/scratch`, {
    method: "PUT",
    body: JSON.stringify({ reason }),
  });
}

/* ------------------------------------------------------------------ *
 * Labels
 * ------------------------------------------------------------------ */

export const RESULTS = ["WON", "PLACED", "UNPLACED"];

export const RESULT_TEXT = {
  WON: { label: "Won", tone: "good" },
  PLACED: { label: "Placed", tone: "good" },
  UNPLACED: { label: "Unplaced", tone: "neutral" },
  SCRATCHED: { label: "Scratched", tone: "serious" },
  REJECTED: { label: "Declined", tone: "serious" },
};

export const STATE_TEXT = {
  UPCOMING: { label: "Upcoming", tone: "neutral" },
  PENDING_APPROVAL: { label: "Pending Owner", tone: "warning" },
  AWAITING_RESULT: { label: "Result needed", tone: "warning" },
  FINISHED: { label: "Finished", tone: "good" },
  SCRATCHED: { label: "Scratched", tone: "serious" },
  REJECTED: { label: "Declined", tone: "serious" },
};

/** The chip for a row: the result once there is one, otherwise the state. */
export function toEntryChip(entry) {
  if (entry.approvalStatus === "PENDING_OWNER" || entry.state === "PENDING_APPROVAL") {
    return { label: "Pending Owner", tone: "warning" };
  }
  if (entry.approvalStatus === "REJECTED" || entry.state === "REJECTED") {
    return { label: "Declined", tone: "serious" };
  }
  if (entry.result && RESULT_TEXT[entry.result]) return RESULT_TEXT[entry.result];
  return STATE_TEXT[entry.state] || { label: entry.state || "—", tone: "neutral" };
}

const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
});

export function formatPrize(amount) {
  const n = Number(amount);
  if (!Number.isFinite(n) || n === 0) return "—";
  return money.format(n);
}

/** raceDate is a LocalDate "2026-11-26". Parsed as a local date, not UTC. */
export function formatRaceDate(iso) {
  if (!iso) return "—";
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
}

export function tomorrowIso() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
