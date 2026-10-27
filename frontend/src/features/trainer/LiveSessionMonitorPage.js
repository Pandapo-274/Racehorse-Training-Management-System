import { useCallback, useEffect, useRef, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import { handleAuthError, logout } from "../auth/authService";
import { fetchAlerts, fetchRunningSessions, resolveAlert } from "./liveSessionApi";
import { toAlertMessage, toSeverity } from "./dashboardApi";
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
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function AlertRow({ alert, busy, onResolve }) {
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
          <button type="button" disabled={busy} onClick={() => onResolve(alert, "VET_CALLED")}>
            Call vet
          </button>
        </div>
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
      ]);
      const nextAlerts = results[0] || [];
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
        const [newAlerts, runningSessions] = await Promise.all([
          fetchAlerts({ afterId: latestAlertId.current, openOnly: true, limit: 100 }),
          fetchRunningSessions(),
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
  }, [showHistory]);

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

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  const openAlerts = alerts.filter((alert) => !alert.resolved);
  const highAlerts = openAlerts.filter((alert) => alert.severity === "HIGH").length;

  return (
    <div className="trainer-page">
      <LiveSessionSidebar onSignOut={handleSignOut} />
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
                    />
                  ))}
                </div>
              )}
            </section>
          </div>
        </section>
      </main>
    </div>
  );
}
