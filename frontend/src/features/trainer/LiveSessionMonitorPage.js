import { useSignOutModal } from "../auth/SignOutModal";
import { parseServerTime } from "../horse/horseApi";
import { useCallback, useEffect, useRef, useState } from "react";
import NavItems, { useScrollToHash } from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import { handleAuthError } from "../auth/authService";
import {
  dismissIncident, fetchAlerts, fetchPendingIncidents, fetchRunningSessions, finishSession, resolveAlert,
} from "./liveSessionApi";
import { toAlertMessage, toSeverity } from "./dashboardApi";
import {
  createVetCall,
  listVetCalls,
  decisionLabel,
  urgencyLabel,
  URGENCIES,
  SIGN_SUGGESTIONS,
} from "../vetcall/vetCallApi";
import "./trainer.css";
import "./schedule.css";
import "./live-session.css";

const POLL_MS = 4000;

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

function LiveSessionSidebar({ onSignOut }) {
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

function formatTime(value) {
  if (!value) return "—";
  const date = parseServerTime(value) || new Date(NaN); // triggeredAt là giờ UTC
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function AlertRow({ alert, busy, onResolve, onCallVet }) {
  const severity = toSeverity(alert.severity);
  return (
    <article className={`live-alert-row severity-${severity.cls}`}>
      <div className="live-alert-severity">{severity.label}</div>
      <div className="live-alert-copy">
        <div className="live-alert-heading">
          <strong>{alert.horseName || `Horse #${alert.horseId}`}</strong>
          <span>Session #{alert.sessionId}</span>
        </div>
        <p>{toAlertMessage(alert)}</p>
        <small>Triggered {formatTime(alert.triggeredAt)}</small>
        {alert.resolved && (
          <small className="live-resolved-by">
            Resolved: {alert.actionTaken}
            {alert.resolvedByName ? ` · ${alert.resolvedByName}` : ""}
          </small>
        )}
      </div>
      {!alert.resolved && (
        <div className="live-alert-actions">
          <button type="button" disabled={busy} onClick={() => onResolve(alert, "CONTINUE")}>
            Continue
          </button>
          <button type="button" disabled={busy} onClick={() => onResolve(alert, "REDUCE_INTENSITY")}>
            Reduce
          </button>
          <button
            type="button"
            className="danger"
            disabled={busy}
            onClick={() => onResolve(alert, "STOP_SESSION")}
          >
            Stop session
          </button>
          <button type="button" disabled={busy} onClick={() => onCallVet(alert)}>
            Call vet
          </button>
        </div>
      )}
    </article>
  );
}

/**
 * Form gọi bác sĩ: lý do (điền sẵn từ cảnh báo), dấu hiệu quan sát, mức khẩn cấp,
 * có dừng buổi tập ngay không. Cảnh báo HIGH mặc định dừng buổi tập.
 */
function VetCallDialog({ alert, onClose, onSent, navigate }) {
  const [form, setForm] = useState(() => ({
    reason: `${toAlertMessage(alert)}.`,
    observations: "",
    urgency: ["HIGH", "MEDIUM", "LOW"].includes(alert.severity) ? alert.severity : "HIGH",
    stopSession: alert.severity === "HIGH",
  }));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const addSign = (sign) =>
    setForm((old) => {
      const parts = old.observations.split(",").map((x) => x.trim()).filter(Boolean);
      if (parts.includes(sign)) return old;
      return { ...old, observations: [...parts, sign].join(", ") };
    });

  const submit = async (event) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const call = await createVetCall(alert.alertId, {
        reason: form.reason.trim(),
        observations: form.observations.trim() || null,
        urgency: form.urgency,
        stopSession: form.stopSession,
      });
      onSent(call);
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError({ message: err.message, details: Object.values(err.fieldErrors || {}) });
      }
      setSaving(false);
    }
  };

  return (
    <div
      className="schedule-modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !saving) onClose();
      }}
    >
      <section className="schedule-modal" role="dialog" aria-modal="true" aria-labelledby="vet-call-title">
        <div className="schedule-modal-heading">
          <div>
            <span className="schedule-eyebrow">FITNESS SAFETY</span>
            <h2 id="vet-call-title">Call the vet</h2>
            <p>
              {alert.horseName || `Horse #${alert.horseId}`} · Session #{alert.sessionId} ·{" "}
              {toAlertMessage(alert)}
            </p>
          </div>
          <button type="button" className="schedule-close-button" onClick={onClose} disabled={saving}
            aria-label="Close dialog">
            ×
          </button>
        </div>

        <form className="schedule-form" onSubmit={submit}>
          {error && (
            <div className="schedule-alert error" role="alert">
              <strong>{error.message}</strong>
              {error.details.filter((d) => d !== error.message).map((d) => <div key={d}>{d}</div>)}
            </div>
          )}
          <label>
            Why are you calling?
            <textarea
              required
              maxLength={500}
              rows={2}
              value={form.reason}
              onChange={(e) => setForm((old) => ({ ...old, reason: e.target.value }))}
            />
          </label>
          <div className="vet-call-field">
            <span className="vet-call-field-label">What do you see?</span>
            <span className="schedule-field-hint">Tap a sign to add it, or type your own.</span>
            <div className="vet-call-signs">
              {SIGN_SUGGESTIONS.map((sign) => (
                <button type="button" key={sign} onClick={() => addSign(sign)}>
                  + {sign}
                </button>
              ))}
            </div>
            <textarea
              aria-label="What do you see?"
              maxLength={1000}
              rows={2}
              placeholder="e.g. Heavy sweating, short stride on the left fore"
              value={form.observations}
              onChange={(e) => setForm((old) => ({ ...old, observations: e.target.value }))}
            />
          </div>
          <label>
            Urgency
            <select value={form.urgency} onChange={(e) => setForm((old) => ({ ...old, urgency: e.target.value }))}>
              {URGENCIES.map((u) => <option key={u.value} value={u.value}>{u.label}</option>)}
            </select>
          </label>
          <label className="vet-call-check">
            <input
              type="checkbox"
              checked={form.stopSession}
              onChange={(e) => setForm((old) => ({ ...old, stopSession: e.target.checked }))}
            />
            <span>Stop the session now and keep the horse still until the vet answers</span>
          </label>
          <div className="schedule-form-actions">
            <button type="button" className="schedule-button secondary" onClick={onClose} disabled={saving}>
              Cancel
            </button>
            <button type="submit" className="schedule-button primary" disabled={saving || !form.reason.trim()}>
              {saving ? "Calling…" : "Call vet"}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

