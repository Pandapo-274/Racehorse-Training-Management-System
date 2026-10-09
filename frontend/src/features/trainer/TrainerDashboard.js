import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import "./trainer.css";
import "./trainer-data.css";
import {
  fetchDashboard,
  toFormBadge,
  toStatusChip,
  toAlertMessage,
  toSeverity,
  toDistance,
  toChartData,
} from "./dashboardApi";
import { getUser, logout, handleAuthError } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";

// UC6 - View Master Fitness & Training Dashboard
//
// Dashboard lấy dữ liệu hoàn toàn từ GET /api/trainer/dashboard.
// Các thao tác tìm kiếm/lọc/chọn ngựa được xử lý giống màn /trainer/horses:
// dữ liệu được lấy một lần từ API, sau đó lọc tại client để phản hồi tức thì.

const RANGE_OPTIONS = [4, 8, 12];
const STATUS_FILTERS = [
  { value: "ALL", label: "All" },
  { value: "ACTIVE", label: "Active" },
  { value: "READY", label: "Ready to plan" },
  { value: "MONITOR", label: "Monitor" },
  { value: "LOCKED", label: "Locked" },
];

/* Biểu tượng móng ngựa dùng cho menu (theo Figma) */
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

function TrainerSidebar({ user, onSignOut }) {
  const navigate = useNavigate();

  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.svg" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="trainer-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="trainer-nav">
        <button type="button" className="trainer-nav-item active">
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

        <button type="button" className="trainer-nav-item">
          <HorseshoeIcon />
          Training plans
        </button>

        <button type="button" className="trainer-nav-item">
          <HorseshoeIcon />
          Schedule
        </button>

        <button type="button" className="trainer-nav-item">
          <HorseshoeIcon />
          Live session
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

        <ProfileCard className="trainer-user" avatarClassName="trainer-avatar"
                     role="Head Trainer" />
      </div>
    </aside>
  );
}

