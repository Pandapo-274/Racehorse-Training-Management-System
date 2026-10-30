import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import "./groom.css";
import { handleAuthError, logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import NotificationBell from "../notification/NotificationBell";
import {
  formatSessionDay, isFinished, listMySessions, sendVitals, sessionDay, sessionTime,
  startSession, stopSession, toSessionStatus, toSeverityLabel, todayIso,
  COMMON_SYMPTOMS, URGENCY_OPTIONS,
} from "./groomApi";

// Giao diện Groom dùng cùng khung với Head Trainer, Club Manager và Vet:
// sidebar 265px + icon móng ngựa, header 84px, thẻ thống kê, card bo 18px viền
// trên 6px, chip trạng thái. Class đều mang tiền tố "groom-".
//
// Phần việc của Groom trong Flow 2: xem buổi tập Head Trainer giao (UC10), bắt
// đầu buổi tập, gửi số đo nhịp tim/vận tốc khi ngựa chạy (UC11). Cho ăn, vệ sinh,
// dụng cụ và báo sự cố chuồng thuộc Flow 3/4, chưa có API nên không hiện ở đây.

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
        <img src="/logo.png" alt="Tracen logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="groom-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="groom-nav">
        <NavItems role="GROOM" prefix="groom" icon={<HorseshoeIcon />} />
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

/* =====================================================
   BUỔI TẬP HÔM NAY
===================================================== */

function SessionsCard({ items, total, filter, selectedId, onSelect }) {
  return (
    <div className="groom-card tasks-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>Training sessions today</h2>
          <p>
            {filter === "all"
              ? `${total} ${total === 1 ? "session" : "sessions"} assigned to you`
              : `${items.length} of ${total} · ${toSessionStatus(filter).label}`}
          </p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="groom-inline-state">
          {total === 0
            ? "The head trainer has not assigned you a session today."
            : "No session matches your search or filter."}
        </div>
      ) : (
        <div className="tasks-list">
          {items.map((s) => {
            const st = toSessionStatus(s.status);
            const classes = ["task-row"];
            if (s.status === "RUNNING") classes.push("current-task");
            if (s.sessionId === selectedId) classes.push("selected");
            return (
              <button
                type="button"
                className={classes.join(" ")}
                key={s.sessionId}
                onClick={() => onSelect(s.sessionId)}
                aria-pressed={s.sessionId === selectedId}
              >
                <div className="task-time">{sessionTime(s.sessionDate)}</div>

                <div className="task-line" aria-hidden="true">
                  <span />
                </div>

                <div className="task-information">
                  <strong>
                    {s.phaseName}
                    {s.plannedDistance != null ? ` ${s.plannedDistance.toLocaleString("en-US")} m` : ""}
                  </strong>
                  <p>
                    {s.horseName} · Phase {s.phaseNo}
                    {s.heartRateCeiling != null ? ` · HR ceiling ${s.heartRateCeiling} bpm` : ""}
                  </p>
                </div>

                <span className={`task-status ${st.slug}`}>{st.label}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/* =====================================================
   ĐIỀU KHIỂN BUỔI TẬP ĐANG CHỌN
===================================================== */

/**
 * SCHEDULED: nút Start. RUNNING: ô nhập số đo, mỗi lần gửi backend so với ngưỡng
 * nhịp tim của giai đoạn và sinh cảnh báo cho Head Trainer nếu vượt.
 * Groom không tự kết thúc buổi tập: Head Trainer chấm điểm ở Session review
 * (UC12) mới chuyển sang COMPLETED, hoặc dừng sớm từ Live Session Monitor.
 */
function SessionPanel({ session, onChanged, navigate }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [heartRate, setHeartRate] = useState("");
  const [velocity, setVelocity] = useState("");
  const [lastReading, setLastReading] = useState(null);
  const [stopOpen, setStopOpen] = useState(false);
  const [stop, setStop] = useState({ symptoms: "", description: "", urgency: "HIGH" });
  const [stopResult, setStopResult] = useState(null);

  // Đổi buổi tập thì xoá trạng thái của buổi trước.
  useEffect(() => {
    setError("");
    setHeartRate("");
    setVelocity("");
    setLastReading(null);
    setStopOpen(false);
    setStop({ symptoms: "", description: "", urgency: "HIGH" });
    setStopResult(null);
  }, [session?.sessionId]);

  if (!session) {
    return (
      <div className="groom-card session-card">
        <div className="groom-card-title">
          <span className="groom-diamond" aria-hidden="true" />
          <div>
            <h2>Session</h2>
            <p>Pick a session on the left</p>
          </div>
        </div>
      </div>
    );
  }

  const st = toSessionStatus(session.status);

  const run = async (action) => {
    setBusy(true);
    setError("");
    try {
      return await action();
    } catch (err) {
      if (handleAuthError(err, navigate)) return null;
      const fields = Object.values(err.fieldErrors || {});
      setError(fields[0] || err.message || "Something went wrong.");
      return null;
    } finally {
      setBusy(false);
    }
  };

  const start = async () => {
    const updated = await run(() => startSession(session.sessionId));
    if (updated) onChanged(updated);
  };

  const submitVitals = async (event) => {
    event.preventDefault();
    const hr = Number(heartRate);
    if (!Number.isInteger(hr) || hr < 20 || hr > 300) {
      setError("Heart rate must be a whole number between 20 and 300.");
      return;
    }
    const v = velocity === "" ? null : Number(velocity);
    if (v != null && (!Number.isFinite(v) || v < 0 || v > 99.99)) {
      setError("Speed must be between 0 and 99.99 m/s.");
      return;
    }
    const result = await run(() => sendVitals(session.sessionId, { heartRate: hr, velocity: v }));
    if (result) {
      setLastReading(result);
      setHeartRate("");
      setVelocity("");
      onChanged(null); // tải lại để thấy nhịp tim đỉnh và số cảnh báo mới
    }
  };

  /* Ngựa bất thường giữa buổi: dừng buổi và mở báo cáo sự cố cho bác sĩ thú y.
     Bắt buộc ghi triệu chứng: bác sĩ cần biết ngựa bị gì trước khi tới. */
  const submitStop = async (event) => {
    event.preventDefault();
    if (!stop.symptoms.trim()) {
      setError("Describe what you saw so the vet knows what to check.");
      return;
    }
    const result = await run(() => stopSession(session.sessionId, {
      symptoms: stop.symptoms.trim(),
      description: stop.description.trim(),
      urgency: stop.urgency,
    }));
    if (result) {
      setStopResult(result.report);
      setStopOpen(false);
      onChanged(null);
    }
  };

  return (
    <div className="groom-card session-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>{session.horseName}</h2>
          <p>
            {formatSessionDay(session.sessionDate)} · {sessionTime(session.sessionDate)} · {session.phaseName}
          </p>
        </div>
        <span className={`task-status ${st.slug}`}>{st.label}</span>
      </div>

      <dl className="session-facts">
        <div>
          <dt>Planned distance</dt>
          <dd>{session.plannedDistance != null ? `${session.plannedDistance.toLocaleString("en-US")} m` : "—"}</dd>
        </div>
        <div>
          <dt>HR ceiling</dt>
          <dd>{session.heartRateCeiling != null ? `${session.heartRateCeiling} bpm` : "—"}</dd>
        </div>
        <div>
          <dt>Peak HR so far</dt>
          <dd>{session.peakHeartRate != null ? `${session.peakHeartRate} bpm` : "—"}</dd>
        </div>
        <div>
          <dt>Alerts</dt>
          <dd>{session.alertCount}</dd>
        </div>
      </dl>

      {session.status === "SCHEDULED" && (
        <button type="button" className="start-session" onClick={start} disabled={busy}>
          {busy ? "Starting…" : "Start session"}
        </button>
      )}

      {session.status === "RUNNING" && (
        <form className="vitals-form" onSubmit={submitVitals} noValidate>
          <label>
            Heart rate (bpm)
            <input
              type="number" inputMode="numeric" min="20" max="300"
              value={heartRate} onChange={(e) => setHeartRate(e.target.value)} required
            />
          </label>
          <label>
            Speed (m/s, optional)
            <input
              type="number" inputMode="decimal" min="0" max="99.99" step="0.01"
              value={velocity} onChange={(e) => setVelocity(e.target.value)}
            />
          </label>
          <button type="submit" className="start-session" disabled={busy || heartRate === ""}>
            {busy ? "Sending…" : "Send reading"}
          </button>
        </form>
      )}

      {session.status === "RUNNING" && !stopOpen && (
        <button type="button" className="report-incident" onClick={() => setStopOpen(true)} disabled={busy}>
          Horse unwell? Stop this session
        </button>
      )}

      {session.status === "RUNNING" && stopOpen && (
        <form className="stop-form" onSubmit={submitStop} noValidate>
          <p className="stop-form__lead">
            The session stops now and the veterinarian is notified. The horse cannot train
            again until a vet has examined it.
          </p>
          <label>
            What did you see?
            <input
              list="common-symptoms"
              value={stop.symptoms}
              maxLength={255}
              onChange={(e) => setStop((s) => ({ ...s, symptoms: e.target.value }))}
              placeholder="e.g. Limping on the left foreleg"
              required
            />
            <datalist id="common-symptoms">
              {COMMON_SYMPTOMS.map((s) => <option key={s} value={s} />)}
            </datalist>
          </label>
          <label>
            Details (optional)
            <textarea
              rows={2}
              maxLength={2000}
              value={stop.description}
              onChange={(e) => setStop((s) => ({ ...s, description: e.target.value }))}
              placeholder="When it started, which lap, anything else the vet should know"
            />
          </label>
          <label>
            How urgent?
            <select value={stop.urgency} onChange={(e) => setStop((s) => ({ ...s, urgency: e.target.value }))}>
              {URGENCY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </label>
          <div className="stop-form__actions">
            <button type="submit" className="stop-button" disabled={busy}>
              {busy ? "Stopping…" : "Stop session and call the vet"}
            </button>
            <button type="button" className="stop-cancel" onClick={() => setStopOpen(false)} disabled={busy}>
              Keep training
            </button>
          </div>
        </form>
      )}

      {stopResult && (
        <p className="vitals-result alert" role="status">
          Session stopped. Incident report #{stopResult.reportId} was sent to the veterinarian.
        </p>
      )}

      {lastReading && (
        <p className={`vitals-result${lastReading.alertRaised ? " alert" : ""}`} role="status">
          {lastReading.alertRaised
            ? `Alert sent to the head trainer: ${lastReading.heartRate} bpm is over the ${lastReading.heartRateCeiling} bpm ceiling (${toSeverityLabel(lastReading.severity)}).`
            : lastReading.severity
              ? `${lastReading.heartRate} bpm is still over the ceiling. The head trainer has already been alerted.`
              : `Recorded ${lastReading.heartRate} bpm.`}
        </p>
      )}

      {isFinished(session.status) && (
        <p className="session-note">
          {session.status === "CANCELLED"
            ? "This session was cancelled."
            : session.evaluation
              ? `Evaluated by the head trainer: ${session.evaluation.charAt(0)}${session.evaluation.slice(1).toLowerCase()}.`
              : "Finished. Waiting for the head trainer's evaluation."}
        </p>
      )}

      {session.status === "RUNNING" && (
        <p className="session-note">
          The head trainer ends the session from the live monitor or the session review.
        </p>
      )}

      {error && <p className="session-error" role="alert">{error}</p>}
    </div>
  );
}

/* =====================================================
   SẮP TỚI
===================================================== */

function UpcomingCard({ items }) {
  return (
    <div className="groom-card tack-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>Coming up</h2>
          <p>{items.length === 0 ? "Nothing scheduled after today" : `${items.length} scheduled after today`}</p>
        </div>
      </div>

      {items.length > 0 && (
        <div className="tack-list">
          {items.slice(0, 5).map((s) => (
            <div className="tack-item" key={s.sessionId}>
              <strong>{s.horseName} · {s.phaseName}</strong>
              <span className="tack-chip ready">
                {formatSessionDay(s.sessionDate)} {sessionTime(s.sessionDate)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/* =====================================================
   TRANG
===================================================== */

export default function GroomDashboard() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState(null);
  const [loadError, setLoadError] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [selectedId, setSelectedId] = useState(null);
  const noticeRef = useRef(null);
  const upcomingRef = useRef(null);

  const load = useCallback(
    async (signal) => {
      try {
        const rows = await listMySessions(signal);
        setSessions(rows || []);
        setLoadError("");
      } catch (err) {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setLoadError(err.message || "Could not load your sessions.");
      }
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const today = todayIso();

  const todays = useMemo(
    () =>
      (sessions || [])
        .filter((s) => sessionDay(s.sessionDate) === today)
        .sort((a, b) => String(a.sessionDate).localeCompare(String(b.sessionDate))),
    [sessions, today]
  );

  const upcoming = useMemo(
    () =>
      (sessions || [])
        .filter((s) => s.status === "SCHEDULED" && sessionDay(s.sessionDate) > today)
        .sort((a, b) => String(a.sessionDate).localeCompare(String(b.sessionDate))),
    [sessions, today]
  );

  // Mặc định chọn buổi đang chạy, không có thì buổi kế tiếp chưa bắt đầu.
  useEffect(() => {
    if (selectedId != null && todays.some((s) => s.sessionId === selectedId)) return;
    const next = todays.find((s) => s.status === "RUNNING") || todays.find((s) => s.status === "SCHEDULED");
    setSelectedId(next ? next.sessionId : todays[0]?.sessionId ?? null);
  }, [todays, selectedId]);

  const counts = useMemo(
    () => ({
      done: todays.filter((s) => isFinished(s.status) && s.status !== "CANCELLED").length,
      running: todays.filter((s) => s.status === "RUNNING").length,
      scheduled: todays.filter((s) => s.status === "SCHEDULED").length,
    }),
    [todays]
  );

  const normalizedQuery = query.trim().toLowerCase();
  const matchesFilter = (s) => {
    if (statusFilter === "all") return true;
    if (statusFilter === "COMPLETED") return s.status === "COMPLETED" || s.status === "STOPPED_EARLY";
    return s.status === statusFilter;
  };
  const visible = todays.filter(
    (s) =>
      matchesFilter(s) &&
      (!normalizedQuery ||
        String(s.horseName).toLowerCase().includes(normalizedQuery) ||
        String(s.phaseName).toLowerCase().includes(normalizedQuery) ||
        sessionTime(s.sessionDate).includes(normalizedQuery))
  );

  const toggleFilter = (status) => setStatusFilter(statusFilter === status ? "all" : status);
  const scrollTo = (ref) => ref.current?.scrollIntoView({ behavior: "smooth", block: "center" });

  // Buổi tập vừa bắt đầu trả về bản mới; gửi số đo thì tải lại cả danh sách.
  const onChanged = (updated) => {
    if (updated) {
      setSessions((rows) => rows.map((s) => (s.sessionId === updated.sessionId ? updated : s)));
    } else {
      load();
    }
  };

  const selected = todays.find((s) => s.sessionId === selectedId) || null;
  const nextUp = todays.find((s) => s.status === "SCHEDULED") || upcoming[0];

  const now = new Date();
  const weekday = now.toLocaleDateString("en-GB", { weekday: "long" });
  const dateText = now.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });

  let notice = null;
  if (loadError) {
    notice = { tone: "error", title: "Could not load:", text: loadError };
  } else if (sessions && nextUp) {
    notice = {
      tone: "info",
      title: "Next session:",
      text: `${nextUp.horseName} · ${nextUp.phaseName} · ${formatSessionDay(nextUp.sessionDate)} ${sessionTime(nextUp.sessionDate)}`,
    };
  } else if (sessions) {
    notice = { tone: "info", title: "All clear:", text: "No session waiting to start." };
  }

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
              {weekday} · {dateText}
            </p>
          </div>

          <div className="groom-header-actions">
            <input
              type="search"
              className="groom-search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search horses, phases..."
              aria-label="Search today's sessions"
            />

            <NotificationBell className="groom-notification" />

            <ProfileCircle className="groom-profile" />
          </div>
        </header>

        <section className="groom-content">
          <div className="groom-stats">
            <StatCard
              value={counts.done}
              label="Finished"
              type="green"
              active={statusFilter === "COMPLETED"}
              onClick={() => toggleFilter("COMPLETED")}
            />
            <StatCard
              value={counts.running}
              label="Running"
              type="gold"
              active={statusFilter === "RUNNING"}
              onClick={() => toggleFilter("RUNNING")}
            />
            <StatCard
              value={counts.scheduled}
              label="Scheduled"
              type="pink"
              active={statusFilter === "SCHEDULED"}
              onClick={() => toggleFilter("SCHEDULED")}
            />
            <StatCard
              value={upcoming.length}
              label="Coming up"
              type="red"
              active={false}
              onClick={() => scrollTo(upcomingRef)}
            />
          </div>

          {notice && (
            <div ref={noticeRef} className={`groom-alert${notice.tone === "error" ? " error" : ""}`} role="status">
              <strong>{notice.title}</strong>
              <span>{notice.text}</span>
              {notice.tone === "error" && (
                <button type="button" className="groom-retry" onClick={() => load()}>
                  Try again
                </button>
              )}
            </div>
          )}

          {sessions === null && !loadError ? (
            <div className="groom-inline-state">Loading your sessions…</div>
          ) : (
            <div className="groom-grid">
              <SessionsCard
                items={visible}
                total={todays.length}
                filter={statusFilter}
                selectedId={selectedId}
                onSelect={setSelectedId}
              />

              <div className="groom-right-column">
                <SessionPanel session={selected} onChanged={onChanged} navigate={navigate} />

                <div ref={upcomingRef}>
                  <UpcomingCard items={upcoming} />
                </div>
              </div>
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