/** Một lần gọi bác sĩ trên màn HLV: đang chờ, hoặc bác sĩ đã dặn gì. */
function VetCallCard({ call }) {
  const answered = call.status === "ANSWERED";
  return (
    <article className={`vet-call-card ${answered ? `decision-${call.decision.toLowerCase()}` : "pending"}`}>
      <div className="live-alert-heading">
        <strong>{call.horseName}</strong>
        <span>Session #{call.sessionId} · called {formatTime(call.calledAt)}</span>
      </div>
      <p className="vet-call-reason">
        <b>{urgencyLabel(call.urgency)}</b> · {call.reason}
        {call.observations ? ` · Seen: ${call.observations}` : ""}
        {call.sessionStopped ? " · Session stopped" : ""}
      </p>
      {answered ? (
        <div className="vet-call-answer">
          <strong>{decisionLabel(call.decision)}</strong>
          <p>{call.instructions}</p>
          {call.medication && <p><b>Give now:</b> {call.medication}</p>}
          <small>
            {call.answeredByName} · {formatTime(call.answeredAt)}
            {call.reportId ? ` · Examination case #${call.reportId} opened` : ""}
          </small>
        </div>
      ) : (
        <div className="vet-call-waiting">Waiting for a veterinarian to answer…</div>
      )}
    </article>
  );
}

