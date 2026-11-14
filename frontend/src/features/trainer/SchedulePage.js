import { useSignOutModal } from "../auth/SignOutModal";
import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { authFetch, handleAuthError } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import ConfirmModal from "../common/ConfirmModal";
import "./trainer.css";
import "./schedule.css";

const SESSION_API = "/api/training-sessions";
const PLAN_API = "/api/training-plans";

const EMPTY_FORM = {
  planId: "",
  phaseId: "",
  assignedTo: "",
  sessionDate: "",
  plannedDistance: "",
  plannedDuration: "60",
  velocityCeiling: "",
};

function dateKey(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function mondayOf(date) {
  const result = new Date(date);
  result.setHours(0, 0, 0, 0);
  const day = result.getDay();
  result.setDate(result.getDate() - (day === 0 ? 6 : day - 1));
  return result;
}

function addDays(date, amount) {
  const result = new Date(date);
  result.setDate(result.getDate() + amount);
  return result;
}

function formatDate(date) {
  return date.toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
  });
}

function formatTime(value) {
  if (!value) return "--:--";
  return String(value).slice(11, 16);
}

function toLocalDateTimeInput(value) {
  if (!value) return "";
  return String(value).slice(0, 16);
}

function toApiDateTime(value) {
  // LocalDateTime của Java không cần hậu tố Z hoặc timezone.
  return value.length === 16 ? `${value}:00` : value;
}

function computeEndInfo(sessionDateStr, plannedDurationStr) {
  if (!sessionDateStr) return null;
  const start = new Date(sessionDateStr);
  const dur = Number(plannedDurationStr || 60);
  if (Number.isNaN(start.getTime()) || !Number.isFinite(dur) || dur <= 0) return null;
  const end = new Date(start.getTime() + dur * 60000);
  const sameDay =
    start.getFullYear() === end.getFullYear() &&
    start.getMonth() === end.getMonth() &&
    start.getDate() === end.getDate();
  const pad = (n) => String(n).padStart(2, "0");
  const endTimeText = `${pad(end.getHours())}:${pad(end.getMinutes())}`;
  return { end, sameDay, endTimeText, dur };
}

function getErrorMessage(error) {
  if (error?.fieldErrors && Object.keys(error.fieldErrors).length > 0) {
    return Object.values(error.fieldErrors).join(" ");
  }
  return error?.message || "Something went wrong. Please try again.";
}

function HorseshoeIcon() {
  return (
    <svg
      className="trainer-nav-icon"
      viewBox="0 0 24 24"
      width="20"
      height="20"
      aria-hidden="true"
    >
      <path
        d="M6.2 21V12a5.8 5.8 0 0 1 11.6 0v9"
        fill="none"
        stroke="currentColor"
        strokeWidth="3.6"
        strokeLinecap="round"
      />
    </svg>
  );
}

function ScheduleSidebar({ onSignOut }) {
  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.png" alt="TRACEN logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="trainer-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="trainer-nav">
        <NavItems role="HEAD_TRAINER" prefix="trainer" icon={<HorseshoeIcon />} />
      </nav>

      <div className="trainer-sidebar-bottom">
        <button type="button" className="trainer-signout" onClick={onSignOut}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          Sign out
        </button>
        <ProfileCard
          className="trainer-user"
          avatarClassName="trainer-avatar"
          role="Head Trainer"
        />
      </div>
    </aside>
  );
}

function SessionCard({ session, onEdit, now }) {
  const canEdit = session.status === "SCHEDULED";
  const due = canEdit && session.sessionDate && new Date(session.sessionDate) <= now;
  const durationMins = session.plannedDuration || 60;

  return (
    <article
      className={`schedule-session status-${String(
        session.status || "",
      ).toLowerCase()}${due ? " is-due" : ""}`}
    >
      <div className="schedule-session-time">
        {formatTime(session.sessionDate)} · {durationMins}m
      </div>

      <strong className="schedule-session-horse">
        {session.horseName || `Horse #${session.horseId}`}
      </strong>

      <span className="schedule-session-phase">
        {session.phaseName || `Phase ${session.phaseNo}`}
      </span>

      <span className="schedule-session-groom">
        <span aria-hidden="true">♙</span>{" "}
        {session.assignedToName || `Groom #${session.assignedTo}`}
      </span>

      <span className="schedule-status">
        {due ? "DUE · NOT STARTED" : session.status || "UNKNOWN"}
      </span>

      {session.remarks && session.remarks.includes("[Recovery Session]") && (
        <span style={{ fontSize: "11px", fontWeight: 700, color: "#059669", backgroundColor: "#ecfdf5", border: "1px solid #a7f3d0", padding: "1px 6px", borderRadius: "8px", alignSelf: "flex-start", marginTop: "2px" }}>
          🩺 Recovery
        </span>
      )}

      {canEdit && (
        <button
          className="schedule-edit-button"
          type="button"
          onClick={() => onEdit(session)}
        >
          Edit schedule
        </button>
      )}
    </article>
  );
}

