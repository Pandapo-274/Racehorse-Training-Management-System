import React, { useState } from "react";
import "./horse-owner.css";
import { useNavigate } from "react-router-dom";
import { getUser, logout } from "../auth/authService";

// TODO(fetch): trainingStats -> API chỉ số huấn luyện của ngựa (HorseOverview)
const trainingStats = [
  { name: "Speed", value: 92, icon: "⚡", type: "speed" },
  { name: "Stamina", value: 74, icon: "◉", type: "stamina" },
  { name: "Power", value: 86, icon: "▰", type: "power" },
  { name: "Guts", value: 68, icon: "♥", type: "guts" },
  { name: "Wit", value: 80, icon: "●", type: "wit" },
];

// TODO(fetch): fitnessData -> API xu hướng thể lực theo tuần (FitnessTrend)
const fitnessData = [
  { week: "W26", value: 64 },
  { week: "W27", value: 68 },
  { week: "W28", value: 71 },
  { week: "W29", value: 69 },
  { week: "W30", value: 74 },
  { week: "W31", value: 78 },
  { week: "W32", value: 76 },
  { week: "W33", value: 83 },
];

// TODO(fetch): costRows -> API chi phí & tiền thưởng (CostsAndPrizeMoney)
const costRows = [
  [
    "Keep and stabling",
    "¥4,200,000",
    "¥3,800,000",
    "¥4,100,000",
    "¥12,100,000",
  ],
  [
    "Veterinary",
    "¥640,000",
    "¥210,000",
    "¥980,000",
    "¥1,830,000",
  ],
  [
    "Training",
    "¥2,800,000",
    "¥2,400,000",
    "¥2,800,000",
    "¥8,000,000",
  ],
  [
    "Race prize money",
    "+¥12,000,000",
    "—",
    "+¥4,500,000",
    "+¥16,500,000",
  ],
];

// TODO(fetch): ownerSummary -> API tổng quan ngựa của chủ ngựa (StatStrip)
const ownerSummary = [
  { value: "3", label: "Horses stabled", type: "pink" },
  { value: "83", label: "Current fitness", type: "green" },
  { value: "36 km", label: "Weekly distance", type: "gold" },
  { value: "22 Nov", label: "Next race", type: "red" },
];