export default function LiveSessionMonitorPage() {
  const navigate = useNavigate();
  const [alerts, setAlerts] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [showHistory, setShowHistory] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [busyAlertId, setBusyAlertId] = useState(null);
  const [finishingId, setFinishingId] = useState(null);
  const [incidents, setIncidents] = useState([]);
  const [dismissingId, setDismissingId] = useState(null);
  const [vetCalls, setVetCalls] = useState([]);
  const [callingFor, setCallingFor] = useState(null);
  const latestAlertId = useRef(0);

  const handleError = useCallback((err) => {
    if (err?.name === "AbortError") return;
    if (!handleAuthError(err, navigate)) setError(err.message || "Unable to load live session data.");
  }, [navigate]);

  const loadInitial = useCallback(async (signal, includeHistory) => {
    setError("");
    try {
      const results = await Promise.all([
        fetchAlerts({ openOnly: !includeHistory, limit: includeHistory ? 100 : 50, signal }),
        fetchRunningSessions(signal),
        listVetCalls({ limit: 10, signal }),
        fetchPendingIncidents(signal),
      ]);
      const nextAlerts = results[0] || [];
      setVetCalls(results[2] || []);
      setIncidents(results[3] || []);
      setAlerts(nextAlerts);
      latestAlertId.current = nextAlerts.reduce((max, item) => Math.max(max, item.alertId || 0), 0);
      setSessions(results[1] || []);
    } catch (err) {
      handleError(err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [handleError]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    loadInitial(controller.signal, showHistory);
    return () => controller.abort();
  }, [loadInitial, showHistory]);

  useEffect(() => {
    if (showHistory) return undefined;
    let cancelled = false;
    const poll = async () => {
      setRefreshing(true);
      try {
        const [newAlerts, runningSessions, calls, pendingIncidents] = await Promise.all([
          fetchAlerts({ afterId: latestAlertId.current, openOnly: true, limit: 100 }),
          fetchRunningSessions(),
          listVetCalls({ limit: 10 }),
          fetchPendingIncidents(),
        ]);
        if (cancelled) return;
        if (newAlerts?.length) {
          latestAlertId.current = newAlerts.reduce(
            (max, item) => Math.max(max, item.alertId || 0),
            latestAlertId.current
          );
          setAlerts((current) => {
            const byId = new Map(current.map((item) => [item.alertId, item]));
            newAlerts.forEach((item) => byId.set(item.alertId, item));
            return Array.from(byId.values()).sort((a, b) => b.alertId - a.alertId);
          });
        }
        setSessions(runningSessions || []);
        setVetCalls(calls || []);
        setIncidents(pendingIncidents || []);
        setError("");
      } catch (err) {
        if (!cancelled && err?.name !== "AbortError") {
          if (!handleAuthError(err, navigate)) setError(err.message || "Live refresh failed.");
        }
      } finally {
        if (!cancelled) setRefreshing(false);
      }
    };
    const timer = setInterval(poll, POLL_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, [showHistory, navigate]);

  const handleResolve = async (alert, actionTaken) => {
    setBusyAlertId(alert.alertId);
    setActionError("");
    try {
      const updated = await resolveAlert(alert.alertId, actionTaken);
      setAlerts((current) => showHistory
        ? current.map((item) => item.alertId === alert.alertId ? updated : item)
        : current.filter((item) => item.alertId !== alert.alertId));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message || "Unable to resolve this alert.");
    } finally {
      setBusyAlertId(null);
    }
  };

  const handleFinish = async (session) => {
    if (!window.confirm(`Finish the session with ${session.horseName || `horse #${session.horseId}`}?`)) return;
    setFinishingId(session.sessionId);
    setActionError("");
    try {
      await finishSession(session.sessionId);
      setSessions((current) => current.filter((item) => item.sessionId !== session.sessionId));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message || "Unable to finish this session.");
    } finally {
      setFinishingId(null);
    }
  };

  /* Báo động giả (Groom bấm Stop khi ngựa chỉ vấp nhẹ): Head Trainer ở sân thẩm
     định và gỡ chặn, bắt buộc ghi lý do. Bác sĩ đã nhận ca thì không còn nút này. */
  const handleDismiss = async (report) => {
    const reason = window.prompt(
      `Why is the report for ${report.horseName} (${report.symptoms}) a false alarm?`
        + " The vet, the owner and the groom will see this reason.",
    );
    if (reason == null) return;
    if (!reason.trim()) {
      setActionError("Give a reason to dismiss the report.");
      return;
    }
    setDismissingId(report.reportId);
    setActionError("");
    try {
      await dismissIncident(report.reportId, reason.trim());
      setIncidents((current) => current.filter((r) => r.reportId !== report.reportId));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message || "Unable to dismiss this report.");
    } finally {
      setDismissingId(null);
    }
  };

  const handleVetCallSent = (call) => {
    setCallingFor(null);
    setVetCalls((current) => [call, ...current.filter((c) => c.callId !== call.callId)]);
    setAlerts((current) => showHistory
      ? current.map((item) => item.alertId === call.alertId
        ? { ...item, resolved: true, actionTaken: "VET_CALLED" }
        : item)
      : current.filter((item) => item.alertId !== call.alertId));
  };

  useScrollToHash(!loading);

  const { requestSignOut, modalElement } = useSignOutModal(navigate);

  const openAlerts = alerts.filter((alert) => !alert.resolved);
  const highAlerts = openAlerts.filter((alert) => alert.severity === "HIGH").length;

  return (
    <div className="trainer-page">
      {modalElement}
      <LiveSessionSidebar onSignOut={requestSignOut} />
      <main className="trainer-main schedule-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Live Session Monitor</h1>
            </div>
            <p>Monitor running sessions and respond to fitness threshold alerts.</p>
          </div>
          <div className="schedule-header-right">
            <span className="schedule-role-label">HEAD TRAINER</span>
            <ProfileCircle className="trainer-profile" />
          </div>
        </header>

        <section className="trainer-content live-session-content">
          {error && (
            <div className="schedule-alert error" role="alert">
              <strong>{error}</strong>
              <button
                type="button"
                className="schedule-button secondary"
                onClick={() => {
                  setLoading(true);
                  setRefreshing(true);
                  loadInitial(undefined, showHistory);
                }}
              >
                Retry
              </button>
            </div>
          )}
          {actionError && <div className="schedule-alert error" role="alert">{actionError}</div>}

          <div className="live-session-summary">
            <div className="live-summary-card"><span>Running sessions</span><strong>{sessions.length}</strong></div>
            <div className="live-summary-card"><span>Open alerts</span><strong>{openAlerts.length}</strong></div>
            <div className="live-summary-card urgent"><span>High severity</span><strong>{highAlerts}</strong></div>
            <div className="live-summary-status">
              <span className={refreshing ? "live-status-dot refreshing" : "live-status-dot"} />
              {refreshing ? "Refreshing" : "Live updates every 4 seconds"}
            </div>
          </div>

          <div className="live-session-columns">
            <section className="live-panel">
              <div className="live-panel-heading">
                <div>
                  <span className="schedule-eyebrow">LIVE TRACKING</span>
                  <h2>Running sessions</h2>
                </div>
                <span className="live-count">{sessions.length}</span>
              </div>
              {loading && sessions.length === 0 ? (
                <p className="live-empty">Loading sessions…</p>
              ) : sessions.length === 0 ? (
                <p className="live-empty">No training sessions are running right now.</p>
              ) : (
                <div className="live-running-list">
                  {sessions.map((session) => (
                    <article className="live-running-card" key={session.sessionId}>
                      <div className="live-running-top">
                        <strong>{session.horseName || `Horse #${session.horseId}`}</strong>
                        <span>RUNNING</span>
                      </div>
                      <p>{session.phaseName || `Phase ${session.phaseNo}`} · Session #{session.sessionId}</p>
                      <div className="live-metrics">
                        <div><span>HR ceiling</span><strong>{session.heartRateCeiling ?? "—"} bpm</strong></div>
                        <div><span>Peak HR</span><strong>{session.peakHeartRate ?? "—"} bpm</strong></div>
                        <div><span>Peak speed</span><strong>{session.peakVelocity ?? "—"}</strong></div>
                        <div><span>Alerts raised</span><strong>{session.alertCount ?? 0}</strong></div>
                      </div>
                      <small>Assigned Groom: {session.assignedToName || "Unassigned"}</small>
                      <div className="live-alert-actions">
                        <button
                          type="button"
                          disabled={finishingId === session.sessionId}
                          onClick={() => handleFinish(session)}
                        >
                          {finishingId === session.sessionId ? "Finishing…" : "Finish session"}
                        </button>
                      </div>
                    </article>
                  ))}
                </div>
              )}
            </section>

            <section className="live-panel live-alert-panel">
              <div className="live-panel-heading">
                <div>
                  <span className="schedule-eyebrow">FITNESS SAFETY</span>
                  <h2>{showHistory ? "Alert history" : "Open threshold alerts"}</h2>
                </div>
                <label className="live-history-toggle">
                  <input
                    type="checkbox"
                    checked={showHistory}
                    onChange={(event) => setShowHistory(event.target.checked)}
                  />{" "}
                  Show history
                </label>
              </div>
              {loading && alerts.length === 0 ? (
                <p className="live-empty">Loading alerts…</p>
              ) : alerts.length === 0 ? (
                <p className="live-empty">
                  {showHistory ? "No alerts recorded yet." : "No unresolved fitness alerts."}
                </p>
              ) : (
                <div className="live-alert-list">
                  {alerts.map((alert) => (
                    <AlertRow
                      key={alert.alertId}
                      alert={alert}
                      busy={busyAlertId === alert.alertId}
                      onResolve={handleResolve}
                      onCallVet={setCallingFor}
                    />
                  ))}
                </div>
              )}
            </section>
          </div>

          <section className="live-panel vet-call-panel" id="vet-calls">
            <div className="live-panel-heading">
              <div>
                <span className="schedule-eyebrow">VETERINARY ADVICE</span>
                <h2>Vet calls</h2>
              </div>
              <span className="live-count" title="Waiting for an answer">
                {vetCalls.filter((c) => c.status === "PENDING").length}
              </span>
            </div>
            {vetCalls.length === 0 ? (
              <p className="live-empty">
                No vet calls yet. Use Call vet on an alert to ask a veterinarian for advice.
              </p>
            ) : (
              <div className="vet-call-list">
                {vetCalls.map((call) => <VetCallCard key={call.callId} call={call} />)}
              </div>
            )}
          </section>

          <section className="live-panel" id="stopped-sessions">
            <div className="live-panel-heading">
              <div>
                <span className="schedule-eyebrow">WAITING FOR A VET</span>
                <h2>Stopped sessions</h2>
              </div>
              <span className="live-count">{incidents.filter((r) => r.sessionId != null).length}</span>
            </div>
            {incidents.filter((r) => r.sessionId != null).length === 0 ? (
              <p className="live-empty">No stopped session is waiting for a veterinarian.</p>
            ) : (
              <div className="live-running-list">
                {incidents.filter((r) => r.sessionId != null).map((r) => (
                  <article className="live-running-card" key={r.reportId}>
                    <div className="live-running-top">
                      <strong>{r.horseName}</strong>
                      <span>{r.urgency}</span>
                    </div>
                    <p>{r.symptoms} · Session #{r.sessionId}</p>
                    <small>
                      Reported by {r.reporterName} · {formatTime(r.reportedAt)}. The horse cannot train until a
                      vet examines it, unless you dismiss this as a false alarm.
                    </small>
                    <div className="live-alert-actions">
                      <button
                        type="button"
                        disabled={dismissingId === r.reportId}
                        onClick={() => handleDismiss(r)}
                      >
                        {dismissingId === r.reportId ? "Dismissing…" : "False alarm"}
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        </section>
      </main>
      {callingFor && (
        <VetCallDialog
          alert={callingFor}
          navigate={navigate}
          onClose={() => setCallingFor(null)}
          onSent={handleVetCallSent}
        />
      )}
    </div>
  );
}