function SessionTypeChoiceModal({ isOpen, onClose, onSelect }) {
  if (!isOpen) return null;
  return (
    <div
      className="schedule-modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        className="schedule-modal"
        role="dialog"
        aria-modal="true"
        style={{ maxWidth: 520 }}
      >
        <div className="schedule-modal-heading">
          <div>
            <span className="schedule-eyebrow">NEW WORKOUT</span>
            <h2>Select session type</h2>
            <p>Choose the type of training session you want to schedule.</p>
          </div>
          <button
            type="button"
            className="schedule-close-button"
            onClick={onClose}
            aria-label="Close dialog"
          >
            ×
          </button>
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "14px", marginTop: "16px", marginBottom: "8px" }}>
          <button
            type="button"
            style={{
              padding: "18px 16px",
              borderRadius: "12px",
              border: "2px solid #e2e8f0",
              background: "#fff",
              cursor: "pointer",
              textAlign: "left",
              display: "flex",
              flexDirection: "column",
              gap: "8px",
              transition: "all 0.15s ease",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.borderColor = "#d93b87";
              e.currentTarget.style.background = "#fdf4f9";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.borderColor = "#e2e8f0";
              e.currentTarget.style.background = "#fff";
            }}
            onClick={() => onSelect("TRAINING")}
          >
            <span style={{ fontSize: "28px" }}>🏃</span>
            <div>
              <strong style={{ fontSize: "15px", color: "#0f172a", display: "block" }}>Training session</strong>
              <span style={{ fontSize: "12px", color: "#64748b", marginTop: "4px", display: "block", lineHeight: 1.4 }}>
                Standard workout for race-fit horses based on active training plan & phase.
              </span>
            </div>
          </button>

          <button
            type="button"
            style={{
              padding: "18px 16px",
              borderRadius: "12px",
              border: "2px solid #e2e8f0",
              background: "#fff",
              cursor: "pointer",
              textAlign: "left",
              display: "flex",
              flexDirection: "column",
              gap: "8px",
              transition: "all 0.15s ease",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.borderColor = "#059669";
              e.currentTarget.style.background = "#f0fdf4";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.borderColor = "#e2e8f0";
              e.currentTarget.style.background = "#fff";
            }}
            onClick={() => onSelect("RECOVERY")}
          >
            <span style={{ fontSize: "28px" }}>🩺</span>
            <div>
              <strong style={{ fontSize: "15px", color: "#059669", display: "block" }}>Recovery session</strong>
              <span style={{ fontSize: "12px", color: "#64748b", marginTop: "4px", display: "block", lineHeight: 1.4 }}>
                Rehabilitation light work prescribed by veterinarian (walking, trotting, max 800m).
              </span>
            </div>
          </button>
        </div>
      </section>
    </div>
  );
}

