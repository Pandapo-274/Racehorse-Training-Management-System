import { useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import "./groom.css";
import { logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";

// Giao diện Groom dùng cùng khung với Head Trainer, Club Manager và Vet:
// sidebar 265px + icon móng ngựa, header 84px, thẻ thống kê, card bo 18px viền
// trên 6px, chip trạng thái. Class đều mang tiền tố "groom-".

// TODO(fetch): tasks -> API công việc trong ngày của groom (TasksCard)
const tasks = [
  { time: "05:00", title: "Morning feed · Barn B", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "Done" },
  { time: "05:30", title: "Prepare horse and tack", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "In progress" },
  { time: "06:30", title: "Base session 3,200 m", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "Queued" },
  { time: "08:00", title: "Wash down, ice boots", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "Queued" },
  { time: "09:30", title: "Muck out stalls 12–18", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "Queued" },
  { time: "11:00", title: "Midday feed per ration", detail: "Symboli Rudolf · TM-0481 · Stall 14", status: "Queued" },
];

// TODO(fetch): tackItems -> API tình trạng dụng cụ/yên cương (TackCard)
const tackItems = [
  { name: "Exercise saddle", status: "Ready" },
  { name: "Leather bridle", status: "Ready" },
  { name: "Heart-rate girth", status: "Needs charging" },
  { name: "Front boots", status: "Ready" },
];

// TODO(fetch): notice -> API thông báo lịch mới từ head trainer
const notice = "The head trainer has sent you a new schedule for Symboli Rudolf.";

const statusSlug = (status) => status.toLowerCase().replaceAll(" ", "-");

function HorseshoeIcon() {
  return (
    <svg className="groom-nav-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
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

function GroomSidebar() {
  const navigate = useNavigate();

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="groom-sidebar">
      <div className="groom-brand">
        <img src="/logo.png" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="groom-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="groom-nav">
        <button type="button" className="groom-nav-item active">
          <HorseshoeIcon />
          Today
        </button>

        <button
          type="button"
          className="groom-nav-item"
          onClick={() => navigate("/groom/horses")}
        >
          <HorseshoeIcon />
          Stable map
        </button>

        <button type="button" className="groom-nav-item">
          <HorseshoeIcon />
          Feeding
        </button>

        <button type="button" className="groom-nav-item">
          <HorseshoeIcon />
          Incident reports
        </button>

        <button type="button" className="groom-nav-item">
          <HorseshoeIcon />
          Supplies
        </button>
      </nav>

      <div className="groom-sidebar-bottom">
        <button type="button" className="groom-signout" onClick={handleSignOut}>
          Sign out
        </button>

        {/* Tên và vai trò lấy theo người đang đăng nhập; bấm vào mở hồ sơ. */}
        <ProfileCard className="groom-user" avatarClassName="groom-avatar" role="Groom" />
      </div>
    </aside>
  );
}

function StatCard({ value, label, type, active, onClick }) {
  return (
    <button
      type="button"
      className={`groom-stat-card ${type}${active ? " selected" : ""}`}
      onClick={onClick}
      aria-pressed={active}
    >
      <span className="groom-stat-value">{value}</span>
      <span className="groom-stat-label">{label}</span>
    </button>
  );
}

function TasksCard({ items, total, filter }) {
  return (
    <div className="groom-card tasks-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>Tasks by hour</h2>
          <p>
            {filter === "all" ? `${total} tasks today` : `${items.length} of ${total} tasks · ${filter}`}
          </p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="groom-inline-state">No tasks match your search or filter.</div>
      ) : (
        <div className="tasks-list">
          {items.map((task) => (
            <div
              className={`task-row${task.status === "In progress" ? " current-task" : ""}`}
              key={task.time}
            >
              <div className="task-time">{task.time}</div>

              <div className="task-line" aria-hidden="true">
                <span />
              </div>

              <div className="task-information">
                <strong>{task.title}</strong>
                <p>{task.detail}</p>
              </div>

              <span className={`task-status ${statusSlug(task.status)}`}>{task.status}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TackCard({ items, cardRef }) {
  return (
    <div ref={cardRef} className="groom-card tack-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>Tack to prepare</h2>
          <p>
            {items.filter((item) => item.status !== "Ready").length} need attention
          </p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="groom-inline-state">No tack matches your search.</div>
      ) : (
        <div className="tack-list">
          {items.map((item) => (
            <div className="tack-item" key={item.name}>
              <strong>{item.name}</strong>
              <span className={item.status === "Ready" ? "tack-chip ready" : "tack-chip warning"}>
                {item.status}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function GroomDashboard() {
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const noticeRef = useRef(null);
  const tackRef = useRef(null);

  const normalizedQuery = query.trim().toLowerCase();

  const counts = useMemo(
    () => ({
      Done: tasks.filter((t) => t.status === "Done").length,
      "In progress": tasks.filter((t) => t.status === "In progress").length,
      Queued: tasks.filter((t) => t.status === "Queued").length,
      attention: tackItems.filter((t) => t.status !== "Ready").length,
    }),
    []
  );

  const visibleTasks = tasks.filter(
    (task) =>
      (statusFilter === "all" || task.status === statusFilter) &&
      (!normalizedQuery ||
        task.title.toLowerCase().includes(normalizedQuery) ||
        task.detail.toLowerCase().includes(normalizedQuery) ||
        task.time.includes(normalizedQuery))
  );

  const visibleTack = tackItems.filter(
    (item) => !normalizedQuery || item.name.toLowerCase().includes(normalizedQuery)
  );

  const toggleFilter = (status) => setStatusFilter(statusFilter === status ? "all" : status);

  const scrollTo = (ref) => ref.current?.scrollIntoView({ behavior: "smooth", block: "center" });

  const now = new Date();
  const weekday = now.toLocaleDateString("en-GB", { weekday: "long" });
  const dateText = now.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });

  return (
    <div className="groom-page">
      <GroomSidebar />

      <main className="groom-main">
        <header className="groom-header">
          <div>
            <div className="groom-page-title">
              <span className="groom-diamond" aria-hidden="true" />
              <h1>Today's schedule</h1>
            </div>
            <p>
              {weekday} · {dateText} · Barn B
            </p>
          </div>

          <div className="groom-header-actions">
            <input
              type="search"
              className="groom-search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search tasks, tack..."
              aria-label="Search tasks and tack"
            />

            <button
              type="button"
              className="groom-notification"
              onClick={() => scrollTo(noticeRef)}
              title="Jump to the latest notice"
              aria-label="Jump to the latest notice"
            >
              <span className="groom-notification-dot" />
            </button>

            <ProfileCircle className="groom-profile" />
          </div>
        </header>

        <section className="groom-content">
          <div className="groom-stats">
            <StatCard
              value={counts.Done}
              label="Done"
              type="green"
              active={statusFilter === "Done"}
              onClick={() => toggleFilter("Done")}
            />
            <StatCard
              value={counts["In progress"]}
              label="In progress"
              type="gold"
              active={statusFilter === "In progress"}
              onClick={() => toggleFilter("In progress")}
            />
            <StatCard
              value={counts.Queued}
              label="Queued"
              type="pink"
              active={statusFilter === "Queued"}
              onClick={() => toggleFilter("Queued")}
            />
            <StatCard
              value={counts.attention}
              label="Tack needs attention"
              type="red"
              active={false}
              onClick={() => scrollTo(tackRef)}
            />
          </div>

          <div ref={noticeRef} className="groom-alert" role="status">
            <strong>New schedule:</strong>
            <span>{notice}</span>
          </div>

          <div className="groom-grid">
            <TasksCard items={visibleTasks} total={tasks.length} filter={statusFilter} />

            <div className="groom-right-column">
              <TackCard items={visibleTack} cardRef={tackRef} />

              {/* TODO(fetch): chưa có API/route cho bắt đầu phiên tập và báo sự cố chuồng */}
              <button type="button" className="start-session">
                Start session
              </button>

              <button type="button" className="report-incident">
                Report a stable incident
              </button>
            </div>
          </div>
        </section>
      </main>
    </div>
  );
}