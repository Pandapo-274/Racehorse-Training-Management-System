import { useEffect, useState } from "react";
import "./horse-owner.css";
import { Link, useNavigate } from "react-router-dom";
import { getUser, logout, handleAuthError } from "../auth/authService";
import {
  formatDate, formatWeight, toAge, toEnumLabel, toInitials, toStatus,
} from "../horse/horseApi";
import { listRequests, pendingOnly } from "../horse/horseRequestApi";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import {
  COST_PERIODS, DEFAULT_COST_PERIOD, TRAINING_STATS, formatKm, formatMoney,
  formatShortDate, getOwnerDashboard, isPrizeRow, toCostLabel, toEvaluation, toStars,
} from "./horseOwnerApi";

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

function scrollToId(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

/* =====================================================
   SIDEBAR
===================================================== */

function Sidebar({ user }) {
  const navigate = useNavigate();

  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="owner-sidebar">
      <div className="owner-brand">
        <img src="/logo.png" alt="Tenma logo" />
        <div>
          <strong>TENMA</strong>
          <span>Racing Club</span>
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

        <ProfileCard className="owner-account" avatarClassName="owner-account-avatar"
                     role="Horse Owner" />
      </div>
    </aside>
  );
}

/* =====================================================
   HEADER
===================================================== */

function Header({ horseCount }) {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");

  // Giống màn /horse-owner/horses: tìm kiếm ngựa nằm ở danh sách, nên Enter chuyển sang đó.
  const handleSearch = (event) => {
    if (event.key === "Enter") navigate("/horse-owner/horses");
  };

  let subtitle = "Loading your horses…";
  if (horseCount != null) {
    subtitle = `${horseCount} ${horseCount === 1 ? "horse" : "horses"} stabled at Tenma Club`;
  }

  return (
    <header className="owner-header">
      <div className="owner-header-title">
        <div className="owner-page-title">
          <span className="owner-diamond" aria-hidden="true" />
          <h1>My horses</h1>
        </div>

        <p>{subtitle}</p>
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

        <ProfileCircle className="owner-header-avatar" />
      </div>
    </header>
  );
}

/* =====================================================
   STAT STRIP
===================================================== */