function SessionModal({
  form,
  setForm,
  plans,
  phases,
  grooms,
  editingSession,
  isRecovery,
  saving,
  onClose,
  onSubmit,
  onCancel,
}) {
  const [recoveryDetails, setRecoveryDetails] = useState(null);
  const selectedPlan = plans.find(
    (plan) => String(plan.planId) === String(form.planId),
  );
  const selectedPhase = phases.find(
    (phase) => String(phase.phaseId) === String(form.phaseId),
  );
  const endInfo = computeEndInfo(form.sessionDate, form.plannedDuration);

  useEffect(() => {
    if (!isRecovery || !selectedPlan?.horseId) {
      setRecoveryDetails(null);
      return;
    }
    let cancelled = false;
    authFetch(`/api/medical-records?horseId=${selectedPlan.horseId}`)
      .then((records) => {
        if (cancelled || !Array.isArray(records)) return;
        const lw = records.find(
          (r) =>
            (r.diagnosis && r.diagnosis.toLowerCase().includes("light work")) ||
            (r.notes && r.notes.toLowerCase().includes("light work")),
        );
        if (lw) {
          const protoMatch = lw.notes?.match(/protocol:\s*([^.]+)/i) || lw.notes?.match(/protocol\s*[a-c]:\s*([^.]+)/i);
          const freqMatch = lw.notes?.match(/frequency:\s*([^.]+)/i);
          const durMatch = lw.notes?.match(/duration:\s*(\d+)/i);
          setRecoveryDetails({
            protocol: protoMatch ? protoMatch[0] : (lw.diagnosis || "Controlled walking & light trotting (max 800m)"),
            frequency: freqMatch ? freqMatch[1].trim() : "Once daily",
            duration: durMatch ? `${durMatch[1]} days` : "5 days",
          });
        } else {
          setRecoveryDetails({
            protocol: "Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm",
            frequency: "Once daily",
            duration: "5 days",
          });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setRecoveryDetails({
            protocol: "Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm",
            frequency: "Once daily",
            duration: "5 days",
          });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isRecovery, selectedPlan?.horseId]);

  const availableGrooms = useMemo(() => {
    const list = [...grooms];
    const ensureGroom = (id, name) => {
      if (!id) return;
      const exists = list.some((g) => String(g.userId) === String(id));
      if (!exists) {
        list.unshift({
          userId: Number(id),
          fullName: name || `Groom #${id}`,
        });
      }
    };
    if (selectedPlan?.groomId) {
      ensureGroom(selectedPlan.groomId, selectedPlan.groomName);
    }
    if (editingSession?.assignedTo) {
      ensureGroom(editingSession.assignedTo, editingSession.assignedToName);
    }
    return list;
  }, [grooms, selectedPlan, editingSession]);

  return (
    <div
      className="schedule-modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving) onClose();
      }}
    >
      <section
        className="schedule-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="schedule-modal-title"
      >
        <div className="schedule-modal-heading">
          <div>
            <span className="schedule-eyebrow">
              {isRecovery ? "RECOVERY & REHABILITATION" : "TRAINING MANAGEMENT"}
            </span>
            <h2 id="schedule-modal-title">
              {editingSession
                ? "Edit schedule"
                : isRecovery
                  ? "Schedule recovery session"
                  : "Create training session"}
            </h2>
            <p>
              {editingSession
                ? "Change the assigned Groom, session time, and planned duration."
                : isRecovery
                  ? "Schedule a rehabilitation workout according to the veterinarian's light work protocol."
                  : "Choose an active plan, a training phase, a Groom managed by you, and session duration."}
            </p>
          </div>
          <button
            type="button"
            className="schedule-close-button"
            onClick={onClose}
            disabled={saving}
            aria-label="Close dialog"
          >
            ×
          </button>
        </div>

        <form className="schedule-form" onSubmit={onSubmit} noValidate>
          {isRecovery && (
            <div
              style={{
                backgroundColor: "#f0fdf4",
                border: "1px solid #86efac",
                borderRadius: "10px",
                padding: "12px 16px",
                marginBottom: "16px",
                fontSize: "13px",
                lineHeight: 1.5,
              }}
            >
              <strong style={{ color: "#166534", display: "flex", alignItems: "center", gap: "6px" }}>
                🩺 Prescribed Rehabilitation Plan (from Veterinarian)
              </strong>
              <div style={{ marginTop: "6px", display: "flex", flexDirection: "column", gap: "4px", color: "#15803d", fontSize: "12px" }}>
                <span>• <strong>Protocol:</strong> {recoveryDetails?.protocol || "Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm"}</span>
                <span>• <strong>Prescribed Frequency:</strong> {recoveryDetails?.frequency || "Once daily (1 session/day)"}</span>
                <span>• <strong>Expected Duration:</strong> {recoveryDetails?.duration || "5 days"}</span>
                <span>• <strong>Safety Rule:</strong> Training lock restricts distance to maximum 800m.</span>
              </div>
            </div>
          )}

          {!editingSession && (
            <>
              <label>
                Training plan
                <select
                  required
                  value={form.planId}
                  onChange={(event) => {
                    const chosenPlanId = event.target.value;
                    const chosenPlan = plans.find(
                      (p) => String(p.planId) === String(chosenPlanId),
                    );
                    setForm((old) => ({
                      ...old,
                      planId: chosenPlanId,
                      phaseId: "",
                      plannedDistance: "",
                      velocityCeiling: "",
                      assignedTo: chosenPlan?.groomId
                        ? String(chosenPlan.groomId)
                        : "",
                    }));
                  }}
                >
                  <option value="">Select a training plan</option>
                  {plans.map((plan) => (
                    <option key={plan.planId} value={plan.planId}>
                      {plan.horseName} — {plan.planName}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Training phase
                <select
                  required
                  disabled={!form.planId}
                  value={form.phaseId}
                  onChange={(event) => {
                    const phase = phases.find(
                      (item) => String(item.phaseId) === event.target.value,
                    );
                    setForm((old) => ({
                      ...old,
                      phaseId: event.target.value,
                      plannedDistance: phase?.distance ?? "",
                      velocityCeiling: phase?.targetVelocity != null ? String(phase.targetVelocity) : "",
                    }))
                  }}
                >
                  <option value="">Select a phase</option>
                  {phases.map((phase) => (
                    <option key={phase.phaseId} value={phase.phaseId}>
                      Phase {phase.phaseNo}: {phase.phaseName}
                    </option>
                  ))}
                </select>
                {selectedPlan && phases.length === 0 && (
                  <small className="schedule-field-hint">
                    No phases were found for this plan.
                  </small>
                )}
                {selectedPhase && (
                  <div className="schedule-phase-info-box">
                    <div className="schedule-phase-info-header">
                      <strong>Phase {selectedPhase.phaseNo}: {selectedPhase.phaseName}</strong>
                      <span>📅 {selectedPhase.startDate} – {selectedPhase.endDate}</span>
                    </div>
                    <div className="schedule-phase-info-grid">
                      <div className="schedule-phase-info-item">
                        <span>Planned Distance</span>
                        <strong>{selectedPhase.distance ? `${Number(selectedPhase.distance).toLocaleString()} m` : "—"}</strong>
                      </div>
                      <div className="schedule-phase-info-item">
                        <span>Target Surface</span>
                        <strong>{selectedPhase.surface || "TURF"}</strong>
                      </div>
                      <div className="schedule-phase-info-item">
                        <span>Sessions / Wk</span>
                        <strong>{selectedPhase.sessionsPerWeek || "—"}</strong>
                      </div>
                      <div className="schedule-phase-info-item">
                        <span>HR Ceiling</span>
                        <strong>{selectedPhase.heartRateCeiling ? `${selectedPhase.heartRateCeiling} bpm` : "—"}</strong>
                      </div>
                      <div className="schedule-phase-info-item">
                        <span>Workload</span>
                        <strong>{selectedPhase.workload || "Normal"}</strong>
                      </div>
                      <div className="schedule-phase-info-item">
                        <span>Min Rest Gap</span>
                        <strong>{selectedPhase.minGapMinutes || 90}m</strong>
                      </div>
                      {selectedPhase.targetVelocity && (
                        <div className="schedule-phase-info-item">
                          <span>Target Speed</span>
                          <strong>{selectedPhase.targetVelocity} m/s</strong>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </label>

              {isRecovery && (
                <label>
                  Planned distance (meters)
                  <input
                    type="number"
                    required
                    min="100"
                    max="800"
                    step="50"
                    value={form.plannedDistance || "800"}
                    onChange={(event) =>
                      setForm((old) => ({
                        ...old,
                        plannedDistance: event.target.value,
                      }))
                    }
                  />
                  <small className="schedule-field-hint" style={{ color: "#166534" }}>
                    ✓ Restricted by Light work training lock: Maximum 800 meters.
                  </small>
                </label>
              )}
            </>
          )}

          {editingSession && (
            <div className="schedule-session-summary">
              <span>HORSE</span>
              <strong>{editingSession.horseName}</strong>
              <span>TRAINING PHASE</span>
              <strong>
                {editingSession.phaseName || `Phase ${editingSession.phaseNo}`}
              </strong>
            </div>
          )}

          <label>
            Assigned Groom
            <select
              required
              value={form.assignedTo}
              onChange={(event) =>
                setForm((old) => ({ ...old, assignedTo: event.target.value }))
              }
            >
              <option value="">Select a Groom</option>
              {availableGrooms.map((groom) => {
                const isPlanGroom =
                  selectedPlan?.groomId &&
                  String(groom.userId) === String(selectedPlan.groomId);
                return (
                  <option key={groom.userId} value={groom.userId}>
                    {groom.fullName}
                    {isPlanGroom ? " (Assigned to plan)" : ""}
                  </option>
                );
              })}
            </select>
            {selectedPlan?.groomName ? (
              <small className="schedule-field-hint" style={{ color: "#166534" }}>
                ✓ Groom in charge of this plan: <strong>{selectedPlan.groomName}</strong> (auto-selected). You can reassign if needed.
              </small>
            ) : availableGrooms.length === 0 ? (
              <small className="schedule-field-hint">
                No active Groom accounts are assigned to your management. Ask the Club Manager to assign Grooms to you.
              </small>
            ) : (
              <small className="schedule-field-hint">
                1 Groom can only train 1 horse at a time and must not be executing an active session.
              </small>
            )}
          </label>

          <label>
            Session date and time
            <input
              type="datetime-local"
              required
              value={form.sessionDate}
              onChange={(event) =>
                setForm((old) => ({
                  ...old,
                  sessionDate: event.target.value,
                }))
              }
            />
            <small className="schedule-field-hint">
              Must be today or in the future. 1 horse can only have 1 session per day.
            </small>
          </label>

          <label>
            Planned duration (minutes, integer divisible by 5)
            <input
              type="number"
              required
              min="15"
              max="720"
              step="5"
              value={form.plannedDuration}
              onChange={(event) =>
                setForm((old) => ({
                  ...old,
                  plannedDuration: event.target.value,
                }))
              }
            />
            <small className="schedule-field-hint">
              Must be an integer divisible by 5 (between 15 and 720 minutes = 12 hours).
            </small>
            {endInfo && (
              <small
                className="schedule-field-hint"
                style={{ color: endInfo.sameDay && endInfo.dur <= 720 ? undefined : "#b91c1c", fontWeight: endInfo.sameDay ? 400 : 600 }}
              >
                {endInfo.dur > 720
                  ? "⚠️ Duration cannot exceed 720 minutes (12 hours)."
                  : endInfo.sameDay
                    ? `Scheduled to end at ${endInfo.endTimeText} on the same day. Auto-completes if overdue.`
                    : `⚠️ Invalid: Session would end at ${endInfo.endTimeText} on the next day. Sessions cannot cross midnight.`}
              </small>
            )}
          </label>

          {!editingSession && (
            <label>
              Max allowed speed (m/s)
              <input
                type="number"
                min="1"
                max="30"
                step="0.1"
                value={form.velocityCeiling}
                onChange={(event) =>
                  setForm((old) => ({
                    ...old,
                    velocityCeiling: event.target.value,
                  }))
                }
                placeholder={selectedPhase?.targetVelocity ? `Default: ${selectedPhase.targetVelocity} m/s` : "e.g. 16.5"}
              />
              <small className="schedule-field-hint">
                Speed readings exceeding this limit trigger a danger alert (same as heart rate ceiling).
              </small>
            </label>
          )}

          <div className="schedule-form-actions" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            {editingSession && (
              <button
                type="button"
                className="schedule-button"
                style={{
                  background: "#fee2e2",
                  color: "#b91c1c",
                  borderColor: "#fca5a5",
                  fontWeight: 700,
                  cursor: "pointer",
                }}
                onClick={onCancel}
                disabled={saving}
              >
                Cancel session
              </button>
            )}
            <div style={{ display: "flex", gap: "8px", marginLeft: "auto" }}>
              <button
                type="button"
                className="schedule-button secondary"
                onClick={onClose}
                disabled={saving}
              >
                Close
              </button>
              <button
                type="submit"
                className="schedule-button primary"
                disabled={
                  saving ||
                  grooms.length === 0 ||
                  (!editingSession && phases.length === 0) ||
                  (endInfo != null && (!endInfo.sameDay || endInfo.dur > 720 || endInfo.dur < 15))
                }
              >
                {saving
                  ? "Saving..."
                  : editingSession
                    ? "Save changes"
                    : "Create session"}
              </button>
            </div>
          </div>
        </form>
      </section>
    </div>
  );
}

export default function SchedulePage() {
  const navigate = useNavigate();

  const [sessions, setSessions] = useState([]);
  const [plans, setPlans] = useState([]);
  const [phases, setPhases] = useState([]);
  const [grooms, setGrooms] = useState([]);
  const [weekStart, setWeekStart] = useState(() => mondayOf(new Date()));

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [sessionTypeChoiceOpen, setSessionTypeChoiceOpen] = useState(false);
  const [isRecoverySession, setIsRecoverySession] = useState(false);
  const [editingSession, setEditingSession] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [now, setNow] = useState(() => new Date());
  const [cancelConfirm, setCancelConfirm] = useState({ isOpen: false, session: null });

  const loadData = useCallback(async (silent = false) => {
    if (!silent) {
      setLoading(true);
      setError("");
    }

    try {
      const [sessionData, planData, groomData] = await Promise.all([
        authFetch(SESSION_API),
        authFetch(`${PLAN_API}?status=ACTIVE`),
        authFetch(`${SESSION_API}/assignees`),
      ]);

      setSessions(Array.isArray(sessionData) ? sessionData : []);
      setPlans(Array.isArray(planData) ? planData : []);
      setGrooms(Array.isArray(groomData) ? groomData : []);
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError(getErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  useEffect(() => {
    if (modalOpen) return undefined;
    const timer = setInterval(() => {
      setNow(new Date());
      loadData(true);
    }, 30000);
    return () => clearInterval(timer);
  }, [loadData, modalOpen]);

  useEffect(() => {
    let cancelled = false;

    async function loadPhases() {
      if (!form.planId || editingSession) {
        setPhases([]);
        return;
      }

      try {
        const plan = await authFetch(`${PLAN_API}/${form.planId}`);
        if (!cancelled) {
          setPhases(Array.isArray(plan.phases) ? plan.phases : []);
          if (plan.groomId) {
            setForm((old) => {
              if (old.assignedTo) return old;
              return { ...old, assignedTo: String(plan.groomId) };
            });
          }
        }
      } catch (err) {
        if (!cancelled && !handleAuthError(err, navigate)) {
          setPhases([]);
          setError(getErrorMessage(err));
        }
      }
    }

    loadPhases();
    return () => {
      cancelled = true;
    };
  }, [form.planId, editingSession, navigate]);

  const weekDays = useMemo(
    () => Array.from({ length: 7 }, (_, index) => addDays(weekStart, index)),
    [weekStart],
  );

  const sessionsByDay = useMemo(() => {
    const result = {};

    for (const day of weekDays) {
      result[dateKey(day)] = [];
    }

    for (const session of sessions) {
      if (session.status === "CANCELLED") continue;
      const key = String(session.sessionDate || "").slice(0, 10);
      if (result[key]) result[key].push(session);
    }

    Object.values(result).forEach((items) => {
      items.sort((a, b) =>
        String(a.sessionDate).localeCompare(String(b.sessionDate)),
      );
    });

    return result;
  }, [sessions, weekDays]);

  const openCreateModal = (recovery = false) => {
    setIsRecoverySession(Boolean(recovery));
    setEditingSession(null);
    setPhases([]);
    const defaultPlan = plans.length === 1 ? plans[0] : null;
    setForm(
      defaultPlan
        ? {
            ...EMPTY_FORM,
            planId: String(defaultPlan.planId),
            assignedTo: defaultPlan.groomId ? String(defaultPlan.groomId) : "",
            plannedDistance: recovery ? "800" : "",
            plannedDuration: recovery ? "30" : "60",
          }
        : {
            ...EMPTY_FORM,
            plannedDistance: recovery ? "800" : "",
            plannedDuration: recovery ? "30" : "60",
          },
    );
    setError("");
    setNotice("");
    setModalOpen(true);
  };

  const openEditModal = (session) => {
    setIsRecoverySession(Boolean(session.remarks && session.remarks.includes("[Recovery Session]")));
    setEditingSession(session);
    setPhases([]);
    setForm({
      planId: "",
      phaseId: String(session.phaseId),
      assignedTo: String(session.assignedTo),
      sessionDate: toLocalDateTimeInput(session.sessionDate),
      plannedDistance: session.plannedDistance ?? "",
      plannedDuration: String(session.plannedDuration ?? 60),
    });
    setError("");
    setNotice("");
    setModalOpen(true);
  };

  const closeModal = () => {
    if (saving) return;
    setModalOpen(false);
    setIsRecoverySession(false);
    setEditingSession(null);
    setPhases([]);
    setForm(EMPTY_FORM);
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (form.plannedDistance !== "" && form.plannedDistance != null) {
      const dist = Number(form.plannedDistance);
      if (!Number.isFinite(dist) || dist <= 0 || dist > 5000) {
        setError("Planned distance must be between 1 and 5,000 meters (maximum 5,000m).");
        return;
      }
      if (isRecoverySession && dist > 800) {
        setError("Recovery session distance cannot exceed 800 meters under Light work protocol.");
        return;
      }
    }

    const durRaw = form.plannedDuration;
    const durNum = Number(durRaw);
    if (
      durRaw === "" ||
      !Number.isInteger(durNum) ||
      durNum < 15 ||
      durNum > 720 ||
      durNum % 5 !== 0
    ) {
      setError("Planned duration must be an integer divisible by 5 (e.g. 15, 20, 25... up to 720 minutes = 12 hours).");
      return;
    }
    const plannedDuration = durNum;
    const endInfo = computeEndInfo(form.sessionDate, form.plannedDuration);
    if (endInfo && !endInfo.sameDay) {
      setError("Session duration cannot cross into the next calendar day.");
      return;
    }

    setSaving(true);
    setError("");
    setNotice("");

    try {
      const sessionDate = toApiDateTime(form.sessionDate);

      if (editingSession) {
        await authFetch(`${SESSION_API}/${editingSession.sessionId}/schedule`, {
          method: "PUT",
          body: JSON.stringify({
            assignedTo: Number(form.assignedTo),
            sessionDate,
            plannedDuration,
          }),
        });
        setNotice("Schedule updated successfully.");
      } else {
        const selectedPhase = phases.find((p) => String(p.phaseId) === String(form.phaseId));
        const body = {
          phaseId: Number(form.phaseId),
          assignedTo: Number(form.assignedTo),
          sessionDate,
          plannedDistance: isRecoverySession
            ? Math.min(800, Number(form.plannedDistance || 800))
            : ((form.plannedDistance || selectedPhase?.distance)
              ? Number(form.plannedDistance || selectedPhase?.distance)
              : null),
          plannedDuration,
          velocityCeiling: form.velocityCeiling !== "" ? Number(form.velocityCeiling) : (selectedPhase?.targetVelocity != null ? Number(selectedPhase.targetVelocity) : null),
          remarks: isRecoverySession ? "[Recovery Session] Prescribed light work rehabilitation" : null,
        };

        await authFetch(SESSION_API, {
          method: "POST",
          body: JSON.stringify(body),
        });
        setNotice(isRecoverySession ? "Recovery session scheduled successfully." : "Training session created successfully.");
      }

      setModalOpen(false);
      setEditingSession(null);
      setForm(EMPTY_FORM);
      await loadData();
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError(getErrorMessage(err));
      }
    } finally {
      setSaving(false);
    }
  };

  const handleCancelSession = () => {
    if (!editingSession) return;
    setCancelConfirm({
      isOpen: true,
      session: editingSession,
    });
  };

  const confirmCancelSession = async () => {
    const sessionToCancel = cancelConfirm.session;
    setCancelConfirm({ isOpen: false, session: null });
    if (!sessionToCancel) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      await authFetch(`${SESSION_API}/${sessionToCancel.sessionId}/cancel`, {
        method: "PUT",
      });
      setNotice("Training session cancelled successfully.");
      setModalOpen(false);
      setEditingSession(null);
      setForm(EMPTY_FORM);
      await loadData();
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError(getErrorMessage(err));
      }
    } finally {
      setSaving(false);
    }
  };

  const today = dateKey(new Date());
  const weekEnd = addDays(weekStart, 6);

  const { requestSignOut, modalElement } = useSignOutModal(navigate);

  return (
    <div className="trainer-page">
      {modalElement}
      <ScheduleSidebar onSignOut={requestSignOut} />

      <main className="trainer-main schedule-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Training Schedule</h1>
            </div>
            <p>Plan sessions and assign the right Groom to each horse.</p>
          </div>

          <div className="schedule-header-right">
            <span className="schedule-role-label">HEAD TRAINER</span>
            <ProfileCircle className="trainer-profile" />
          </div>
        </header>

        <section className="trainer-content schedule-content">
          <div className="schedule-intro">
            <div>
              <span className="schedule-eyebrow">WEEKLY OVERVIEW</span>
              <h2>Your training calendar</h2>
              <p>Review upcoming sessions and manage Groom assignments.</p>
            </div>
            <button
              type="button"
              className="schedule-button primary"
              onClick={() => setSessionTypeChoiceOpen(true)}
            >
              <span aria-hidden="true">＋</span> New session
            </button>
          </div>

          {error && !modalOpen && (
            <div className="schedule-alert error" role="alert">
              {error}
            </div>
          )}
          {notice && !modalOpen && (
            <div className="schedule-alert success" role="status">
              {notice}
            </div>
          )}

          <section className="schedule-toolbar">
            <div className="schedule-week-title">
              <strong>
                {formatDate(weekStart)} – {formatDate(weekEnd)}
              </strong>
              <span>{weekStart.getFullYear()}</span>
            </div>

            <div className="schedule-week-actions">
              <button
                type="button"
                className="schedule-button secondary"
                onClick={() => setWeekStart(mondayOf(new Date()))}
              >
                Today
              </button>
              <button
                type="button"
                className="schedule-arrow"
                aria-label="Previous week"
                onClick={() => setWeekStart((old) => addDays(old, -7))}
              >
                ‹
              </button>
              <button
                type="button"
                className="schedule-arrow"
                aria-label="Next week"
                onClick={() => setWeekStart((old) => addDays(old, 7))}
              >
                ›
              </button>
            </div>
          </section>

          <div className="schedule-legend">
            <span>
              <i className="legend-dot scheduled" /> Scheduled
            </span>
            <span>
              <i className="legend-dot running" /> Running
            </span>
            <span>
              <i className="legend-dot completed" /> Completed
            </span>
          </div>

          {loading ? (
            <div className="schedule-empty">Loading training schedule...</div>
          ) : (
            <div className="schedule-week-grid">
              {weekDays.map((day) => {
                const key = dateKey(day);
                const daySessions = sessionsByDay[key] || [];
                const isToday = key === today;

                return (
                  <section className="schedule-day-column" key={key}>
                    <header
                      className={`schedule-day-heading ${isToday ? "today" : ""}`}
                    >
                      <span>
                        {day.toLocaleDateString("en-US", { weekday: "short" })}
                      </span>
                      <strong>{day.getDate()}</strong>
                      {isToday && <small>Today</small>}
                    </header>

                    <div className="schedule-day-sessions">
                      {daySessions.map((session) => (
                        <SessionCard
                          key={session.sessionId}
                          session={session}
                          onEdit={openEditModal}
                          now={now}
                        />
                      ))}

                      {daySessions.length === 0 && (
                        <div className="schedule-day-empty">No sessions</div>
                      )}
                    </div>
                  </section>
                );
              })}
            </div>
          )}

          <div className="schedule-footer-note">
            <span aria-hidden="true">ⓘ</span>
            Only scheduled sessions can be reassigned or rescheduled.
          </div>
        </section>
      </main>

      <SessionTypeChoiceModal
        isOpen={sessionTypeChoiceOpen}
        onClose={() => setSessionTypeChoiceOpen(false)}
        onSelect={(type) => {
          setSessionTypeChoiceOpen(false);
          openCreateModal(type === "RECOVERY");
        }}
      />

      {modalOpen && (
        <SessionModal
          form={form}
          setForm={setForm}
          plans={plans}
          phases={phases}
          grooms={grooms}
          editingSession={editingSession}
          isRecovery={isRecoverySession}
          saving={saving}
          onClose={closeModal}
          onSubmit={handleSubmit}
          onCancel={handleCancelSession}
        />
      )}

      {error && modalOpen && (
        <div className="schedule-modal-error" role="alert">
          {error}
        </div>
      )}

      <ConfirmModal
        isOpen={cancelConfirm.isOpen}
        title="Cancel Training Session"
        message={`Are you sure you want to cancel the scheduled training session for ${cancelConfirm.session?.horseName || "this horse"}?`}
        confirmText="Yes, Cancel Session"
        cancelText="Keep Session"
        confirmTone="danger"
        icon="⚠️"
        onConfirm={confirmCancelSession}
        onCancel={() => setCancelConfirm({ isOpen: false, session: null })}
      />
    </div>
  );
}