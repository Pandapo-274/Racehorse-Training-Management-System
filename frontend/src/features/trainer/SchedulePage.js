import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { authFetch, handleAuthError, logout } from "../auth/authService";
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
  const navigate = useNavigate();

  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.png" alt="TENMA logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="trainer-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="trainer-nav">
        <button
          type="button"
          className="trainer-nav-item"
          onClick={() => navigate("/trainer")}
        >
          <HorseshoeIcon />
          Herd progress
        </button>

        <button
          type="button"
          className="trainer-nav-item"
          onClick={() => navigate("/trainer/horses")}
        >
          <HorseshoeIcon />
          Horses
        </button>

        <button
          type="button"
          className="trainer-nav-item"
          onClick={() => navigate("/trainer/training-plans")}
        >
          <HorseshoeIcon />
          Training plans
        </button>

        <button
          type="button"
          className="trainer-nav-item active"
          onClick={() => navigate("/trainer/schedule")}
        >
          <HorseshoeIcon />
          Schedule
        </button>

        <button
          type="button"
          className="trainer-nav-item"
          onClick={() => navigate("/trainer/live-session")}
        >
          <HorseshoeIcon />
          Live session
        </button>

        <button
          type="button"
          className="trainer-nav-item"
          onClick={() => navigate("/trainer/session-review")}
        >
          <HorseshoeIcon />
          Session review
        </button>

        <button type="button" className="trainer-nav-item">
          <HorseshoeIcon />
          Races
        </button>
      </nav>



      <div className="trainer-sidebar-bottom">
        <button type="button" className="trainer-signout" onClick={onSignOut}>
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

function SessionCard({ session, onEdit }) {
  const canEdit = session.status === "SCHEDULED";

  return (
    <article
      className={`schedule-session status-${String(
        session.status || "",
      ).toLowerCase()}`}
    >
      <div className="schedule-session-time">
        {formatTime(session.sessionDate)}
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

      <span className="schedule-status">{session.status || "UNKNOWN"}</span>

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
}) {
  const selectedPlan = plans.find(
    (plan) => String(plan.planId) === String(form.planId),
  );

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
                ? "Change the assigned Groom and session time."
                : "Choose an active plan, a training phase and a Groom."}
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

        <form className="schedule-form" onSubmit={onSubmit}>
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
                    }));
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
                Planned distance (meters)
                <input
                  type="number"
                  min="1"
                  step="1"
                  value={form.plannedDistance}
                  onChange={(event) =>
                    setForm((old) => ({
                      ...old,
                      plannedDistance: event.target.value,
                    }))
                  }
                  placeholder="Use phase distance if left empty"
                />
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
            Assigned Groom
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
            {grooms.length === 0 && (
              <small className="schedule-field-hint">
                No active Groom accounts are available.
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
          </label>

          <div className="schedule-form-actions">
            <button
              type="button"
              className="schedule-button secondary"
              onClick={onClose}
              disabled={saving}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="schedule-button primary"
              disabled={
                saving ||
                grooms.length === 0 ||
                (!editingSession && phases.length === 0)
              }
            >
              {saving
                ? "Saving..."
                : editingSession
                  ? "Save changes"
                  : "Create session"}
            </button>
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

  const loadData = useCallback(async () => {
    setLoading(true);
    setError("");

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

  const today = dateKey(new Date());
  const weekEnd = addDays(weekStart, 6);

  return (
    <div className="trainer-page">
      <ScheduleSidebar
        onSignOut={() => {
          // Cùng luồng đăng xuất với Trainer Dashboard: xoá token rồi mới chuyển trang.
          logout();
          navigate("/login", { replace: true });
        }}
      />

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