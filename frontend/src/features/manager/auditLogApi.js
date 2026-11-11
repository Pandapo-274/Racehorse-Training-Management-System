// src/features/manager/auditLogApi.js
// GET /api/audit-logs (CLUB_MANAGER only): the system's action log, newest first.
//
// The backend sends times as UTC instants ("...Z"), so new Date() reads them in
// the viewer's own time zone without extra work.
import { parseServerTime } from "../horse/horseApi";
import { authFetch } from "../auth/authService";

/**
 * filters: { since, until (Date), result, action, user, target }
 * beforeId: the nextBeforeId of the previous page, for "Load more".
 * Returns { items, nextBeforeId, summary } (summary only on the first page).
 */
export function getAuditLogs(filters = {}, beforeId, signal) {
  const params = new URLSearchParams();
  if (filters.since) params.set("since", filters.since.toISOString());
  if (filters.until) params.set("until", filters.until.toISOString());
  if (filters.result) params.set("result", filters.result);
  if (filters.action) params.set("action", filters.action);
  if (filters.user) params.set("user", filters.user);
  if (filters.target) params.set("target", filters.target);
  if (beforeId) params.set("beforeId", beforeId);
  const qs = params.toString();
  return authFetch(`/api/audit-logs${qs ? `?${qs}` : ""}`, { signal });
}

/* ------------------------------------------------------------------ *
 * Filters
 * ------------------------------------------------------------------ */

/** Time windows; hours = null means no lower bound. */
export const PERIODS = [
  { key: "24H", label: "Last 24 hours", hours: 24 },
  { key: "7D", label: "Last 7 days", hours: 24 * 7 },
  { key: "30D", label: "Last 30 days", hours: 24 * 30 },
  { key: "ALL", label: "All time", hours: null },
];

export function periodStart(key, now = new Date()) {
  const p = PERIODS.find((x) => x.key === key);
  return p?.hours ? new Date(now.getTime() - p.hours * 3600 * 1000) : null;
}

/** Action groups, matched by code prefix on the backend. */
export const ACTION_GROUPS = [
  { value: "", label: "All actions" },
  { value: "LOGIN", label: "Sign-in" },
  { value: "ACCESS", label: "Blocked access attempts" },
  { value: "STAFF_", label: "Staff accounts & roles" },
  { value: "HORSE_", label: "Horses & registrations" },
  { value: "TRAINING_PLAN_", label: "Training plans" },
  { value: "TRAINING_SESSION_", label: "Training sessions" },
  { value: "RESOLVE_ALERT", label: "Alerts" },
  { value: "VET_CALL_", label: "Vet calls" },
  { value: "MEDICAL_EXAM", label: "Examinations" },
  { value: "TRAINING_LOCK_", label: "Training locks" },
  { value: "INCIDENT_", label: "Incident reports" },
  { value: "DAILY_TASK_", label: "Daily care tasks" },
  { value: "FEEDING_", label: "Rations" },
  { value: "SUPPLY_", label: "Supplies" },
  { value: "RACE_", label: "Races" },
];

/* ------------------------------------------------------------------ *
 * Labels
 * ------------------------------------------------------------------ */

/**
 * Readable text for each action code written by the backend (writeAudit).
 * A code that is not listed here is shown as-is, so nothing is ever hidden.
 */
