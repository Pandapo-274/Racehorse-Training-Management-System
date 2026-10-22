import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import { handleAuthError, logout } from "../auth/authService";
import { evaluateSession, fetchSessions } from "./sessionEvaluationApi";
import "./trainer.css";
import "./schedule.css";
import "./session-evaluation.css";

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

function SessionEvaluationSidebar({ onSignOut }) {
  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.png" alt="Tracen logo" />
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

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

function statusLabel(status) {
  return String(status || "UNKNOWN").replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());
}

function emptyForm(session) {
  return {
    actualDistance: session?.actualDistance ?? session?.plannedDistance ?? "",
    durationMinutes: session?.durationMinutes ?? "",
    avgHeartRate: session?.avgHeartRate ?? "",
    peakHeartRate: session?.peakHeartRate ?? "",
    recoveryHeartRate: session?.recoveryHeartRate ?? "",
    peakVelocity: session?.peakVelocity ?? "",
    evaluation: session?.evaluation || "GOOD",
    remarks: session?.remarks || "",
    status: session?.status === "STOPPED_EARLY" ? "STOPPED_EARLY" : "COMPLETED",
  };
}

export default function SessionEvaluationPage() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [form, setForm] = useState(emptyForm(null));
  const [filter, setFilter] = useState("NEEDS_REVIEW");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadSessions = useCallback(async (signal) => {
    setError("");
    try {
      const result = await fetchSessions(signal);
      const next = result || [];
      setSessions(next);
      setSelectedId((current) => {
        if (current && next.some((item) => item.sessionId === current)) return current;
        return (
          next.find((item) => item.status !== "SCHEDULED" && !item.evaluation)?.sessionId ??
          next.find((item) => item.status !== "SCHEDULED")?.sessionId ??
          null
        );
      });
    } catch (err) {
      if (err?.name !== "AbortError" && !handleAuthError(err, navigate)) {
        setError(err.message || "Unable to load training sessions.");
      }
    } finally {
      setLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    const controller = new AbortController();
    loadSessions(controller.signal);
    return () => controller.abort();
  }, [loadSessions]);

  const selectedSession = useMemo(
    () => sessions.find((item) => item.sessionId === selectedId) || null,
    [sessions, selectedId]
  );

  useEffect(() => {
    setForm(emptyForm(selectedSession));
    setNotice("");
  }, [selectedSession]);

  const visibleSessions = useMemo(
    () =>
      sessions
        .filter((session) => session.status !== "SCHEDULED")
        .filter(
          (session) =>
            filter === "ALL" ||
            (filter === "NEEDS_REVIEW" ? !session.evaluation : session.evaluation === filter)
        )
        .sort((a, b) => new Date(b.sessionDate) - new Date(a.sessionDate)),
    [sessions, filter]
  );

  const updateField = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
    setNotice("");
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!selectedSession) return;
    setSaving(true);
    setError("");
    setNotice("");
    const payload = {
      actualDistance: Number(form.actualDistance),
      durationMinutes: Number(form.durationMinutes),
      avgHeartRate: Number(form.avgHeartRate),
      peakHeartRate: Number(form.peakHeartRate),
      recoveryHeartRate: form.recoveryHeartRate === "" ? null : Number(form.recoveryHeartRate),
      peakVelocity: form.peakVelocity === "" ? null : Number(form.peakVelocity),
      evaluation: form.evaluation,
      remarks: form.remarks.trim() || null,
      status: form.status,
    };
    try {
      const updated = await evaluateSession(selectedSession.sessionId, payload);
      setSessions((current) => current.map((item) => item.sessionId === updated.sessionId ? updated : item));
      setForm(emptyForm(updated));
      setNotice(`Session #${updated.sessionId} evaluation saved successfully.`);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message || "Unable to save this evaluation.");
    } finally {
      setSaving(false);
    }
  };

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="trainer-page">
      <SessionEvaluationSidebar onSignOut={handleSignOut} />
      <main className="trainer-main schedule-main session-review-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Session Review</h1>
            </div>
            <p>Record workout results and evaluate horse performance after a training session.</p>
          </div>
          <div className="trainer-header-actions">
            <button
              type="button"
              className="session-review-refresh"
              onClick={() => {
                setLoading(true);
                loadSessions();
              }}
              disabled={loading}
            >
              Refresh
            </button>

            <ProfileCircle className="trainer-profile" />
          </div>
        </header>

        {error && <div className="session-review-message error" role="alert">{error}</div>}
        {notice && <div className="session-review-message success" role="status">{notice}</div>}

        <section className="session-review-layout">
          <div className="session-review-panel session-review-list-panel">
            <div className="session-review-panel-heading">
              <div>
                <h2>Training sessions</h2>
                <p>
                  {visibleSessions.length} session{visibleSessions.length === 1 ? "" : "s"}
                </p>
              </div>
              <select aria-label="Filter sessions" value={filter} onChange={(event) => setFilter(event.target.value)}>
                <option value="NEEDS_REVIEW">Needs review</option>
                <option value="ALL">All sessions</option>
                <option value="POOR">Poor</option>
                <option value="FAIR">Fair</option>
                <option value="GOOD">Good</option>
                <option value="EXCELLENT">Excellent</option>
              </select>
            </div>
            {loading ? <p className="session-review-empty">Loading sessions…</p> : visibleSessions.length === 0 ? (
              <p className="session-review-empty">No sessions match this filter.</p>
            ) : (
              <div className="session-review-list">
                {visibleSessions.map((session) => (
                  <button
                    type="button"
                    key={session.sessionId}
                    className={`session-review-item ${session.sessionId === selectedId ? "selected" : ""}`}
                    onClick={() => {
                      setSelectedId(session.sessionId);
                      setError("");
                    }}
                  >
                    <span className="session-review-item-top">
                      <strong>{session.horseName || `Horse #${session.horseId}`}</strong>
                      <span
                        className={`session-review-status status-${String(session.status).toLowerCase()}`}
                      >
                        {statusLabel(session.status)}
                      </span>
                    </span>
                    <span className="session-review-item-meta">
                      Session #{session.sessionId} · {formatDate(session.sessionDate)}
                    </span>
                    <span className="session-review-item-meta">
                      {session.phaseName || `Phase #${session.phaseId}`} · Groom:{" "}
                      {session.assignedToName || `#${session.assignedTo}`}
                    </span>
                    <span className="session-review-item-bottom">
                      <span>
                        {session.evaluation
                          ? `Rating: ${statusLabel(session.evaluation)}`
                          : "Awaiting evaluation"}
                      </span>
                      <span>
                        {session.actualDistance == null ? "No result yet" : `${session.actualDistance} m`}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="session-review-panel session-review-form-panel">
            {!selectedSession ? (
              <div className="session-review-empty session-review-no-selection">
                <h2>Select a session</h2>
                <p>Choose a started session from the list to record its results.</p>
              </div>
            ) : (
              <>
                <div className="session-review-panel-heading session-review-detail-heading">
                  <div>
                    <h2>{selectedSession.horseName || `Horse #${selectedSession.horseId}`}</h2>
                    <p>
                      Session #{selectedSession.sessionId} · {formatDate(selectedSession.sessionDate)}
                    </p>
                  </div>
                  <span
                    className={`session-review-status status-${String(selectedSession.status).toLowerCase()}`}
                  >
                    {statusLabel(selectedSession.status)}
                  </span>
                </div>
                {selectedSession.status === "SCHEDULED" ? (
                  <p className="session-review-empty">This session has not started and cannot be evaluated yet.</p>
                ) : (
                  <form className="session-review-form" onSubmit={handleSubmit}>
                    <div className="session-review-context">
                      <span>
                        <small>Training phase</small>
                        <strong>{selectedSession.phaseName || `Phase #${selectedSession.phaseId}`}</strong>
                      </span>
                      <span>
                        <small>Planned distance</small>
                        <strong>{selectedSession.plannedDistance ?? "—"} m</strong>
                      </span>
                      <span>
                        <small>Heart-rate ceiling</small>
                        <strong>{selectedSession.heartRateCeiling ?? "—"} bpm</strong>
                      </span>
                    </div>
                    <h3>Actual results</h3>
                    <div className="session-review-fields">
                      <label>
                        Actual distance (m)
                        <input
                          name="actualDistance"
                          type="number"
                          min="0"
                          max="100000"
                          step="1"
                          value={form.actualDistance}
                          onChange={updateField}
                          required
                        />
                      </label>
                      <label>
                        Duration (minutes)
                        <input
                          name="durationMinutes"
                          type="number"
                          min="1"
                          max="600"
                          step="1"
                          value={form.durationMinutes}
                          onChange={updateField}
                          required
                        />
                      </label>
                      <label>
                        Average heart rate (bpm)
                        <input
                          name="avgHeartRate"
                          type="number"
                          min="20"
                          max="300"
                          step="1"
                          value={form.avgHeartRate}
                          onChange={updateField}
                          required
                        />
                      </label>
                      <label>
                        Peak heart rate (bpm)
                        <input
                          name="peakHeartRate"
                          type="number"
                          min="20"
                          max="300"
                          step="1"
                          value={form.peakHeartRate}
                          onChange={updateField}
                          required
                        />
                      </label>
                      <label>
                        Recovery heart rate (bpm)
                        <input
                          name="recoveryHeartRate"
                          type="number"
                          min="20"
                          max="300"
                          step="1"
                          value={form.recoveryHeartRate}
                          onChange={updateField}
                        />
                      </label>
                      <label>
                        Peak velocity
                        <input
                          name="peakVelocity"
                          type="number"
                          min="0"
                          max="99.99"
                          step="0.01"
                          value={form.peakVelocity}
                          onChange={updateField}
                        />
                      </label>
                    </div>
                    <h3>Performance evaluation</h3>
                    <div className="session-review-fields">
                      <label>
                        Overall rating
                        <select name="evaluation" value={form.evaluation} onChange={updateField} required>
                          <option value="POOR">Poor</option>
                          <option value="FAIR">Fair</option>
                          <option value="GOOD">Good</option>
                          <option value="EXCELLENT">Excellent</option>
                        </select>
                      </label>
                      <label>
                        Final session status
                        <select name="status" value={form.status} onChange={updateField} required>
                          <option value="COMPLETED">Completed</option>
                          <option value="STOPPED_EARLY">Stopped early</option>
                        </select>
                      </label>
                    </div>
                    <label className="session-review-remarks">
                      Trainer remarks
                      <textarea
                        name="remarks"
                        value={form.remarks}
                        onChange={updateField}
                        maxLength={2000}
                        rows={4}
                        placeholder="Record observations, fatigue, form, or recommended follow-up…"
                      />
                    </label>
                    <div className="session-review-form-footer">
                      <span>
                        {selectedSession.evaluation
                          ? "Updating the existing evaluation will replace its current values."
                          : "Evaluation is saved to the training session record."}
                      </span>
                      <button type="submit" disabled={saving}>
                        {saving
                          ? "Saving…"
                          : selectedSession.evaluation
                            ? "Update evaluation"
                            : "Save evaluation"}
                      </button>
                    </div>
                  </form>
                )}
              </>
            )}
          </div>
        </section>
      </main>
    </div>
  );
}
