// src/features/vet/VetShell.js
// Khung chung cho mọi màn của bác sĩ thú y: sidebar, header, vùng nội dung.
// Cùng khung với Trainer và Manager (xem vet.css); các màn chỉ việc truyền
// `active`, `title`, `subtitle` rồi đặt nội dung vào children.

import { useNavigate } from "react-router-dom";
import { logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import "./vet.css";

export function HorseshoeIcon() {
  return (
    <svg className="vet-nav-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
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

function VetSidebar({ active }) {
  const navigate = useNavigate();

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="vet-sidebar">
      <div className="vet-brand">
        <img src="/logo.png" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="vet-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="vet-nav">
        <button
          type="button"
          className={`vet-nav-item${active === "health" ? " active" : ""}`}
          aria-current={active === "health" ? "page" : undefined}
          onClick={() => navigate("/veterinarian")}
        >
          <HorseshoeIcon />
          Herd health
        </button>

        <button
          type="button"
          className={`vet-nav-item${active === "horses" ? " active" : ""}`}
          aria-current={active === "horses" ? "page" : undefined}
          onClick={() => navigate("/veterinarian/horses")}
        >
          <HorseshoeIcon />
          Horses
        </button>

        <button
          type="button"
          className={`vet-nav-item${active === "examination" ? " active" : ""}`}
          aria-current={active === "examination" ? "page" : undefined}
          onClick={() => navigate("/veterinarian/examination")}
        >
          <HorseshoeIcon />
          Examination
        </button>

        <button
          type="button"
          className={`vet-nav-item${active === "recovery" ? " active" : ""}`}
          aria-current={active === "recovery" ? "page" : undefined}
          onClick={() => navigate("/veterinarian/recovery")}
        >
          <HorseshoeIcon />
          Recovery
        </button>

        <button
          type="button"
          className={`vet-nav-item${active === "preventive" ? " active" : ""}`}
          aria-current={active === "preventive" ? "page" : undefined}
        >
          <HorseshoeIcon />
          Preventive care
        </button>
      </nav>

      <div className="vet-sidebar-bottom">
        <button type="button" className="vet-signout" onClick={handleSignOut}>
          Sign out
        </button>

        <ProfileCard className="vet-user" avatarClassName="vet-avatar" role="Veterinarian" />
      </div>
    </aside>
  );
}

export default function VetShell({
  active,
  title,
  subtitle,
  query,
  onQueryChange,
  onBellClick,
  children,
}) {
  const controlled = typeof onQueryChange === "function";

  return (
    <div className="vet-page">
      <VetSidebar active={active} />

      <main className="vet-main">
        <header className="vet-header">
          <div>
            <div className="vet-page-title">
              <span className="vet-diamond" aria-hidden="true" />
              <h1>{title}</h1>
            </div>
            {subtitle && <p>{subtitle}</p>}
          </div>

          <div className="vet-header-actions">
            <input
              type="search"
              className="vet-search"
              placeholder="Search horses, records..."
              aria-label="Search horses and medical records"
              {...(controlled
                ? { value: query || "", onChange: (e) => onQueryChange(e.target.value) }
                : {})}
            />

            <button
              type="button"
              className="vet-notification"
              onClick={onBellClick}
              aria-label="Notifications"
            >
              <span className="vet-notification-dot" />
            </button>

            <ProfileCircle className="vet-profile" />
          </div>
        </header>

        <section className="vet-content">{children}</section>
      </main>
    </div>
  );
}

/* ---------- mảnh dùng lại ---------- */

/** Thẻ trắng bo 18px, viền trên 6px theo `tone` (pink | green | gold | red). */
export function VetCard({ title, hint, tone = "pink", actions, className = "", children }) {
  return (
    <div className={`vet-card vx-card vx-card--${tone} ${className}`}>
      {title && (
        <div className="vet-card-title">
          <span className="vet-diamond" aria-hidden="true" />
          <div>
            <h2>{title}</h2>
            {hint && <p>{hint}</p>}
          </div>
          {actions}
        </div>
      )}
      {children}
    </div>
  );
}

/** Đang tải / lỗi / rỗng - cùng một hình dạng cho cả ba màn. */
export function VetState({ kind = "info", title, children, onRetry }) {
  return (
    <div className={`vx-state vx-state--${kind}`} role={kind === "error" ? "alert" : undefined}>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
      {onRetry && (
        <button type="button" className="vx-retry" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

/** Khối lỗi gồm câu chính và danh sách chi tiết theo từng trường. */
export function VetError({ error }) {
  if (!error) return null;
  return (
    <div className="vx-banner vx-banner--error" role="alert">
      <strong>{error.message}</strong>
      {error.details?.length > 0 && (
        <ul>
          {error.details.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      )}
      {error.note && <p>{error.note}</p>}
    </div>
  );
}