const ACTION_TEXT = {
  LOGIN: "Signed in",
  LOGIN_LOCKED: "Sign-in locked after repeated wrong passwords",
  LOGIN_GOOGLE: "Signed in with Google",
  LOGOUT: "Signed out",
  REGISTER: "Registered an account",
  REGISTER_GOOGLE: "Registered with Google",
  LINK_GOOGLE: "Linked a Google account",
  CHANGE_PASSWORD: "Changed password",
  FORGOT_PASSWORD: "Requested a password reset",
  RESET_PASSWORD: "Reset password with an email link",
  UPDATE_PROFILE: "Updated profile",
  UPLOAD_AVATAR: "Uploaded a profile photo",
  STAFF_CREATE: "Created an account",
  STAFF_UPDATE: "Edited an account's contact details",
  STAFF_TEMP_PASSWORD: "Set a temporary password",
  STAFF_SUSPEND: "Suspended an account",
  STAFF_ACTIVATE: "Activated an account",
  HORSE_CREATE: "Created a horse record",
  HORSE_UPDATE: "Edited horse details",
  HORSE_IDENTITY_UPDATE: "Edited a horse's club records",
  HORSE_OWNER_TRANSFER: "Transferred a horse to another owner",
  HORSE_READMIT: "Re-admitted a withdrawn horse",
  HORSE_PHOTO_UPLOAD: "Uploaded a horse photo",
  HORSE_PHOTO_REMOVE: "Removed a horse photo",
  HORSE_REQUEST_SUBMIT: "Requested a horse registration",
  HORSE_REQUEST_APPROVE: "Approved a horse registration",
  HORSE_REQUEST_REJECT: "Rejected a horse registration",
  HORSE_REQUEST_CANCEL: "Cancelled a horse registration request",
  HORSE_WITHDRAWAL_SUBMIT: "Requested a horse withdrawal",
  HORSE_WITHDRAWAL_APPROVE: "Approved a horse withdrawal",
  HORSE_WITHDRAWAL_REJECT: "Rejected a horse withdrawal",
  HORSE_WITHDRAWAL_CANCEL: "Cancelled a withdrawal request",
  HORSE_WITHDRAW: "Horse left the club",
  DAILY_TASK_REMOVE_BULK: "Removed the horse's open care tasks",
  RACE_SCRATCH_BULK: "Scratched the horse's upcoming races",
  TRAINING_PLAN_CREATE: "Created a training plan",
  TRAINING_PLAN_ACTIVE: "Activated a training plan",
  TRAINING_PLAN_PAUSED: "Paused a training plan",
  TRAINING_PLAN_COMPLETED: "Completed a training plan",
  TRAINING_PLAN_COMPLETE_BULK: "Ended training plans",
  TRAINING_SESSION_ASSIGN: "Assigned a training session",
  TRAINING_SESSION_RESCHEDULE: "Rescheduled a training session",
  TRAINING_SESSION_START: "Started a training session",
  TRAINING_SESSION_CANCEL_BULK: "Cancelled remaining sessions",
  SESSION_STOP_INCIDENT: "Stopped a session: horse unwell",
  SESSION_STOPPED_BY_LOCK: "Session stopped by a training lock",
  SESSION_STOPPED_FOR_VET: "Stopped a session to call the vet",
  SESSION_STOPPED_BY_VET: "Session stopped on vet advice",
  SESSION_TRIAL_RUN_UPDATE: "Updated a trial run / video",
  EVALUATE_SESSION: "Evaluated a training session",
  INCIDENT_RECEIVE: "Took an incident case",
  INCIDENT_REPORT_CREATE: "Reported an incident at the stable",
  MEDICAL_EXAM: "Recorded an examination",
  HEALTH_CLEARED: "Cleared a horse as healthy",
  INJURY_MARKED: "Marked an injury",
  INJURY_UNMARKED: "Removed an injury mark",
  TREATMENT_PRESCRIBE: "Prescribed a treatment",
  TREATMENT_END: "Ended a treatment",
  TRAINING_LOCK_ISSUED: "Issued a training lock",
  TRAINING_LOCK_RELEASED: "Released a training lock",
  FEEDING_PLAN_UPDATE: "Approved a ration",
  DAILY_TASK_ASSIGN: "Assigned a care task",
  DAILY_TASK_DONE: "Completed a care task",
  DAILY_TASK_REOPEN: "Reopened a care task",
  DAILY_TASK_DELETE: "Deleted a care task",
  SUPPLY_CREATE: "Added a supply item",
  SUPPLY_UPDATE: "Edited a supply item",
  SUPPLY_COUNT: "Updated a stock count",
  RACE_REGISTER: "Registered a horse for a race",
  RACE_RESULT: "Recorded a race result",
  RACE_SCRATCH: "Scratched a race entry",
};

const ROLE_TEXT = {
  CLUB_MANAGER: "Club Manager",
  HEAD_TRAINER: "Head Trainer",
  VETERINARIAN: "Veterinarian",
  GROOM: "Groom",
  HORSE_OWNER: "Horse Owner",
};

const ALERT_ACTION_TEXT = {
  CONTINUE: "continue",
  REDUCE_INTENSITY: "reduce intensity",
  STOP_SESSION: "stop the session",
  VET_CALLED: "call the vet",
};

const VET_DECISION_TEXT = {
  CONTINUE_LIGHT: "continue light",
  TREAT_ON_SITE: "treat at the stable",
  EMERGENCY_STOP: "emergency stop",
};

/** The same action read differently when it was refused (result DENIED). */
const DENIED_TEXT = {
  LOGIN: "Failed sign-in",
  LOGIN_GOOGLE: "Google sign-in refused",
  CHANGE_PASSWORD: "Failed password change",
};

/**
 * Codes with a variable part: "ACCESS GET /api/x", "STAFF_ROLE_CHANGE:A->B",
 * "STAFF_ASSIGN_TRAINER:12", "STAFF_MAX_HORSES:15", "HORSE_ASSIGN_TRAINER:NONE",
 * "RESOLVE_ALERT:STOP_SESSION". Pass the row's result so a refused action does
 * not read as if it had happened ("Signed in" + Denied).
 */
