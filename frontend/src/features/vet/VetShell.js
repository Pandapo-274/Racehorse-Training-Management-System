// src/features/vet/VetShell.js
// Khung chung cho mọi màn của bác sĩ thú y: sidebar, header, vùng nội dung.
// Cùng khung với Trainer và Manager (xem vet.css); các màn chỉ việc truyền
// `active`, `title`, `subtitle` rồi đặt nội dung vào children.

import { useNavigate } from "react-router-dom";
import NavItems from "../navigation/NavItems";
import { logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import NotificationBell from "../notification/NotificationBell";
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
        <img src="/logo.png" alt="Tracen logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="vet-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="vet-nav">
        <NavItems role="VETERINARIAN" prefix="vet" icon={<HorseshoeIcon />} />
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

            <NotificationBell className="vet-notification" />

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