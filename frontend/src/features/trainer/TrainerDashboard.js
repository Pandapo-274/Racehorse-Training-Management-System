import "./trainer.css";

const fitnessData = [
  { date: "6 Sep", value: 68 },
  { date: "7 Sep", value: 72 },
  { date: "8 Sep", value: 70 },
  { date: "9 Sep", value: 77 },
  { date: "10 Sep", value: 81 },
  { date: "11 Sep", value: 75 },
  { date: "12 Sep", value: 86 },
];

const alerts = [
  {
    horse: "Air Groove",
    message: "Slow heart-rate recovery",
    level: "High",
  },
  {
    horse: "Gold Ship",
    message: "Workload up 28% this week",
    level: "Medium",
  },
  {
    horse: "TM Opera O",
    message: "Missed one session",
    level: "Low",
  },
];

const horses = [
  {
    name: "Symboli Rudolf",
    phase: "No plan yet",
    distance: "—",
    fitness: 83,
    form: "S",
    status: "Ready to plan",
  },
  {
    name: "Gold Ship",
    phase: "Phase 2 · Build",
    distance: "42 km",
    fitness: 76,
    form: "A",
    status: "Active",
  },
  {
    name: "Kitasan Black",
    phase: "Phase 1 · Base",
    distance: "28 km",
    fitness: 81,
    form: "B",
    status: "Active",
  },
  {
    name: "Deep Impact",
    phase: "Phase 3 · Peak",
    distance: "51 km",
    fitness: 88,
    form: "S",
    status: "Active",
  },
  {
    name: "Air Groove",
    phase: "Paused",
    distance: "—",
    fitness: 61,
    form: "C",
    status: "Locked",
  },
  {
    name: "TM Opera O",
    phase: "Phase 2 · Build",
    distance: "35 km",
    fitness: 70,
    form: "B",
    status: "Monitor",
  },
];

function TrainerSidebar() {
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
        <button className="trainer-signout">
          Sign out
        </button>

        <div className="trainer-user">
          <div className="trainer-avatar">KM</div>

          <div>
            <strong>Kenta Morishita</strong>
            <span>Head Trainer</span>
          </div>
        </div>
      </div>
    </aside>
  );
}

function FitnessChart() {
  const max = Math.max(
    ...fitnessData.map((item) => item.value)
  );

  return (
    <div className="trainer-card fitness-card">
      <div className="trainer-card-title">
        <span>◆</span>

        <div>
          <h2>Average fitness index</h2>
          <p>Last seven sessions</p>
        </div>
      </div>

      <div className="fitness-chart">
        {fitnessData.map((item, index) => {
          const height = (item.value / max) * 100;

          return (
            <div className="fitness-column" key={item.date}>
              <div className="fitness-bar-wrapper">
                <div
                  className={`fitness-bar ${
                    index === fitnessData.length - 1
                      ? "highlight"
                      : ""
                  }`}
                  style={{
                    height: `${height}%`,
                  }}
                />
              </div>

              <span>{item.date}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AlertsCard() {
  return (
    <div className="trainer-card alerts-card">
      <div className="trainer-card-title">
        <span>◆</span>

        <div>
          <h2>Open alerts</h2>
        </div>
      </div>

      <div className="alerts-list">
        {alerts.map((alert) => (
          <div
            className={`trainer-alert ${alert.level.toLowerCase()}`}
            key={alert.horse}
          >
            <div>
              <strong>{alert.horse}</strong>
              <p>{alert.message}</p>
            </div>

            <span>{alert.level}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function HorsesTable() {
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
            {horses.map((horse) => (
              <tr key={horse.name}>
                <td className="horse-name">
                  {horse.name}
                </td>

                <td>{horse.phase}</td>

                <td>{horse.distance}</td>

                <td className="fitness-value">
                  {horse.fitness}
                </td>

                <td>
                  <span
                    className={`form-badge form-${horse.form.toLowerCase()}`}
                  >
                    {horse.form}
                  </span>
                </td>

                <td>
                  <span
                    className={`horse-status ${
                      horse.status
                        .toLowerCase()
                        .replaceAll(" ", "-")
                    }`}
                  >
                    {horse.status}
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

export default function TrainerDashboard() {
  return (
    <div className="trainer-page">
      <TrainerSidebar />

      <main className="trainer-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span>◆</span>
              <h1>Herd progress and fitness</h1>
            </div>

            <p>Live · 13 Sep 2026</p>
          </div>

          <div className="trainer-header-actions">
            <input
              className="trainer-search"
              type="text"
              placeholder="Search horses, records..."
            />

            <button className="trainer-notification">
              ●
            </button>

            <div className="trainer-profile" />
          </div>
        </header>

        <section className="trainer-content">
          <div className="trainer-top">
            <FitnessChart />
            <AlertsCard />
          </div>

          <HorsesTable />
        </section>
      </main>
    </div>
  );
}