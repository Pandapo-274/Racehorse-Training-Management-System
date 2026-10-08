// src/features/horse/HorseShell.js
// Khung chung cho ba màn của UC7/UC8: thanh bên, thanh tiêu đề, vùng nội dung.
//
// Vì sao không dùng lại .trainer-sidebar trong trainer.css: file đó dài 660
// dòng và do người khác giữ, import vào đây là buộc hai màn khác nhau phải đi
// cùng một file CSS mãi mãi. Dự án vốn đã cho mỗi dashboard một file css riêng,
// nên làm theo cách đó - lớp ở đây đều mang tiền tố .hz-.
import { useNavigate, useLocation } from "react-router-dom";
import { getUser, logout } from "../auth/authService";
<<<<<<< HEAD
import { toInitials } from "./horseApi";
=======
import { getHorseBasePath, toInitials } from "./horseApi";
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
import "./horse.css";

const ROLE_LABEL = {
  CLUB_MANAGER: "Club Manager",
  HEAD_TRAINER: "Head Trainer",
  VETERINARIAN: "Veterinarian",
  GROOM: "Groom",
  HORSE_OWNER: "Horse Owner",
};

const HOME_BY_ROLE = {
  CLUB_MANAGER: "/manager",
  HEAD_TRAINER: "/trainer",
  VETERINARIAN: "/veterinarian",
  GROOM: "/groom",
  HORSE_OWNER: "/horse-owner",
};

export default function HorseShell({ title, subtitle, actions, children }) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = getUser();

  const fullName = user?.fullName || "Signed out";
  const role = ROLE_LABEL[user?.role] || "—";
  const home = HOME_BY_ROLE[user?.role] || "/login";
<<<<<<< HEAD
=======
  const horseBase = getHorseBasePath(user);
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

<<<<<<< HEAD
  const onHorses = location.pathname.startsWith("/horses");
=======
  const onHorses = location.pathname.startsWith(horseBase);
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72

  return (
    <div className="hz-page">
      <aside className="hz-sidebar">
        <div className="hz-brand">
          <img src="/logo.svg" alt="" />
          <div>
            <strong>TENMA</strong>
            <span>Racing Academy</span>
          </div>
        </div>

        <div className="hz-menu-title">◉ MENU</div>

        <nav className="hz-nav">
          <button className="hz-nav-item" onClick={() => navigate(home)}>
            <span aria-hidden="true">◉</span>
            Dashboard
          </button>

          <button
            className={`hz-nav-item${onHorses ? " active" : ""}`}
            aria-current={onHorses ? "page" : undefined}
<<<<<<< HEAD
            onClick={() => navigate("/horses")}
=======
            onClick={() => navigate(horseBase)}
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
          >
            <span aria-hidden="true">◉</span>
            Horses
          </button>
        </nav>

        <div className="hz-sidebar-bottom">
          <button className="hz-signout" onClick={handleSignOut}>
            Sign out
          </button>

          <div className="hz-user">
            <div className="hz-avatar">{toInitials(fullName)}</div>
            <div>
              <strong>{fullName}</strong>
              <span>{role}</span>
            </div>
          </div>
        </div>
      </aside>

      <main className="hz-main">
        <header className="hz-header">
          <div>
            <div className="hz-page-title">
              <span aria-hidden="true">◆</span>
              <h1>{title}</h1>
            </div>
            {subtitle && <p>{subtitle}</p>}
          </div>

          {actions && <div className="hz-header-actions">{actions}</div>}
        </header>

        <section className="hz-content">{children}</section>
      </main>
    </div>
  );
}

/* --------------------------------------------------------------- *
 * Những mảnh nhỏ dùng lại ở cả ba màn
 * --------------------------------------------------------------- */

/**
 * Chip trạng thái. `tone` chỉ đổi màu; chữ trong `label` mới là thứ mang nghĩa.
 * Không bao giờ bỏ label đi để chỉ còn chấm màu.
 */
export function StatusChip({ tone = "neutral", label, title }) {
  return (
    <span className={`hz-chip hz-chip--${tone}`} title={title || undefined}>
      <i className="hz-chip__dot" aria-hidden="true" />
      {label}
    </span>
  );
}

export function Card({ title, hint, actions, children, className = "" }) {
  return (
    <div className={`hz-card ${className}`}>
      {(title || actions) && (
        <div className="hz-card-title">
          <span aria-hidden="true">◆</span>
          <div>
            {title && <h2>{title}</h2>}
            {hint && <p>{hint}</p>}
          </div>
          {actions && <div className="hz-card-actions">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}

/** Trạng thái đang tải / lỗi / rỗng - ba màn dùng chung một hình dạng. */
export function StateBlock({ kind = "info", title, children, onRetry }) {
  return (
    <div className={`hz-state hz-state--${kind}`} role={kind === "error" ? "alert" : undefined}>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
      {onRetry && (
        <button type="button" className="hz-retry" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}
