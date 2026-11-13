import { useSignOutModal } from "../auth/SignOutModal";
import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { authFetch, handleAuthError } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
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

function SessionModal({
  form,
  setForm,
  plans,
  phases,
  grooms,
  editingSession,
  saving,
  onClose,
  onSubmit,
  onCancel,
}) {
  const selectedPlan = plans.find(
    (plan) => String(plan.planId) === String(form.planId),
  );
  const endInfo = computeEndInfo(form.sessionDate, form.plannedDuration);

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
            <span className="schedule-eyebrow">TRAINING MANAGEMENT</span>
            <h2 id="schedule-modal-title">
              {editingSession ? "Edit schedule" : "Create training session"}
            </h2>
            <p>
              {editingSession
                ? "Change the assigned Groom, session time, and planned duration."
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
          {!editingSession && (
            <>
              <label>
                Training plan
                <select
                  required
                  value={form.planId}
                  onChange={(event) =>
                    setForm((old) => ({
                      ...old,
                      planId: event.target.value,
                      phaseId: "",
                      plannedDistance: "",
                    }))
                  }
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
              </label>

              <label>
                Planned distance (meters, max 5,000)
                <input
                  type="number"
                  min="1"
                  max="5000"
                  step="1"
                  value={form.plannedDistance}
                  onChange={(event) =>
                    setForm((old) => ({
                      ...old,
                      plannedDistance: event.target.value,
                    }))
                  }
                  placeholder="e.g. 1200 (max 5,000m)"
                />
                <small className="schedule-field-hint">
                  Target distance in meters for this session (maximum 5,000m).
                </small>
              </label>
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
            Assigned Groom (managed by you, max 3)
            <select
              required
              value={form.assignedTo}
              onChange={(event) =>
                setForm((old) => ({ ...old, assignedTo: event.target.value }))
              }
            >
              <option value="">Select a Groom</option>
              {grooms.map((groom) => (
                <option key={groom.userId} value={groom.userId}>
                  {groom.fullName}
                </option>
              ))}
            </select>
            {grooms.length === 0 ? (
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
  const [editingSession, setEditingSession] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [now, setNow] = useState(() => new Date());

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

  const openCreateModal = () => {
    setEditingSession(null);
    setPhases([]);
    setForm(EMPTY_FORM);
    setError("");
    setNotice("");
    setModalOpen(true);
  };

  const openEditModal = (session) => {
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
        const body = {
          phaseId: Number(form.phaseId),
          assignedTo: Number(form.assignedTo),
          sessionDate,
          plannedDistance: form.plannedDistance
            ? Number(form.plannedDistance)
            : null,
          plannedDuration,
        };

        await authFetch(SESSION_API, {
          method: "POST",
          body: JSON.stringify(body),
        });
        setNotice("Training session created successfully.");
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

  const handleCancelSession = async () => {
    if (!editingSession) return;
    const confirmed = window.confirm(
      `Are you sure you want to cancel the scheduled training session for ${editingSession.horseName || "this horse"}?`
    );
    if (!confirmed) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      await authFetch(`${SESSION_API}/${editingSession.sessionId}/cancel`, {
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
              onClick={openCreateModal}
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

      {modalOpen && (
        <SessionModal
          form={form}
          setForm={setForm}
          plans={plans}
          phases={phases}
          grooms={grooms}
          editingSession={editingSession}
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
    </div>
  );
}