function HorseshoeIcon() {
  return (
    <svg className="owner-nav-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
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

function toInitials(name) {
  if (!name) return "??";
  return name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
}

function scrollToId(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

/* =====================================================
   SIDEBAR
===================================================== */

function Sidebar({ user }) {
  const navigate = useNavigate();
  const fullName = user?.fullName || "Horse Owner";

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="owner-sidebar">
      <div className="owner-brand">
        <img src="/logo.svg" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="owner-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="owner-navigation">
        <button type="button" className="owner-nav-item active">
          <HorseshoeIcon />
          Overview
        </button>

        <button type="button" className="owner-nav-item" onClick={() => navigate("/horse-owner/horses")}>
          <HorseshoeIcon />
          My horses
        </button>

        <button type="button" className="owner-nav-item" onClick={() => scrollToId("results-races")}>
          <HorseshoeIcon />
          Results &amp; races
        </button>

        <button type="button" className="owner-nav-item" onClick={() => scrollToId("costs-prizes")}>
          <HorseshoeIcon />
          Costs &amp; prizes
        </button>
      </nav>

      <div className="owner-sidebar-bottom">
        <button type="button" className="owner-signout" onClick={handleSignOut}>
          Sign out
        </button>

        <div className="owner-account">
          <div className="owner-account-avatar">{toInitials(fullName)}</div>
          <div>
            <strong>{fullName}</strong>
            <span>Horse Owner</span>
          </div>
        </div>
      </div>
    </aside>
  );
}

/* =====================================================
   HEADER
===================================================== */

function Header({ user }) {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const fullName = user?.fullName || "Horse Owner";

  // Giống màn /horse-owner/horses: tìm kiếm ngựa nằm ở danh sách, nên Enter chuyển sang đó.
  const handleSearch = (event) => {
    if (event.key === "Enter") navigate("/horse-owner/horses");
  };

  return (
    <header className="owner-header">
      <div className="owner-header-title">
        <div className="owner-page-title">
          <span className="owner-diamond" aria-hidden="true" />
          <h1>My horses</h1>
        </div>

        <p>3 horses stabled at Tenma Academy</p>
      </div>

      <div className="owner-header-actions">
        <input
          className="owner-search"
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={handleSearch}
          placeholder="Search horses, records..."
          aria-label="Search horses and records"
        />

        <button
          type="button"
          className="owner-notification"
          onClick={() => scrollToId("trainer-remarks")}
          title="Jump to trainer's remarks"
          aria-label="Jump to trainer's remarks"
        >
          <span className="owner-notification-dot" />
        </button>

        <button
          type="button"
          className="owner-header-avatar"
          onClick={() => navigate("/horse-owner/horses")}
          title="Open my horses"
          aria-label="Open my horses"
        >
          {toInitials(fullName)}
        </button>
      </div>
    </header>
  );
}

/* =====================================================
   STAT STRIP
===================================================== */

function StatStrip() {
  return (
    <div className="owner-stats">
      {ownerSummary.map((item) => (
        <div className={`owner-stat-card ${item.type}`} key={item.label}>
          <span className="owner-stat-value">{item.value}</span>
          <span className="owner-stat-label">{item.label}</span>
        </div>
      ))}
    </div>
  );
}

/* =====================================================
   HORSE OVERVIEW
===================================================== */

function HorseOverview() {
  return (
    <section className="horse-overview" id="results-races">
      <div className="horse-gradient"></div>
      <div className="horse-speed-lines"></div>

      <div className="horse-photo">
        <img
          src="/symbolirudoff.jpg"
          alt="Symboli Rudolf"
        />

        <div className="photo-overlay"></div>

        <div className="photo-corner top-left"></div>
        <div className="photo-corner top-right"></div>
        <div className="photo-corner bottom-left"></div>
        <div className="photo-corner bottom-right"></div>
      </div>

      <div className="horse-title-area">
        <h2>Symboli Rudolf</h2>

        <div className="horse-title-line"></div>

        <p>Thoroughbred · 4 yrs · TM-0481</p>

        <div className="horse-rating">
          <span className="horse-stars" aria-label="5 stars">
            ★★★★★
          </span>

          <span className="cleared-chip">
            Cleared to race
          </span>
        </div>
      </div>

      <div className="horse-metrics">
        <div className="metric">
          <span>Fitness</span>
          <strong>83</strong>
        </div>

        <div className="metric">
          <span>Weight</span>
          <strong>486 kg</strong>
        </div>

        <div className="metric">
          <span>Weekly distance</span>
          <strong>36 km</strong>
        </div>

        <div className="metric">
          <span>Next race</span>
          <strong>22 Nov</strong>
        </div>
      </div>

      <div className="training-stats">
        <h3>Training stats</h3>

        {trainingStats.map((stat) => (
          <div className="training-row" key={stat.name}>
            <div className={`training-icon ${stat.type}`}>
              {stat.icon}
            </div>

            <span className="training-name">
              {stat.name}
            </span>

            <div className="training-meter">
              <div
                className={`training-meter-fill ${stat.type}`}
                style={{ width: `${stat.value}%` }}
              ></div>
            </div>

            <span className="training-value">
              {stat.value}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

/* =====================================================
   FITNESS TREND
===================================================== */

function FitnessTrend() {
  return (
    <section className="owner-card fitness-card">
      <div className="card-heading">
        <div>
          <h3>
            <span className="heading-diamond">◆</span>
            Fitness trend
          </h3>

          <p>Last eight weeks</p>
        </div>

        <div className="fitness-current">
          <strong>83</strong>
          <span>current</span>
        </div>
      </div>

      <div className="fitness-chart">
        {fitnessData.map((item, index) => (
          <div
            className="fitness-column"
            key={item.week}
          >
            <div className="fitness-value">
              {item.value}
            </div>

            <div className="fitness-bar-area">
              <div
                className={`fitness-bar ${
                  index === fitnessData.length - 1
                    ? "current"
                    : ""
                }`}
                style={{
                  height: `${item.value}%`,
                }}
              ></div>
            </div>

            <span>{item.week}</span>
          </div>
        ))}
      </div>
    </section>
  );
}

/* =====================================================
   TRAINER REMARKS
===================================================== */

function TrainerRemarks() {
  return (
    <section className="owner-card remarks-card" id="trainer-remarks">
      <div className="card-heading">
        <h3>
          <span className="heading-diamond">◆</span>
          Trainer's remarks
        </h3>
      </div>

      <div className="remark-date">
        13 Sep 2026
      </div>

      <p className="remark-text">
        Held rhythm through laps 1–3, lost breathing
        cadence when the pace lifted. Session stopped
        early on a system alert. Load cut 15% next week.
      </p>

      <div className="trial-box">
        <span className="trial-icon">▶</span>
        <span>1,600 m trial · 12 Sep</span>
        <span className="trial-arrow">→</span>
      </div>
    </section>
  );
}

/* =====================================================
   COSTS & PRIZE MONEY
===================================================== */

function CostsAndPrizeMoney() {
  return (
    <section
      className="owner-card costs-card"
      id="costs-prizes"
    >
      <div className="card-heading costs-heading">
        <div>
          <h3>
            <span className="heading-diamond">◆</span>
            Costs and prize money
          </h3>

          <p>Q3 2026</p>
        </div>

        <button
          type="button"
          className="cost-period"
        >
          Q3 2026
          <span>⌄</span>
        </button>
      </div>

      <div className="cost-table-wrapper">
        <table className="cost-table">
          <thead>
            <tr>
              <th>Line item</th>
              <th>Symboli Rudolf</th>
              <th>Almond Eye</th>
              <th>Gold Ship</th>
              <th>Total</th>
            </tr>
          </thead>

          <tbody>
            {costRows.map((row) => (
              <tr key={row[0]}>
                {row.map((cell, index) => (
                  <td
                    key={`${row[0]}-${index}`}
                    className={index === 0 ? "line-item" : ""}
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/* =====================================================
   MAIN DASHBOARD
===================================================== */

function HorseOwnerDashboard() {
  const user = getUser();

  return (
    <div className="owner-page">
      <Sidebar user={user} />

      <main className="owner-main">
        <Header user={user} />

        <div className="owner-content">
          <StatStrip />

          <HorseOverview />

          <div className="owner-middle">
            <FitnessTrend />
            <TrainerRemarks />
          </div>

          <CostsAndPrizeMoney />
        </div>
      </main>
    </div>
  );
}

export default HorseOwnerDashboard;