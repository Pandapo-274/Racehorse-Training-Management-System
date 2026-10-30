// src/features/manager/ManagerDashboard.js
// Trang "Club overview" của Club Manager.
//
// Đề bài: báo cáo tổng quan về hiệu suất huấn luyện, chi phí vận hành chuồng
// trại và doanh thu giải đấu. Mọi số liệu lấy từ GET /api/manager/dashboard;
// trước đây trang này chỉ có số viết cứng.
import "./manager.css";
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import NavItems from "../navigation/NavItems";
import { handleAuthError, logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import NotificationBell from "../notification/NotificationBell";
import { timeAgo } from "../notification/notificationApi";
import { toStatus } from "../horse/horseApi";
import {
  formatCompactMoney, getManagerDashboard, isoWeek, openItemLink, openItemTone,
} from "./managerDashboardApi";

/* Màu từng trạng thái sức khoẻ: trùng .legend-dot trong manager.css, vì vòng
   tròn vẽ bằng conic-gradient nên phải có mã màu ở đây. */
const HEALTH_STYLE = {
  ELIGIBLE:   { cls: "race-fit",   color: "#8cc63f" },
  MONITOR:    { cls: "monitor",    color: "#ffc53d" },
  INJURED:    { cls: "injured",    color: "#ff5f6d" },
  QUARANTINE: { cls: "quarantine", color: "#f55fa9" },
};

function HorseshoeIcon() {
  return (
    <svg
      className="manager-nav-icon"
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

function ManagerSidebar() {
  const navigate = useNavigate();
  /* Không còn gọi getUser ở đây nữa: ProfileCard tự đọc người đang đăng nhập. */
  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="manager-sidebar">
      <div className="manager-brand">
        <img src="/logo.png" alt="Tracen logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="manager-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="manager-nav">
        <NavItems role="CLUB_MANAGER" prefix="manager" icon={<HorseshoeIcon />} />
      </nav>

      <div className="manager-sidebar-bottom">
        <button
          type="button"
          className="manager-signout"
          onClick={handleSignOut}
        >
          Sign out
        </button>
        <ProfileCard
          className="manager-user"
          avatarClassName="manager-avatar"
          role="Club Manager"
        />
      </div>
    </aside>
  );
}

function StatCard({ value, label, type }) {
  return (
    <div className={`manager-stat-card ${type}`}>
      <div className="manager-stat-value">{value}</div>
      <div className="manager-stat-label">{label}</div>
    </div>
  );
}

function WorkloadChart({ points }) {
  const max = Math.max(0, ...points.map((p) => Number(p.km) || 0));

  return (
    <div className="manager-card workload-card">
      <div className="manager-card-title">
        <span className="manager-diamond" aria-hidden="true" />
        <div>
          <h2>Herd workload</h2>
          <p>Total distance (km) per week, last {points.length} weeks</p>
        </div>
      </div>

      <div className="workload-chart">
        {points.map((point, index) => {
          const km = Number(point.km) || 0;
          const height = max > 0 ? (km / max) * 100 : 0;

          return (
            <div className="workload-column" key={point.weekStart}>
              <div className="workload-bar-wrapper">
                <div
                  className={`workload-bar ${index === points.length - 1 ? "highlight" : ""}`}
                  style={{ height: `${height}%` }}
                  title={`${km.toFixed(1)} km · ${point.sessions} sessions`}
                />
              </div>

              <span>{point.label}</span>
            </div>
          );
        })}
      </div>

      {max === 0 && (
        <p className="workload-empty">No completed training sessions in these weeks.</p>
      )}
    </div>
  );
}

function HealthCard({ health }) {
  const total = health.reduce((sum, h) => sum + h.count, 0);
  const healthy = health.find((h) => h.status === "ELIGIBLE")?.count || 0;
  const percent = total > 0 ? Math.round((healthy / total) * 100) : 0;

  // Mỗi trạng thái một cung, độ dài theo số ngựa.
  let angle = 0;
  const segments = health
    .filter((h) => h.count > 0)
    .map((h) => {
      const from = angle;
      angle += (h.count / total) * 360;
      return `${HEALTH_STYLE[h.status]?.color || "#d9dce8"} ${from}deg ${angle}deg`;
    });

  return (
    <div className="manager-card health-card">
      <div className="manager-card-title">
        <span className="manager-diamond" aria-hidden="true" />
        <div>
          <h2>Herd health status</h2>
          <p>{total} club horses at the club</p>
        </div>
      </div>

      <div className="health-content">
        <div
          className="health-donut"
          style={segments.length ? { background: `conic-gradient(${segments.join(", ")})` } : undefined}
          role="img"
          aria-label={`${percent}% of ${total} horses are healthy`}
        >
          <div className="health-donut-inner">
            <strong>{percent}%</strong>
            <span>Healthy</span>
          </div>
        </div>

        <div className="health-legend">
          {health.map((h) => (
            <div key={h.status}>
              <span className={`legend-dot ${HEALTH_STYLE[h.status]?.cls || ""}`} />
              <strong>{toStatus(h.status).label}</strong>
              <b>{h.count}</b>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function OpenItems({ items, total }) {
  const navigate = useNavigate();

  return (
    <div className="manager-card open-items-card">
      <div className="open-items-header">
        <div className="manager-card-title">
          <span className="manager-diamond" aria-hidden="true" />
          <div>
            <h2>Open items</h2>
            <p className="open-items-hint">
              {total > items.length
                ? `Showing ${items.length} of ${total} waiting items`
                : `${total} waiting ${total === 1 ? "item" : "items"}`}
            </p>
          </div>
        </div>
      </div>

      {items.length === 0 ? (
        <p className="items-empty">Nothing is waiting. All requests, stock and alerts are handled.</p>
      ) : (
        <div className="items-table-wrapper">
          <table className="items-table">
            <thead>
              <tr>
                <th>Item</th>
                <th>Role</th>
                <th>Horse</th>
                <th>When</th>
                <th>Status</th>
              </tr>
            </thead>

            <tbody>
              {items.map((item, index) => {
                const link = openItemLink(item.kind);
                const open = () => link && navigate(link);

                return (
                  <tr
                    key={`${item.kind}-${index}`}
                    className={link ? "clickable" : undefined}
                    tabIndex={link ? 0 : undefined}
                    role={link ? "link" : undefined}
                    onClick={open}
                    onKeyDown={(e) => {
                      if (link && (e.key === "Enter" || e.key === " ")) {
                        e.preventDefault();
                        open();
                      }
                    }}
                  >
                    <td className="item-name">{item.item}</td>
                    <td>{item.role}</td>
                    <td>{item.horseName || "—"}</td>
                    <td>{timeAgo(item.createdAt) || "—"}</td>
                    <td>
                      <span className={`item-status ${openItemTone(item.kind)}`}>
                        {item.status}
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

export default function ManagerDashboard() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [search, setSearch] = useState("");

  const load = useCallback(
    (signal) => {
      setError("");
      getManagerDashboard({ signal })
        .then(setData)
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        });
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load, reloadKey]);

  // Ô tìm kiếm mở danh sách ngựa với từ khoá đã gõ (HorseListPage đọc ?q=).
  const runSearch = (e) => {
    if (e.key !== "Enter") return;
    const q = search.trim();
    navigate(q ? `/manager/horses?q=${encodeURIComponent(q)}` : "/manager/horses");
  };

  const summary = data?.summary;
  const now = new Date();

  return (
    <div className="manager-page">
      <ManagerSidebar />

      <main className="manager-main">
        <header className="manager-header">
          <div>
            <div className="manager-page-title">
              <span className="manager-diamond" aria-hidden="true" />
              <h1>Club overview</h1>
            </div>

            <p>{now.getFullYear()} season · Week {isoWeek(now)}</p>
          </div>

          <div className="manager-header-actions">
            <input
              type="search"
              placeholder="Search horses…"
              aria-label="Search horses"
              className="manager-search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={runSearch}
            />

            <NotificationBell className="manager-notification" />

            <ProfileCircle className="manager-profile" />
          </div>
        </header>

        <section className="manager-content">
          {error && (
            <div className="manager-error" role="alert">
              <span>Could not load the club overview: {error}</span>
              <button type="button" onClick={() => setReloadKey((k) => k + 1)}>Retry</button>
            </div>
          )}

          {!data && !error && <p className="manager-loading">Loading the club overview…</p>}

          {data && (
            <>
              <div className="manager-stats">
                <StatCard value={summary.horses} label="Horses at the club" type="pink" />
                <StatCard value={summary.activePlans} label="Active training plans" type="gold" />
                <StatCard value={summary.lockedHorses} label="Under training lock" type="red" />
                <StatCard
                  value={formatCompactMoney(summary.quarterCost)}
                  label={`Operating cost · ${summary.quarterLabel}`}
                  type="green"
                />
                <StatCard
                  value={formatCompactMoney(summary.seasonPrize)}
                  label={`Prize money · ${summary.season} season`}
                  type="blue"
                />
              </div>

              <div className="manager-middle">
                <WorkloadChart points={data.workload || []} />
                <HealthCard health={data.health || []} />
              </div>

              <OpenItems items={data.openItems || []} total={data.openItemCount || 0} />
            </>
          )}
        </section>
      </main>
    </div>
  );
}