export function toActionText(code, result) {
  if (!code) return "—";
  if (code.startsWith("ACCESS ")) return "Blocked: no permission";
  if (result === "DENIED") {
    if (DENIED_TEXT[code]) return DENIED_TEXT[code];
    const text = toActionText(code);
    return `Blocked: ${text.charAt(0).toLowerCase()}${text.slice(1)}`;
  }
  if (ACTION_TEXT[code]) return ACTION_TEXT[code];
  if (code.startsWith("STAFF_ASSIGN_TRAINER:")) {
    return code.endsWith(":NONE") ? "Removed a groom from their Head Trainer" : "Assigned a groom to a Head Trainer";
  }
  if (code.startsWith("STAFF_MAX_HORSES:")) return "Changed a Head Trainer's horse limit";
  if (code.startsWith("HORSE_ASSIGN_TRAINER:")) {
    return code.endsWith(":NONE") ? "Removed a horse's Head Trainer" : "Assigned a Head Trainer to a horse";
  }
  if (code.startsWith("STAFF_ROLE_CHANGE:")) {
    const [from, to] = code.slice("STAFF_ROLE_CHANGE:".length).split("->");
    return `Changed role: ${ROLE_TEXT[from] || from} → ${ROLE_TEXT[to] || to}`;
  }
  if (code.startsWith("VET_CALL_ANSWER:")) {
    const d = code.slice("VET_CALL_ANSWER:".length);
    return `Answered a vet call: ${VET_DECISION_TEXT[d] || d}`;
  }
  if (code.startsWith("RESOLVE_ALERT:")) {
    const a = code.slice("RESOLVE_ALERT:".length);
    return `Resolved an alert: ${ALERT_ACTION_TEXT[a] || a}`;
  }
  return code;
}

export function toRoleText(role) {
  return ROLE_TEXT[role] || role || "";
}

const TARGET_TEXT = {
  APP_USER: "Account",
  HORSE: "Horse",
  HORSE_REQUEST: "Registration",
  HORSE_WITHDRAWAL: "Withdrawal",
  TRAINING_PLAN: "Training plan",
  TRAINING_SESSION: "Session",
  THRESHOLD_ALERT: "Alert",
  VET_CALL: "Vet call",
  MEDICAL_RECORD: "Exam record",
  INJURY: "Injury",
  TREATMENT: "Treatment",
  TRAINING_LOCK: "Training lock",
  INCIDENT_REPORT: "Incident report",
  DAILY_TASK: "Care task",
  SUPPLY_STOCK: "Supply item",
  RACE_REGISTRATION: "Race entry",
};

/** Screens behind each API path, for "Blocked: no permission" rows. */
const API_AREAS = [
  ["/api/staff", "staff accounts"],
  ["/api/audit-logs", "the audit log"],
  ["/api/permissions", "permissions"],
  ["/api/horse-requests", "horse registrations"],
  ["/api/horse-withdrawals", "horse withdrawals"],
  ["/api/horses", "horse records"],
  ["/api/expenses", "costs"],
  ["/api/training-plans", "training plans"],
  ["/api/training-sessions", "training sessions"],
  ["/api/races", "races"],
  ["/api/sessions", "training sessions"],
  ["/api/training-locks", "training locks"],
  ["/api/medical-records", "medical records"],
  ["/api/injuries", "injuries"],
  ["/api/treatments", "treatments"],
  ["/api/preventive-care", "preventive care"],
  ["/api/supplies", "supplies"],
  ["/api/manager/dashboard", "the manager dashboard"],
  ["/api/trainer/dashboard", "the trainer dashboard"],
  ["/api/owner", "the owner dashboard"],
];
const METHOD_VERB = { GET: "open", POST: "create in", PUT: "change", PATCH: "change", DELETE: "delete in" };

/**
 * One readable sentence for the Description column: what the action was on
 * (by name, not only by id), then the backend's detail (what changed, why).
 * Returns { main, extra } - extra is shown smaller under main.
 */
export function describeLog(r) {
  if (r.action && r.action.startsWith("ACCESS ")) {
    const [method, path = ""] = r.action.slice(7).split(" ");
    const area = API_AREAS.find(([prefix]) => path.startsWith(prefix));
    const verb = METHOD_VERB[method] || "use";
    return {
      main: `Tried to ${verb} ${area ? area[1] : "a function"} without permission`,
      extra: `${method} ${path}`,
    };
  }
  const kind = TARGET_TEXT[r.targetEntity] || r.targetEntity;
  let main;
  if (!r.targetEntity) main = "";
  else if (r.targetName) main = `${kind}: ${r.targetName}`;
  else main = toTargetText(r.targetEntity, r.targetId);
  if (!main && !r.detail) return { main: "—", extra: "" };
  if (!main) return { main: r.detail, extra: "" };
  return { main, extra: r.detail || "" };
}

export function toTargetText(entity, id) {
  if (!entity) return "—";
  const name = TARGET_TEXT[entity] || entity;
  return id != null ? `${name} #${id}` : name;
}

/* ------------------------------------------------------------------ *
 * Time
 * ------------------------------------------------------------------ */

/** "27 Oct 2026, 10:48:29" in the viewer's time zone. */
export function formatFullTime(iso) {
  const d = parseServerTime(iso);
  if (!d || Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("en-GB", {
    day: "2-digit", month: "short", year: "numeric",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
}

/** "Just now", "5 min ago", "3 h ago", "2 d ago". */
export function formatAgo(iso, now = Date.now()) {
  const t = iso ? new Date(iso).getTime() : NaN;
  if (Number.isNaN(t)) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return "Just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}