function StatStrip({ summary }) {
  const next = summary.nextRace;
  const items = [
    { value: String(summary.horseCount), label: "Horses stabled", type: "pink" },
    { value: summary.avgFitness ?? "—", label: "Current fitness", type: "green" },
    { value: formatKm(summary.weeklyDistanceKm), label: "Weekly distance", type: "gold" },
    {
      value: next ? formatShortDate(next.raceDate) : "—",
      label: next ? `Next race · ${next.horseName}` : "Next race",
      title: next ? `${next.raceName} · ${formatDate(next.raceDate)}` : "No upcoming race",
      type: "red",
    },
  ];

  return (
    <div className="owner-stats">
      {items.map((item) => (
        <div className={`owner-stat-card ${item.type}`} key={item.type} title={item.title}>
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

/** Chỉ hiện khi chủ có từ hai con trở lên. */
function HorseTabs({ horses, selectedId, onSelect }) {
  if (horses.length < 2) return null;
  return (
    <div className="owner-horse-tabs" role="tablist" aria-label="Choose a horse">
      {horses.map((h) => (
        <button
          key={h.horseId}
          type="button"
          role="tab"
          aria-selected={h.horseId === selectedId}
          className={`owner-horse-tab${h.horseId === selectedId ? " active" : ""}`}
          onClick={() => onSelect(h.horseId)}
        >
          {h.horseName}
        </button>
      ))}
    </div>
  );
}

function HorseOverview({ horse }) {
  // Ảnh không tải được (file đã bị xoá trên máy chủ) thì quay về chữ viết tắt.
  const [photoBroken, setPhotoBroken] = useState(false);
  useEffect(() => setPhotoBroken(false), [horse.photoUrl]);

  const status = horse.locked
    ? { label: "Training locked", tone: "critical", hint: "The vet has locked training" }
    : toStatus(horse.status);
  const stars = toStars(horse.stats);
  const age = toAge(horse.dateOfBirth);
  const subtitle = [horse.breed, age == null ? null : `${age} yrs`, horse.registrationCode]
    .filter(Boolean)
    .join(" · ");

  return (
    <section className="horse-overview" id="results-races">
      <div className="horse-gradient"></div>
      <div className="horse-speed-lines"></div>

      <Link
        className="horse-photo"
        to={`/horse-owner/horses/${horse.horseId}/edit`}
        title={horse.photoUrl ? "Change photo" : "Add a photo"}
        aria-label={`${horse.photoUrl ? "Change" : "Add a"} photo of ${horse.horseName}`}
      >
        {horse.photoUrl && !photoBroken ? (
          <img src={horse.photoUrl} alt="" onError={() => setPhotoBroken(true)} />
        ) : (
          <div className="horse-photo-initials" aria-hidden="true">
            {toInitials(horse.horseName)}
          </div>
        )}

        <div className="photo-overlay"></div>

        <div className="photo-corner top-left"></div>
        <div className="photo-corner top-right"></div>
        <div className="photo-corner bottom-left"></div>
        <div className="photo-corner bottom-right"></div>
      </Link>

      <div className="horse-title-area">
        <h2>{horse.horseName}</h2>

        <div className="horse-title-line"></div>

        <p>{subtitle}</p>

        <div className="horse-rating">
          {stars != null && (
            <span className="horse-stars" aria-label={`${stars} of 5 stars`}>
              {"★".repeat(stars)}
              <span className="horse-stars-empty">{"★".repeat(5 - stars)}</span>
            </span>
          )}

          <span className={`cleared-chip ${status.tone}`} title={status.hint}>
            {status.label}
          </span>
        </div>
      </div>

      <div className="horse-metrics">
        <div className="metric">
          <span>Fitness</span>
          <strong>{horse.fitness ?? "—"}</strong>
        </div>

        <div className="metric">
          <span>Weight</span>
          <strong>{formatWeight(horse.weight)}</strong>
        </div>

        <div className="metric">
          <span>Weekly distance</span>
          <strong>{formatKm(horse.weeklyDistanceKm)}</strong>
        </div>

        <div className="metric" title={horse.nextRace?.raceName}>
          <span>Next race</span>
          <strong>{horse.nextRace ? formatShortDate(horse.nextRace.raceDate) : "—"}</strong>
        </div>
      </div>

      <div className="training-stats">
        <h3>Training stats · last 4 weeks</h3>

        {TRAINING_STATS.map((stat) => {
          const value = horse.stats?.[stat.key];
          return (
            <div className="training-row" key={stat.key} title={stat.hint}>
              <img className="training-icon" src={stat.icon} alt="" aria-hidden="true" />

              <span className="training-name">{stat.label}</span>

              <div className="training-meter">
                <div
                  className={`training-meter-fill ${stat.key}`}
                  style={{ width: `${value ?? 0}%` }}
                ></div>
              </div>

              <span className="training-value">{value ?? "—"}</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

/* =====================================================
   FITNESS TREND
===================================================== */

function FitnessTrend({ horse }) {
  const points = horse.fitnessTrend || [];

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
          <strong>{horse.fitness ?? "—"}</strong>
          <span>current</span>
        </div>
      </div>

      {points.length === 0 ? (
        <p className="owner-card-empty">No training sessions in the last eight weeks.</p>
      ) : (
        <div className="fitness-chart">
          {points.map((item, index) => (
            <div className="fitness-column" key={item.weekStart}>
              <div className="fitness-value">{item.fitnessIndex}</div>

              <div className="fitness-bar-area">
                <div
                  className={`fitness-bar ${index === points.length - 1 ? "current" : ""}`}
                  style={{ height: `${item.fitnessIndex}%` }}
                ></div>
              </div>

              <span>{item.label}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/* =====================================================
   TRAINER REMARKS
===================================================== */

function TrainerRemarks({ horse }) {
  const latest = horse.remarks?.[0];

  return (
    <section className="owner-card remarks-card" id="trainer-remarks">
      <div className="card-heading">
        <h3>
          <span className="heading-diamond">◆</span>
          Trainer's remarks
        </h3>
      </div>

      {!latest ? (
        <p className="owner-card-empty">The head trainer has not evaluated a session yet.</p>
      ) : (
        <>
          <div className="remark-date">
            {formatDate(latest.sessionDate)} · {toEvaluation(latest.evaluation)}
          </div>

          <p className="remark-text">
            {latest.remarks || "Rated without a written remark."}
          </p>

          <div className="trial-box">
            <span className="trial-icon">▶</span>
            <span>
              {latest.actualDistance != null
                ? `${latest.actualDistance.toLocaleString("en-US")} m`
                : "Distance not recorded"}{" "}
              · {latest.phaseName} · {toEnumLabel(latest.status)}
            </span>
          </div>
        </>
      )}
    </section>
  );
}

/* =====================================================
   COSTS & PRIZE MONEY
===================================================== */

function CostsAndPrizeMoney({ costs, period, onPeriodChange }) {
  const range = costs.from
    ? `${formatDate(costs.from)} – ${formatDate(costs.to)}`
    : `Up to ${formatDate(costs.to)}`;

  return (
    <section className="owner-card costs-card" id="costs-prizes">
      <div className="card-heading costs-heading">
        <div>
          <h3>
            <span className="heading-diamond">◆</span>
            Costs and prize money
          </h3>

          <p>{range}</p>
        </div>

        <select
          className="cost-period"
          value={period}
          onChange={(event) => onPeriodChange(event.target.value)}
          aria-label="Cost period"
        >
          {Object.entries(COST_PERIODS).map(([key, label]) => (
            <option key={key} value={key}>{label}</option>
          ))}
        </select>
      </div>

      <div className="cost-table-wrapper">
        <table className="cost-table">
          <thead>
            <tr>
              <th>Line item</th>
              {costs.horses.map((h) => (
                <th key={h.horseId}>{h.horseName}</th>
              ))}
              <th>Total</th>
            </tr>
          </thead>

          <tbody>
            {costs.rows.map((row) => {
              const prize = isPrizeRow(row.category);
              return (
                <tr key={row.category}>
                  <td className="line-item">{toCostLabel(row.category)}</td>
                  {row.amounts.map((amount, index) => (
                    <td key={costs.horses[index].horseId}>{formatMoney(amount, { prize })}</td>
                  ))}
                  <td>{formatMoney(row.total, { prize })}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/* =====================================================
   MAIN DASHBOARD
===================================================== */

/**
 * Màn hình khi chủ ngựa chưa có con nào.
 *
 * Trước đây trang này luôn hiện dữ liệu mẫu - "3 Horses stabled", Symboli
 * Rudolf, biểu đồ thể lực - kể cả với tài khoản không sở hữu con ngựa nào.
 * Người dùng bấm sang "My horses" thì thấy "0 horses", và hai màn hình nói
 * ngược nhau.
 *
 * Chủ ngựa không tự tạo hồ sơ ngựa được: POST /api/horses chỉ nhận
 * HEAD_TRAINER và CLUB_MANAGER. Nên nút ở đây không gọi thẳng vào đó mà dẫn
 * sang biểu mẫu gửi yêu cầu - đường duy nhất thật sự đi được.
 */
function NoHorsesYet({ pendingCount }) {
  const navigate = useNavigate();

  return (
    <div className="owner-empty">
      <div className="owner-empty__mark" aria-hidden="true">◆</div>

      <h2>No horses in your name yet</h2>

      <p>
        Horses are entered into the register by the club. Send a request and they
        will add yours, assign a registration code and a stall.
      </p>

      {pendingCount > 0 && (
        <p className="owner-empty__pending">
          {pendingCount === 1
            ? "You have one request waiting for the club."
            : `You have ${pendingCount} requests waiting for the club.`}
        </p>
      )}

      <button type="button" className="owner-empty__btn"
              onClick={() => navigate("/horse-owner/request")}>
        {pendingCount > 0 ? "Request another horse" : "Request a horse registration"}
      </button>
    </div>
  );
}

export default function HorseOwnerDashboard() {
  const user = getUser();
  const navigate = useNavigate();

  // null = chưa biết. Chỉ phân nhánh sau khi máy chủ đã trả lời, nếu không
  // trang sẽ nháy qua màn trống một cái rồi mới hiện nội dung.
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(0);
  const [period, setPeriod] = useState(DEFAULT_COST_PERIOD);
  const [selectedId, setSelectedId] = useState(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");

    getOwnerDashboard(period, ctrl.signal)
      .then((res) => {
        setData(res);
        // Giữ con đang chọn khi đổi kỳ chi phí; con đó không còn thì về con đầu.
        setSelectedId((current) =>
          res.horses.some((h) => h.horseId === current) ? current : res.horses[0]?.horseId ?? null,
        );
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message || "Could not load your dashboard.");
      });

    return () => ctrl.abort();
  }, [navigate, period, reloadKey]);

  useEffect(() => {
    const ctrl = new AbortController();
    listRequests(ctrl.signal)
      .then((rows) => setPending(pendingOnly(rows).length))
      .catch(() => {}); // chỉ là con số phụ, hỏng thì bỏ qua
    return () => ctrl.abort();
  }, []);

  const horse = data?.horses.find((h) => h.horseId === selectedId);

  let content;
  if (error && !data) {
    content = (
      <div className="owner-empty" role="alert">
        <h2>Could not load your dashboard</h2>
        <p>{error}</p>
        <button type="button" className="owner-empty__btn" onClick={() => setReloadKey((k) => k + 1)}>
          Try again
        </button>
      </div>
    );
  } else if (!data) {
    content = (
      <div className="owner-empty" role="status">
        <p>Loading your horses…</p>
      </div>
    );
  } else if (data.horses.length === 0) {
    content = <NoHorsesYet pendingCount={pending} />;
  } else {
    content = (
      <>
        {error && <p className="owner-inline-error" role="alert">{error}</p>}

        <StatStrip summary={data.summary} />

        <HorseTabs horses={data.horses} selectedId={selectedId} onSelect={setSelectedId} />

        {horse && <HorseOverview horse={horse} />}

        {horse && (
          <div className="owner-middle">
            <FitnessTrend horse={horse} />
            <TrainerRemarks horse={horse} />
          </div>
        )}

        <CostsAndPrizeMoney costs={data.costs} period={period} onPeriodChange={setPeriod} />
      </>
    );
  }

  return (
    <div className="owner-page">
      <Sidebar user={user} />

      <main className="owner-main">
        <Header horseCount={data ? data.summary.horseCount : null} />

        <div className="owner-content">{content}</div>
      </main>
    </div>
  );
}
