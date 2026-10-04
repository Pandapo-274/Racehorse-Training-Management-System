import "./groom.css";

const tasks = [
  {
    time: "05:00",
    title: "Morning feed · Barn B",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "Done",
  },
  {
    time: "05:30",
    title: "Prepare horse and tack",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "In progress",
  },
  {
    time: "06:30",
    title: "Base session 3,200 m",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "Queued",
  },
  {
    time: "08:00",
    title: "Wash down, ice boots",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "Queued",
  },
  {
    time: "09:30",
    title: "Muck out stalls 12–18",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "Queued",
  },
  {
    time: "11:00",
    title: "Midday feed per ration",
    detail: "Symboli Rudolf · TM-0481 · Stall 14",
    status: "Queued",
  },
];

const tackItems = [
  {
    name: "Exercise saddle",
    status: "Ready",
  },
  {
    name: "Leather bridle",
    status: "Ready",
  },
  {
    name: "Heart-rate girth",
    status: "Needs charging",
  },
  {
    name: "Front boots",
    status: "Ready",
  },
];

function GroomSidebar() {
  return (
    <aside className="groom-sidebar">

      <div className="groom-brand">
        <img
          src="/logo.svg"
          alt="Tenma Racing Academy"
        />

        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="groom-menu-title">
        ◉ MENU
      </div>

      <nav className="groom-navigation">

        <button className="groom-nav-item active">
          <span>◉</span>
          Today
        </button>

        <button className="groom-nav-item">
          <span>◉</span>
          Stable map
        </button>

        <button className="groom-nav-item">
          <span>◉</span>
          Feeding
        </button>

        <button className="groom-nav-item">
          <span>◉</span>
          Incident reports
        </button>

        <button className="groom-nav-item">
          <span>◉</span>
          Supplies
        </button>

      </nav>

      <div className="groom-sidebar-bottom">

        <button className="groom-signout">
          Sign out
        </button>

        <div className="groom-user">

          <div className="groom-avatar">
            RS
          </div>

          <div>
            <strong>Riku Sasaki</strong>
            <span>Groom</span>
          </div>

        </div>

      </div>

    </aside>
  );
}

function TasksCard() {
  return (
    <div className="groom-card tasks-card">

      <div className="groom-card-title">

        <span>◆</span>

        <div>
          <h2>Tasks by hour</h2>
        </div>

      </div>

      <div className="tasks-list">

        {tasks.map((task) => (

          <div
            className={`task-row ${
              task.status === "In progress"
                ? "current-task"
                : ""
            }`}
            key={task.time}
          >

            <div className="task-time">
              {task.time}
            </div>

            <div className="task-line">
              <span />
            </div>

            <div className="task-information">

              <strong>
                {task.title}
              </strong>

              <p>
                {task.detail}
              </p>

            </div>

            <span
              className={`task-status ${
                task.status
                  .toLowerCase()
                  .replaceAll(" ", "-")
              }`}
            >
              {task.status}
            </span>

          </div>

        ))}

      </div>

    </div>
  );
}

function TackCard() {
  return (
    <div className="groom-card tack-card">

      <div className="groom-card-title">

        <span>◆</span>

        <div>
          <h2>Tack to prepare</h2>
        </div>

      </div>

      <div className="tack-list">

        {tackItems.map((item) => (

          <div
            className="tack-item"
            key={item.name}
          >

            <strong>
              {item.name}
            </strong>

            <span
              className={
                item.status === "Ready"
                  ? "tack-ready"
                  : "tack-warning"
              }
            >
              {item.status}
            </span>

          </div>

        ))}

      </div>

    </div>
  );
}

export default function GroomDashboard() {

  return (
    <div className="groom-page">

      <GroomSidebar />

      <main className="groom-main">

        <header className="groom-header">

          <div>

            <div className="groom-page-title">

              <span>◆</span>

              <h1>
                Today's schedule
              </h1>

            </div>

            <p>
              Sunday · 13 Sep 2026 · Barn B
            </p>

          </div>

          <div className="groom-header-actions">

            <input
              className="groom-search"
              type="text"
              placeholder="Search horses, records..."
            />

            <button className="groom-notification">
              ●
            </button>

            <div className="groom-profile" />

          </div>

        </header>

        <section className="groom-content">

          <div className="groom-info">

            <strong>
              The head trainer has sent you a new
              schedule for Symboli Rudolf.
            </strong>

          </div>

          <div className="groom-dashboard-grid">

            <TasksCard />

            <div className="groom-right-column">

              <TackCard />

              <button className="start-session">
                Start session
              </button>

              <button className="report-incident">
                Report a stable incident
              </button>

            </div>

          </div>

        </section>

      </main>

    </div>
  );
}