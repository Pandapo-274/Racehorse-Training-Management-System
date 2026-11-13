// src/features/horse/horseApi.js
import { ApiError, authFetch, getToken } from "../auth/authService";

export { checkAvatar as checkHorsePhoto } from "../profile/profileApi";

const API = "/api/horses";

export function listHorses(signal) {
  return authFetch(API, { signal });
}

export function getHorse(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

/** V37: Danh sách Head Trainer đang ACTIVE cùng số ngựa quản lý và hạn mức tối đa. */
export function listActiveTrainers(signal) {
  return authFetch(`${API}/trainers`, { signal });
}

export function getPedigree(id, generations = 3, signal) {
  return authFetch(`${API}/${id}/pedigree?generations=${generations}`, { signal });
}

export function getVitals(id, signal) {
  return authFetch(`${API}/${id}/vitals`, { signal });
}

/** Chỉ CLUB_MANAGER: sửa mã đăng ký, số chuồng và điều phối Head Trainer phụ trách. */
export function updateHorseIdentity(id, { registrationCode, stallNo, trainerId }) {
  return authFetch(`${API}/${id}/identity`, {
    method: "PUT",
    body: JSON.stringify({
      registrationCode: registrationCode.trim(),
      stallNo: stallNo?.trim() || null,
      trainerId: trainerId ? Number(trainerId) : null,
    }),
  });
}

/** Cha / mẹ chọn được: hồ sơ phả hệ của CLB + ngựa của chính chủ ngựa. */
/** horseId: the horse being edited, so its current parents stay in the list even if not choosable now. */
export function listParentCandidates(signal, horseId) {
  const q = horseId ? `?horseId=${encodeURIComponent(horseId)}` : "";
  return authFetch(`${API}/parent-candidates${q}`, { signal });
}

/** CLUB_MANAGER only (V41): correct the sire / dam on record, with a reason. */
export function correctHorseParents(id, { sireId, damId, reason }) {
  return authFetch(`${API}/${id}/parents`, {
    method: "PUT",
    body: JSON.stringify({
      sireId: sireId ? Number(sireId) : null,
      damId: damId ? Number(damId) : null,
      reason: reason.trim(),
    }),
  });
}

/** Option text for a sire / dam select. */
export function parentLabel(h) {
  return `${h.horseName} · ${h.registrationCode}${h.recordType === "PEDIGREE" ? " · pedigree" : ""}`;
}

/** Chỉ CLUB_MANAGER: chuyển ngựa sang chủ khác. */
export function transferHorseOwner(id, ownerId, reason) {
  return authFetch(`${API}/${id}/owner`, {
    method: "PUT",
    body: JSON.stringify({ ownerId, reason: reason.trim() }),
  });
}

/** Chỉ CLUB_MANAGER: nhận lại ngựa đã rút vào một chuồng. */
export function readmitHorse(id, stallNo) {
  return authFetch(`${API}/${id}/readmit`, {
    method: "PUT",
    body: JSON.stringify({ stallNo: stallNo.trim() }),
  });
}

export function updateHorse(id, body) {
  return authFetch(`${API}/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function uploadHorsePhoto(id, file) {
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
      /* thân rỗng hoặc không phải JSON */
    }
    throw new ApiError(
      res.status,
      payload?.message || `Upload failed (HTTP ${res.status})`,
      payload?.errors || {}
    );
  }

  return res.json();
}

export function removeHorsePhoto(id) {
  return authFetch(`${API}/${id}/photo`, { method: "DELETE" });
}

export function canEditHorse(user, horse) {
  return !!user && !!horse && user.role === "HORSE_OWNER" && horse.ownerId === user.userId;
}

export function canReviewRequests(user) {
  return !!user && (user.role === "CLUB_MANAGER");
}

export function getRequestsPath(user) {
  return user?.role === "HEAD_TRAINER" ? "/trainer/horse-requests" : "/manager/horse-requests";
}

export function canViewPedigree(user) {
  return !!user;
}

export function canViewVitals(user) {
  return !!user;
}

export function getHorseBasePath(user) {
  const paths = {
    CLUB_MANAGER: "/manager/horses",
    HEAD_TRAINER: "/trainer/horses",
    VETERINARIAN: "/veterinarian/horses",
    GROOM: "/groom/horses",
    HORSE_OWNER: "/horse-owner/horses",
  };
  return paths[user?.role] || "/login";
}

const STATUS_TEXT = {
  ELIGIBLE:   { label: "Healthy", tone: "good",   hint: "Cleared as healthy by the vet" },
  MONITOR:    { label: "Monitor",    tone: "warning",  hint: "Needs watching" },
  INJURED:    { label: "Injured",    tone: "critical", hint: "Not fit to train" },
  QUARANTINE: { label: "Quarantine", tone: "serious",  hint: "Isolated" },
};

export function toStatus(status) {
  return STATUS_TEXT[status] || { label: status || "Unknown", tone: "neutral", hint: "" };
}

export const STATUS_OPTIONS = Object.keys(STATUS_TEXT);

const ACTIVITY_TEXT = {
  ACTIVE:    { label: "At the club", tone: "good",    hint: "Stabled and training at the club" },
  WITHDRAWN: { label: "Withdrawn",   tone: "neutral", hint: "Has left the club. No stall, plans or sessions." },
};

export function toActivity(activityStatus) {
  return ACTIVITY_TEXT[activityStatus || "ACTIVE"] || { label: activityStatus, tone: "neutral", hint: "" };
}

export const ACTIVITY_OPTIONS = Object.keys(ACTIVITY_TEXT);

export function isWithdrawn(horse) {
  return horse?.activityStatus === "WITHDRAWN";
}

export function canRequestWithdrawal(user, horse) {
  return canEditHorse(user, horse) && !isPedigreeOnly(horse) && !isWithdrawn(horse);
}

export const AGE_RULES = { foalBelow: 2, racingMax: 10, clubMax: 25 };

export const RECORD_TYPES = {
  ACADEMY:  "Club horse",
  PEDIGREE: "Pedigree record only",
};

export function isPedigreeOnly(horse) {
  return horse?.recordType === "PEDIGREE";
}

export function toStage(horse) {
  if (isPedigreeOnly(horse)) {
    return { key: "PEDIGREE", label: "Pedigree only", tone: "neutral",
             hint: "Reference record for parents and ancestors. Not a training horse." };
  }
  const age = toAge(horse?.dateOfBirth);
  if (age == null) return { key: "UNKNOWN", label: "Age unknown", tone: "neutral", hint: "" };
  if (age < AGE_RULES.foalBelow) {
    return { key: "FOAL", label: "Foal", tone: "neutral", hint: "Too young to race" };
  }
  if (age <= AGE_RULES.racingMax) {
    return { key: "RACING", label: "Racing age", tone: "neutral", hint: "In the racing age range" };
  }
  return { key: "RETIRED", label: "Retired age", tone: "neutral", hint: "Past racing age" };
}

export const STAGE_FILTERS = [
  { key: "RACING", label: "Racing" },
  { key: "FOAL", label: "Foal" },
  { key: "RETIRED", label: "Retired" },
  { key: "PEDIGREE", label: "Ancestors" },
];

export function healthApplies(horse) {
  if (isPedigreeOnly(horse)) return false;
  return true;
}

export function ageProblem(recordType, dateOfBirth) {
  if (recordType === "PEDIGREE") return null;
  if (!dateOfBirth) return "Date of birth is required for a club horse";
  const age = toAge(dateOfBirth);
  if (age != null && age > AGE_RULES.clubMax) {
    return `This horse is ${age} years old, above the club limit of ${AGE_RULES.clubMax}. Register it as a pedigree record instead.`;
  }
  return null;
}

const GENDER_TEXT = {
  COLT:     "Colt",
  FILLY:    "Filly",
  STALLION: "Stallion",
  MARE:     "Mare",
  GELDING:  "Gelding",
};

export const GENDER_OPTIONS = Object.keys(GENDER_TEXT);

export function toGender(g) {
  return GENDER_TEXT[g] || "—";
}

export const SIRE_GENDERS = new Set(["COLT", "STALLION"]);
export const DAM_GENDERS = new Set(["FILLY", "MARE"]);

const SEVERITY_TONE = {
  HIGH:     "critical",
  CRITICAL: "critical",
  MEDIUM:   "serious",
  MODERATE: "serious",
  LOW:      "warning",
  MINOR:    "warning",
};

export function toSeverity(s) {
  const key = String(s || "").toUpperCase();
  return {
    label: key ? key.charAt(0) + key.slice(1).toLowerCase() : "—",
    tone: SEVERITY_TONE[key] || "neutral",
  };
}

const METRIC_TEXT = {
  HEART_RATE: "Heart rate",
  VELOCITY: "Speed",
  WORKLOAD: "Workload",
};

export function toMetric(m) {
  return METRIC_TEXT[m] || m;
}

export function toEnumLabel(v) {
  if (!v) return "—";
  const s = String(v).replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
}

export function parseServerTime(iso) {
  if (!iso) return null;
  const hasZone = /(?:[zZ]|[+-]\d{2}:?\d{2})$/.test(iso);
  const d = new Date(hasZone ? iso : `${iso}Z`);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatDateTime(iso, { local = false } = {}) {
  if (!iso) return "—";
  const d = local ? new Date(iso) : parseServerTime(iso) || new Date(NaN);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleString("en-GB", {
        day: "2-digit", month: "short", year: "numeric",
        hour: "2-digit", minute: "2-digit",
      });
}

export function toAge(dateOfBirth) {
  if (!dateOfBirth) return null;
  const born = new Date(dateOfBirth);
  if (Number.isNaN(born.getTime())) return null;
  const now = new Date();
  let age = now.getFullYear() - born.getFullYear();
  const m = now.getMonth() - born.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < born.getDate())) age -= 1;
  return age;
}

export function formatWeight(w) {
  const n = Number(w);
  return Number.isFinite(n) && n > 0 ? `${n.toFixed(1)} kg` : "—";
}

export function toInitials(name) {
  if (!name) return "??";
  return name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
}