function FitnessChart({ data, weeks, onWeeksChange, loading }) {
  const max = data.length ? Math.max(...data.map((item) => item.value), 1) : 1;

  return (
    <div className="trainer-card trainer-fitness-card">
      <div className="trainer-card-title">
        <span className="trainer-diamond" aria-hidden="true" />
        <div>
          <h2>Average fitness index</h2>
          <p>Derived from 2-minute recovery heart rate</p>
        </div>

        <div className="trainer-fitness-range" role="group" aria-label="Chart range">
          {RANGE_OPTIONS.map((w) => (
            <button
              key={w}
              type="button"
              aria-pressed={w === weeks}
              disabled={loading}
              onClick={() => onWeeksChange(w)}
            >
              {w}w
            </button>
          ))}
        </div>
      </div>

      {data.length === 0 ? (
        <p className="trainer-fitness-empty">No completed sessions in this range.</p>
      ) : (
        <div className="trainer-fitness-chart">
          {data.map((item, index) => {
            const height = (item.value / max) * 100;

            return (
              <button
                className="trainer-fitness-column"
                key={item.date}
                type="button"
                title={`${item.value} points · ${item.sessions} sessions · ${item.date}`}
                aria-label={`${item.date}: ${item.value} fitness points from ${item.sessions} sessions`}
              >
                <span className="trainer-fitness-bar-wrapper">
                  <span
                    className={`trainer-fitness-bar ${
                      index === data.length - 1 ? "highlight" : ""
                    }`}
                    style={{ height: `${height}%` }}
                  />
                </span>
                <span className="trainer-fitness-label">{item.date}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function AlertsCard({ alerts, onHorseClick }) {
  return (
    <div className="trainer-card alerts-card" id="open-alerts">
      <div className="trainer-card-title">
        <span className="trainer-diamond" aria-hidden="true" />
        <div>
          <h2>Open alerts</h2>
          <p>{alerts.length} unresolved alert{alerts.length === 1 ? "" : "s"}</p>
        </div>
      </div>

      {alerts.length === 0 ? (
        <p className="alerts-empty">No open alerts.</p>
      ) : (
        <div className="alerts-list">
          {alerts.map((alert) => {
            const level = toSeverity(alert.severity);

            return (
              <button
                type="button"
                className={`trainer-alert ${level.cls}`}
                key={alert.alertId}
                onClick={() => alert.horseId && onHorseClick(alert.horseId)}
                disabled={!alert.horseId}
                title={alert.horseId ? "Open horse details" : "Horse details unavailable"}
              >
                <span className="trainer-alert-copy">
                  <strong>{alert.horseName}</strong>
                  <span>{toAlertMessage(alert)}</span>
                </span>
                <span className="trainer-alert-level">{level.label}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function getVisibleStatus(horse) {
  const status = toStatusChip(horse);
  if (status.cls === "active") return "ACTIVE";
  if (status.cls === "ready-to-plan") return "READY";
  if (status.cls === "locked") return "LOCKED";
  return "MONITOR";
}

function HorsesTable({ horses, query, statusFilter, onStatusChange, onHorseClick }) {
  const normalizedQuery = query.trim().toLowerCase();

  const visible = useMemo(() => {
    return horses.filter((horse) => {
      if (statusFilter !== "ALL" && getVisibleStatus(horse) !== statusFilter) {
        return false;
      }

      if (!normalizedQuery) return true;

      return [
        horse.horseName,
        horse.registrationCode,
        horse.currentPhase,
        horse.status,
      ].some((value) => String(value || "").toLowerCase().includes(normalizedQuery));
    });
  }, [horses, normalizedQuery, statusFilter]);

  const counts = useMemo(() => {
    const result = { ALL: horses.length };
    STATUS_FILTERS.slice(1).forEach((filter) => {
      result[filter.value] = horses.filter(
        (horse) => getVisibleStatus(horse) === filter.value
      ).length;
    });
    return result;
  }, [horses]);

  return (
    <div className="trainer-card horses-card">
      <div className="trainer-card-title trainer-horses-title">
        <span className="trainer-diamond" aria-hidden="true" />
        <div>
          <h2>Horses in your care</h2>
          <p>
            {visible.length === horses.length
              ? `${horses.length} horses`
              : `${visible.length} of ${horses.length} horses`}
          </p>
        </div>
      </div>

      <div className="trainer-filters">
        <div className="trainer-status-segment" role="group" aria-label="Filter horses by dashboard status">
          {STATUS_FILTERS.map((filter) => (
            <button
              key={filter.value}
              type="button"
              aria-pressed={statusFilter === filter.value}
              onClick={() => onStatusChange(filter.value)}
            >
              {filter.label} <b>{counts[filter.value]}</b>
            </button>
          ))}
        </div>
      </div>

      {horses.length === 0 ? (
        <div className="trainer-inline-state">No horses were returned by the dashboard API.</div>
      ) : visible.length === 0 ? (
        <div className="trainer-inline-state">
          Nothing matches the current search/filter. Try another horse or clear the filters.
        </div>
      ) : (
        <div className="trainer-table-wrapper">
          <table className="trainer-table">
            <thead>
              <tr>
                <th>Horses</th>
                <th>Phase</th>
                <th>Weekly distance</th>
                <th>Fitness</th>
                <th>Form</th>
                <th>Status</th>
              </tr>
            </thead>

            <tbody>
              {visible.map((horse) => {
                const form = toFormBadge(horse.lastEvaluation);
                const status = toStatusChip(horse);

                return (
                  <tr
                    key={horse.horseId}
                    className="trainer-table-row"
                    tabIndex={0}
                    role="link"
                    onClick={() => onHorseClick(horse.horseId)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        onHorseClick(horse.horseId);
                      }
                    }}
                  >
                    <td className="horse-name">{horse.horseName}</td>
                    <td>{horse.currentPhase || "No plan yet"}</td>
                    <td>{toDistance(horse.weeklyDistanceKm)}</td>
                    <td className="trainer-fitness-value">{horse.fitnessIndex ?? "—"}</td>
                    <td>
                      {form ? (
                        <span className={`form-badge form-${form.toLowerCase()}`}>
                          {form}
                        </span>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td>
                      <span className={`horse-status ${status.cls}`}>
                        {status.label}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function TrainerDashboard() {
  const navigate = useNavigate();
  const [weeks, setWeeks] = useState(8);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const alertsRef = useRef(null);

  const user = getUser();

  const load = useCallback(
    (w, signal) => {
      setLoading(true);
      setError("");
      fetchDashboard(w, signal)
        .then(setData)
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        })
        .finally(() => setLoading(false));
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(weeks, ctrl.signal);
    return () => ctrl.abort();
  }, [weeks, load]);

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  const openHorse = (horseId) => {
    if (horseId) navigate(`/trainer/horses/${horseId}`);
  };

  const scrollToAlerts = () => {
    alertsRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  const today = new Date().toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  return (
    <div className="trainer-page">
      <TrainerSidebar user={user} onSignOut={handleSignOut} />

      <main className="trainer-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Herd progress and fitness</h1>
            </div>
            <p>Live · {today}</p>
          </div>

          <div className="trainer-header-actions">
            <input
              className="trainer-search"
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search horses, records..."
              aria-label="Search horses and training records"
            />

            <button
              type="button"
              className="trainer-notification"
              onClick={scrollToAlerts}
              title="Jump to open alerts"
              aria-label="Jump to open alerts"
            >
              <span className="trainer-notification-dot" />
            </button>

            <ProfileCircle className="trainer-profile" />
          </div>
        </header>

        <section className="trainer-content">
          {error && (
            <div className="trainer-state trainer-state--error" role="alert">
              <strong>{error}</strong>
              <p>Check that the backend is running on port 8080 and that you are still signed in.</p>
              <button type="button" className="trainer-retry" onClick={() => load(weeks)}>
                Try again
              </button>
            </div>
          )}

          {loading && !data && (
            <div className="trainer-state">
              <strong>Loading dashboard…</strong>
              <p>Getting the latest training data from the API.</p>
            </div>
          )}

          {data && (
            <>
              <div className="trainer-dashboard-meta">
                <div className="trainer-summary-strip">
                  <button type="button" onClick={() => setStatusFilter("ALL")}>
                    <strong>{data.summary?.totalHorses ?? data.horses?.length ?? 0}</strong>
                    <span>Horses</span>
                  </button>
                  <button type="button" onClick={() => setStatusFilter("ACTIVE")}>
                    <strong>{data.summary?.activePlans ?? 0}</strong>
                    <span>Active plans</span>
                  </button>
                  <button type="button" onClick={() => setStatusFilter("LOCKED")}>
                    <strong>{data.summary?.lockedHorses ?? 0}</strong>
                    <span>Locked</span>
                  </button>
                  <button type="button" onClick={scrollToAlerts}>
                    <strong>{data.summary?.openAlerts ?? data.openAlerts?.length ?? 0}</strong>
                    <span>Open alerts</span>
                  </button>
                </div>
                {loading && <span className="trainer-refreshing">Updating…</span>}
              </div>

              <div className="trainer-top">
                <FitnessChart
                  data={toChartData(data.fitnessTrend)}
                  weeks={weeks}
                  onWeeksChange={(nextWeeks) => {
                    setWeeks(nextWeeks);
                    setStatusFilter("ALL");
                  }}
                  loading={loading}
                />

                <div ref={alertsRef}>
                  <AlertsCard alerts={data.openAlerts || []} onHorseClick={openHorse} />
                </div>
              </div>

              <HorsesTable
                horses={data.horses || []}
                query={query}
                statusFilter={statusFilter}
                onStatusChange={setStatusFilter}
                onHorseClick={openHorse}
              />
            </>
          )}
        </section>
      </main>
    </div>
  );
}