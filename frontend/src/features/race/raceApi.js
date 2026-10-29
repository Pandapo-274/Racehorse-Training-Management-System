// src/features/race/raceApi.js
// Flow 5 - race entries, results and performance.
//
//   GET  /api/races?year=&horseId=   entries (an owner only gets their own horses)
//   GET  /api/races/years            years that have entries
//   GET  /api/races/candidates       horses the Head Trainer may enter, with the reason if not
//   GET  /api/races/summary?year=    club performance and prize total (Manager, Head Trainer)
//   POST /api/races                  enter a horse (Head Trainer)
//   PUT  /api/races/{id}/result      record the result (Head Trainer, Club Manager)
//   PUT  /api/races/{id}/scratch     withdraw from a race before it runs (Head Trainer)
//
// Race revenue is the sum of prize money. There are no invoices or payments.
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

export function listCandidates(signal) {
  return authFetch("/api/races/candidates", { signal });
}

export function getSummary(year, signal) {
  return authFetch(`/api/races/summary?year=${year}`, { signal });
}

export function registerRace({ horseId, raceName, raceDate, note }) {
  return authFetch("/api/races", {
    method: "POST",
    body: JSON.stringify({ horseId, raceName, raceDate, note }),
  });
}

export function recordResult(id, { result, prizeMoney, note }) {
  return authFetch(`/api/races/${id}/result`, {
    method: "PUT",
    body: JSON.stringify({ result, prizeMoney, note }),
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
};

export const STATE_TEXT = {
  UPCOMING: { label: "Upcoming", tone: "neutral" },
  AWAITING_RESULT: { label: "Result needed", tone: "warning" },
  FINISHED: { label: "Finished", tone: "good" },
  SCRATCHED: { label: "Scratched", tone: "serious" },
};

/** The chip for a row: the result once there is one, otherwise the state. */
export function toEntryChip(entry) {
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
