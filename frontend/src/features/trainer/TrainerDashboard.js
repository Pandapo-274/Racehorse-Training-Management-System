import { useCallback, useEffect, useState } from "react";
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
  toInitials,
} from "./dashboardApi";

// UC6 - View Master Fitness & Training Dashboard
//
// Bố cục, lớp CSS và cấu trúc thẻ giữ nguyên như bản dựng theo Figma. Thay đổi
// duy nhất: ba mảng dữ liệu cứng (fitnessData, alerts, horses) được thay bằng
// một lần gọi GET /api/trainer/dashboard. Mọi phép quy đổi từ từ vựng cơ sở dữ
// liệu sang từ vựng hiển thị nằm trong dashboardApi.js, không nằm trong JSX.

const RANGE_OPTIONS = [4, 8, 12];

function TrainerSidebar({ user, onSignOut }) {
  const fullName = user?.fullName || "Head Trainer";

  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.svg" alt="Tenma logo" />

        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="trainer-menu-title">◉ MENU</div>

      <nav className="trainer-nav">
        <button className="trainer-nav-item active">
          <span>◉</span>
          Herd progress
        </button>

        <button className="trainer-nav-item">
          <span>◉</span>
          Training plans
        </button>

        <button className="trainer-nav-item">
          <span>◉</span>
          Schedule
        </button>

        <button className="trainer-nav-item">
          <span>◉</span>
          Live session
        </button>

        <button className="trainer-nav-item">
          <span>◉</span>
          Races
        </button>
      </nav>

      <div className="trainer-sidebar-bottom">
        <button className="trainer-signout" onClick={onSignOut}>
          Sign out
        </button>

        <div className="trainer-user">
          <div className="trainer-avatar">{toInitials(fullName)}</div>

          <div>
            <strong>{fullName}</strong>
            <span>Head Trainer</span>
          </div>
        </div>
      </div>
    </aside>
  );
}

function FitnessChart({ data, weeks, onWeeksChange }) {
  // Chia cho max chứ không cho 100: giữ nguyên cách vẽ của bản thiết kế.
  const max = data.length ? Math.max(...data.map((item) => item.value)) : 1;

  return (
    <div className="trainer-card fitness-card">
      <div className="trainer-card-title">
        <span>◆</span>

        <div>
          <h2>Average fitness index</h2>
          <p>Derived from 2-minute recovery heart rate</p>
        </div>

        <div className="fitness-range" role="group" aria-label="Chart range">
          {RANGE_OPTIONS.map((w) => (
            <button
              key={w}
              type="button"
              aria-pressed={w === weeks}
              onClick={() => onWeeksChange(w)}
            >
              {w}w
            </button>
          ))}
        </div>
      </div>

      {data.length === 0 ? (
        <p className="fitness-empty">No completed sessions in this range.</p>
      ) : (
        <div className="fitness-chart">
          {data.map((item, index) => {
            const height = (item.value / max) * 100;

            return (
              <div className="fitness-column" key={item.date}>
                <div className="fitness-bar-wrapper">
                  <div
                    className={`fitness-bar ${
                      index === data.length - 1 ? "highlight" : ""
                    }`}
                    style={{ height: `${height}%` }}
                    title={`${item.value} points · ${item.sessions} sessions`}
                  />
                </div>

                <span>{item.date}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function AlertsCard({ alerts }) {
  return (
    <div className="trainer-card alerts-card">
      <div className="trainer-card-title">
        <span>◆</span>

        <div>
          <h2>Open alerts</h2>
        </div>
      </div>

      {alerts.length === 0 ? (
        <p className="alerts-empty">No open alerts.</p>
      ) : (
        <div className="alerts-list">
          {alerts.map((alert) => {
            const level = toSeverity(alert.severity);

            return (
              <div className={`trainer-alert ${level.cls}`} key={alert.alertId}>
                <div>
                  <strong>{alert.horseName}</strong>
                  <p>{toAlertMessage(alert)}</p>
                </div>

                <span>{level.label}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function HorsesTable({ horses }) {
  return (
    <div className="trainer-card horses-card">
      <div className="trainer-card-title">
        <span>◆</span>

        <div>
          <h2>Horses in your care</h2>
          <p>Pick one to build a plan</p>
        </div>
      </div>

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
            {horses.map((horse) => {
              const form = toFormBadge(horse.lastEvaluation);
              const status = toStatusChip(horse);

              return (
                <tr key={horse.horseId}>
                  <td className="horse-name">{horse.horseName}</td>

                  <td>{horse.currentPhase || "No plan yet"}</td>

                  <td>{toDistance(horse.weeklyDistanceKm)}</td>

                  <td className="fitness-value">
                    {horse.fitnessIndex ?? "—"}
                  </td>

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
    </div>
  );
}

export default function TrainerDashboard() {
  const navigate = useNavigate();
  const [weeks, setWeeks] = useState(8);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const user = JSON.parse(localStorage.getItem("user") || "null");

  const load = useCallback((w, signal) => {
    setLoading(true);
    setError("");
    fetchDashboard(w, signal)
      .then(setData)
      .catch((err) => {
        if (err.name !== "AbortError") setError(err.message);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    load(weeks, ctrl.signal);
    return () => ctrl.abort();
  }, [weeks, load]);

  const handleSignOut = () => {
    localStorage.removeItem("user");
    navigate("/login", { replace: true });
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
              <span>◆</span>
              <h1>Herd progress and fitness</h1>
            </div>

            <p>Live · {today}</p>
          </div>

          <div className="trainer-header-actions">
            <input
              className="trainer-search"
              type="text"
              placeholder="Search horses, records..."
            />

            <button className="trainer-notification">●</button>

            <div className="trainer-profile" />
          </div>
        </header>

        <section className="trainer-content">
          {error && (
            <div className="trainer-state trainer-state--error" role="alert">
              <strong>{error}</strong>
              <p>
                Check that the backend is running on port 8080 and that the
                database has been seeded.
              </p>
              <button
                type="button"
                className="trainer-retry"
                onClick={() => load(weeks)}
              >
                Try again
              </button>
            </div>
          )}

          {loading && !data && (
            <div className="trainer-state">
              <strong>Loading dashboard…</strong>
            </div>
          )}

          {data && (
            <>
              <div className="trainer-top">
                <FitnessChart
                  data={toChartData(data.fitnessTrend)}
                  weeks={weeks}
                  onWeeksChange={setWeeks}
                />
                <AlertsCard alerts={data.openAlerts || []} />
              </div>

              <HorsesTable horses={data.horses || []} />
            </>
          )}
        </section>
      </main>
    </div>
  );
}
