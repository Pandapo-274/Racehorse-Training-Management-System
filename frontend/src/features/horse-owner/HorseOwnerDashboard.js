import React from "react";
import "./horse-owner.css";

const trainingStats = [
  { name: "Speed", value: 92, icon: "⚡", type: "speed" },
  { name: "Stamina", value: 74, icon: "◉", type: "stamina" },
  { name: "Power", value: 86, icon: "▰", type: "power" },
  { name: "Guts", value: 68, icon: "♥", type: "guts" },
  { name: "Wit", value: 80, icon: "●", type: "wit" },
];

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

function Sidebar() {
  return (
    <aside className="owner-sidebar">
      <div className="owner-brand">
        <div className="owner-brand-mark">
          ♘
        </div>

        <div className="owner-brand-text">
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="owner-menu-ribbon">
        <span>◉</span>
        MENU
      </div>

      <nav className="owner-navigation">
        <a href="#" className="owner-nav-item active">
          <span className="owner-nav-icon">◉</span>
          <span>My horses</span>
        </a>

        <a href="#" className="owner-nav-item">
          <span className="owner-nav-icon">◉</span>
          <span>Results &amp; races</span>
        </a>

        <a href="#" className="owner-nav-item">
          <span className="owner-nav-icon">◉</span>
          <span>Costs &amp; prizes</span>
        </a>
      </nav>

      <div className="owner-sidebar-bottom">
        <button className="owner-signout">
          Sign out
        </button>

        <div className="owner-account">
          <div className="owner-account-avatar"></div>

          <div>
            <strong>Kenji Arai</strong>
            <span>Horse Owner</span>
          </div>
        </div>
      </div>
    </aside>
  );
}

function Header() {
  return (
    <header className="owner-header">
      <div className="owner-header-title">
        <h1>
          <span className="yellow-diamond">◆</span>
          My horses
        </h1>

        <p>3 horses stabled at Tenma Academy</p>
      </div>

      <div className="owner-header-actions">
        <div className="owner-search">
          <input
            type="text"
            placeholder="Search horses, records..."
          />
        </div>

        <button className="owner-notification">
          <span></span>
        </button>

        <div className="owner-header-avatar"></div>
      </div>
    </header>
  );
}

function HorseOverview() {
  return (
    <section className="horse-overview">
      <div className="horse-gradient"></div>

      <div className="horse-speed-lines"></div>

      <div className="horse-photo">
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
          <span className="horse-stars">★★★★★</span>

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
                className="training-meter-fill"
                style={{ width: `${stat.value}%` }}
              ></div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function FitnessTrend() {
  return (
    <section className="owner-card fitness-card">
      <div className="card-heading">
        <div>
          <h3>
            <span>◆</span>
            Fitness trend
          </h3>

          <p>Last eight weeks</p>
        </div>
      </div>

      <div className="fitness-chart">
        {fitnessData.map((item, index) => (
          <div className="fitness-column" key={item.week}>
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

function TrainerRemarks() {
  return (
    <section className="owner-card remarks-card">
      <div className="card-heading">
        <h3>
          <span>◆</span>
          Trainer's remarks
        </h3>
      </div>

      <div className="remark-date">
        13 Sep 2026
      </div>

      <p className="remark-text">
        Held rhythm through laps 1–3, lost breathing cadence
        when the pace lifted. Session stopped early on a system
        alert. Load cut 15% next week.
      </p>

      <div className="trial-box">
        <span>▶</span>
        1,600 m trial · 12 Sep
      </div>
    </section>
  );
}

function CostsAndPrizeMoney() {
  return (
    <section className="owner-card costs-card">
      <div className="card-heading">
        <div>
          <h3>
            <span>◆</span>
            Costs and prize money
          </h3>

          <p>Q3 2026</p>
        </div>
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
                    key={index}
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

function HorseOwnerDashboard() {
  return (
    <div className="owner-page">
      <Sidebar />

      <main className="owner-main">
        <Header />

        <div className="owner-content">
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