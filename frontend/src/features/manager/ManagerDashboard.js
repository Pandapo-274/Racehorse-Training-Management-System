import "./manager.css";

const workloadData = [
  { week: "W27", value: 62 },
  { week: "W28", value: 75 },
  { week: "W29", value: 68 },
  { week: "W30", value: 88 },
  { week: "W31", value: 81 },
  { week: "W32", value: 95 },
  { week: "W33", value: 101 },
];

const openItems = [
  {
    item: "New record awaiting owner assignment",
    role: "Academy Manager",
    horse: "Symboli Rudolf",
    when: "09:12 today",
    status: "Pending",
    statusType: "pending",
  },
  {
    item: "Emergency training lock requested",
    role: "Veterinarian",
    horse: "Air Groove",
    when: "08:40 today",
    status: "Approved",
    statusType: "approved",
  },
  {
    item: "Feed and medicine restock request",
    role: "Stable",
    horse: "—",
    when: "Yesterday",
    status: "Awaiting approval",
    statusType: "pending",
  },
  {
    item: "Phase 3 plan awaiting approval",
    role: "Head Trainer",
    horse: "Gold Ship",
    when: "Yesterday",
    status: "Awaiting approval",
    statusType: "pending",
  },
  {
    item: "View access granted to new owner",
    role: "Academy Manager",
    horse: "Kitasan Black",
    when: "11 Sep 2026",
    status: "Done",
    statusType: "done",
  },
];

function ManagerSidebar() {
  return (
    <aside className="manager-sidebar">
      <div className="manager-brand">
        <img src="/logo.svg" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="manager-menu-title">◉ MENU</div>

      <nav className="manager-nav">
        <button className="manager-nav-item active">
          <span>◉</span>
          Overview
        </button>

        <button className="manager-nav-item">
          <span>◉</span>
          Horses
        </button>

        <button className="manager-nav-item">
          <span>◉</span>
          Staff
        </button>

        <button className="manager-nav-item">
          <span>◉</span>
          Supplies &amp; feed
        </button>

        <button className="manager-nav-item">
          <span>◉</span>
          Permissions
        </button>

        <button className="manager-nav-item">
          <span>◉</span>
          Audit log
        </button>
      </nav>

      <div className="manager-sidebar-bottom">
      <button type="button" className="manager-signout">Sign out</button>
        <div className="manager-user">
          <div className="manager-avatar">AT</div>

          <div>
            <strong>Aoi Tachibana</strong>
            <span>Academy Manager</span>
          </div>
        </div>
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

function WorkloadChart() {
  const max = Math.max(...workloadData.map((item) => item.value));

  return (
    <div className="manager-card workload-card">
      <div className="manager-card-title">
        <span className="diamond">◆</span>
        <div>
          <h2>Herd workload</h2>
          <p>Total distance (km) per week</p>
        </div>
      </div>

      <div className="workload-chart">
        {workloadData.map((item, index) => {
          const height = (item.value / max) * 100;

          return (
            <div className="workload-column" key={item.week}>
              <div className="workload-bar-wrapper">
                <div
                  className={`workload-bar ${
                    index === workloadData.length - 1 ? "highlight" : ""
                  }`}
                  style={{ height: `${height}%` }}
                  title={`${item.value} km`}
                />
              </div>

              <span>{item.week}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function HealthCard() {
  return (
    <div className="manager-card health-card">
      <div className="manager-card-title">
        <span className="diamond">◆</span>
        <div>
          <h2>Herd health status</h2>
        </div>
      </div>

      <div className="health-content">
        <div className="health-donut">
          <div className="health-donut-inner">
            <strong>71%</strong>
            <span>race-fit</span>
          </div>
        </div>

        <div className="health-legend">
          <div>
            <span className="legend-dot race-fit" />
            <strong>Race-fit</strong>
            <b>34</b>
          </div>

          <div>
            <span className="legend-dot monitor" />
            <strong>Monitor</strong>
            <b>9</b>
          </div>

          <div>
            <span className="legend-dot injured" />
            <strong>Injured</strong>
            <b>4</b>
          </div>

          <div>
            <span className="legend-dot quarantine" />
            <strong>Quarantine</strong>
            <b>1</b>
          </div>
        </div>
      </div>
    </div>
  );
}

function OpenItems() {
  return (
    <div className="manager-card open-items-card">
      <div className="open-items-header">
        <div className="manager-card-title">
          <span className="diamond">◆</span>
          <div>
            <h2>Open items</h2>
          </div>
        </div>

        <button className="register-horse-btn">
          Register horse
        </button>
      </div>

      <div className="items-table-wrapper">
        <table className="items-table">
          <thead>
            <tr>
              <th>Item</th>
              <th>Role</th>
              <th>Horses</th>
              <th>When</th>
              <th>Status</th>
            </tr>
          </thead>

          <tbody>
            {openItems.map((item, index) => (
              <tr key={index}>
                <td className="item-name">{item.item}</td>
                <td>{item.role}</td>
                <td>{item.horse}</td>
                <td>{item.when}</td>
                <td>
                  <span
                    className={`item-status ${item.statusType}`}
                  >
                    {item.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function ManagerDashboard() {
  return (
    <div className="manager-page">
      <ManagerSidebar />

      <main className="manager-main">
        <header className="manager-header">
          <div>
            <div className="manager-page-title">
              <span>◆</span>
              <h1>Academy overview</h1>
            </div>

            <p>2026 season · Week 38</p>
          </div>

          <div className="manager-header-actions">
            <input
              type="text"
              placeholder="Search horses, records..."
              className="manager-search"
            />

            <button className="notification-btn">●</button>

            <div className="header-profile" />
          </div>
        </header>

        <section className="manager-content">
          <div className="manager-stats">
            <StatCard
              value="48"
              label="Horses"
              type="pink"
            />

            <StatCard
              value="12"
              label="Active plans"
              type="gold"
            />

            <StatCard
              value="4"
              label="Under training lock"
              type="red"
            />

            <StatCard
              value="¥184M"
              label="Quarterly operating cost"
              type="green"
            />
          </div>

          <div className="manager-middle">
            <WorkloadChart />
            <HealthCard />
          </div>

          <OpenItems />
        </section>
      </main>
    </div>
  );
}