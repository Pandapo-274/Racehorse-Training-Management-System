/*
 * src/features/groom/GroomDashboard.js - trang /groom (dashboard của Groom), phần việc của Groom trong Flow 2.
 * Bố cục: sidebar (GroomSidebar) | header (tìm kiếm, chuông, avatar) | các ô số | danh sách buổi tập HÔM NAY
 * (SessionsCard) bên trái + bảng điều khiển buổi đang chọn (SessionPanel) bên phải | các buổi sắp tới (UpcomingCard).
 * Vòng đời một buổi tập ở phía Groom:
 *   SCHEDULED --Start (đúng ngày, tới giờ)--> RUNNING --gửi số đo (nhịp tim / vận tốc) nhiều lần-->
 *     --Finish (sau giờ kết thúc dự kiến)--> COMPLETED
 *     --End early (lý do; phải gửi đủ nhịp tim và vận tốc ít nhất một lần)--> STOPPED_EARLY
 *     --End early vì INJURY (triệu chứng + mức khẩn cấp)--> STOPPED_EARLY + báo cáo sự cố cho bác sĩ
 * Mọi luật đều được backend (TrainingSessionService, AlertService) kiểm lại.
 */
import { useSignOutModal } from "../auth/SignOutModal";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import "./groom.css";
import ConfirmModal from "../common/ConfirmModal";
import { handleAuthError } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import NotificationBell from "../notification/NotificationBell";
import {
  completeRecoverySession, enterRecoverySession,
  finishSession, formatSessionDay, isFinished, listMySessions, saveSessionVideo, sendVitals, sessionDay, sessionTime,
  startSession, stopSession, toSessionStatus, toSeverityLabel, todayIso,
  COMMON_SYMPTOMS, URGENCY_OPTIONS, EARLY_STOP_REASON_OPTIONS,
} from "./groomApi";

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

// Sidebar của Groom: logo, các mục menu (NavItems theo vai trò), thẻ hồ sơ và nút đăng xuất.
function GroomSidebar() {
  const navigate = useNavigate();

  const { requestSignOut, modalElement } = useSignOutModal(navigate);

  return (
    <aside className="groom-sidebar">
      {modalElement}
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
        <button type="button" className="groom-signout" onClick={requestSignOut}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          Sign out
        </button>

        <ProfileCard className="groom-user" avatarClassName="groom-avatar" role="Groom" />
      </div>
    </aside>
  );
}

// Ô số liệu (đã xong / đang chạy / đã lên lịch hôm nay); bấm vào để lọc danh sách theo trạng thái đó.
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

// Danh sách buổi tập hôm nay sau khi lọc; bấm một dòng để chọn (onSelect) và mở nó ở SessionPanel.
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
                <div className="task-time">
                  {sessionTime(s.sessionDate)}
                  <small style={{ display: "block", fontSize: "11px", opacity: 0.75 }}>
                    {s.plannedDuration || 60}m
                  </small>
                </div>

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

/*
 * Bảng điều khiển của MỘT buổi tập. Props: session, onChanged(buổi đã cập nhật | null = tải lại), navigate.
 * State: heartRate / velocity (ô nhập số đo), lastReading (kết quả lần gửi gần nhất: có sinh cảnh báo không),
 * earlyOpen / earlyReason (form kết thúc sớm), stop (triệu chứng khi chấn thương), confirmConfig (ConfirmModal),
 * currentTime (đồng hồ cập nhật mỗi 3 giây để bật nút Start / Finish đúng lúc).
 */
function SessionPanel({ session, onChanged, navigate }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [heartRate, setHeartRate] = useState("");
  const [velocity, setVelocity] = useState("");
  const [lastReading, setLastReading] = useState(null);
  const [earlyOpen, setEarlyOpen] = useState(false);
  const [earlyReason, setEarlyReason] = useState("TARGET_REACHED");
  const [stop, setStop] = useState({ symptoms: "", description: "", urgency: "HIGH" });
  const [stopResult, setStopResult] = useState(null);
  const [recoveryHrr, setRecoveryHrr] = useState("");
  const [recoveryRemarks, setRecoveryRemarks] = useState("");
  const [videoUrl, setVideoUrl] = useState(session?.videoUrl || "");
  const [videoNotice, setVideoNotice] = useState("");
  const [currentTime, setCurrentTime] = useState(() => Date.now());
  const [confirmConfig, setConfirmConfig] = useState({
    isOpen: false,
    title: "",
    message: "",
    confirmText: "Confirm",
    confirmTone: "primary",
    icon: "♞",
    onConfirm: () => {},
  });

  // Đồng hồ nội bộ: chạy mỗi 3s khi SCHEDULED/RUNNING hoặc 1s khi RECOVERY (đếm ngược recovery).
  useEffect(() => {
    if (session?.status !== "SCHEDULED" && session?.status !== "RUNNING" && session?.status !== "RECOVERY") return undefined;
    const ms = session?.status === "RECOVERY" ? 1000 : 3000;
    const interval = setInterval(() => setCurrentTime(Date.now()), ms);
    return () => clearInterval(interval);
  }, [session?.status]);

  const recoveryEndMs = useMemo(() => {
    if (session?.recoveryEndsAt) {
      const d = new Date(session.recoveryEndsAt);
      if (!Number.isNaN(d.getTime())) return d.getTime();
    }
    if (session?.recoveryStartedAt && session?.recoveryDurationMinutes) {
      const d = new Date(session.recoveryStartedAt);
      if (!Number.isNaN(d.getTime())) return d.getTime() + session.recoveryDurationMinutes * 60000;
    }
    return 0;
  }, [session?.recoveryEndsAt, session?.recoveryStartedAt, session?.recoveryDurationMinutes]);

  const recoveryRemainingSeconds = recoveryEndMs > 0
    ? Math.max(0, Math.ceil((recoveryEndMs - currentTime) / 1000))
    : (session?.recoveryDurationMinutes || 15) * 60;
  const recoveryCountdown = `${String(Math.floor(recoveryRemainingSeconds / 60)).padStart(2, "0")}:${String(recoveryRemainingSeconds % 60).padStart(2, "0")}`;

  // sessionDate là giờ địa phương không múi giờ (giờ người dùng nhập), new Date() đọc đúng là giờ địa phương.
  // plannedEndMs = giờ bắt đầu + thời lượng dự kiến (mặc định 60 phút).
  const scheduledTimeMs = useMemo(() => {
    if (!session?.sessionDate) return 0;
    const d = new Date(session.sessionDate);
    return Number.isNaN(d.getTime()) ? 0 : d.getTime();
  }, [session?.sessionDate]);

  const plannedEndMs = useMemo(() => {
    const dur = session?.plannedDuration || 60;
    return scheduledTimeMs ? scheduledTimeMs + dur * 60000 : 0;
  }, [scheduledTimeMs, session?.plannedDuration]);

  const isDue = scheduledTimeMs <= currentTime;
  const isEndReached = plannedEndMs > 0 && currentTime >= plannedEndMs;
  const hasReportedBoth = session?.peakHeartRate != null && session?.peakVelocity != null;
  // Flow 1: asked to stop because the horse lost its Head Trainer - no readings needed first.
  const canEndEarly = hasReportedBoth || earlyReason === "NO_HEAD_TRAINER";

  // Đổi sang buổi khác thì xoá sạch các ô nhập và kết quả của buổi trước.
  useEffect(() => {
    setError("");
    setHeartRate("");
    setVelocity("");
    setLastReading(null);
    setEarlyOpen(false);
    setEarlyReason("TARGET_REACHED");
    setStop({ symptoms: "", description: "", urgency: "HIGH" });
    setStopResult(null);
    setRecoveryHrr("");
    setRecoveryRemarks("");
    setVideoUrl(session?.videoUrl || "");
    setVideoNotice("");
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

  // Khung chạy chung cho mọi thao tác: khoá nút, gọi API, hiện lỗi đầu tiên (ưu tiên lỗi từng ô của backend).
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

  // PUT /api/training-sessions/{id}/start: backend chặn nếu sai ngày, ngựa bị khoá / chưa khám sự cố, giáo án
  // không ACTIVE, hoặc ngựa đang có buổi RUNNING khác.
  const start = async () => {
    const updated = await run(() => startSession(session.sessionId));
    if (updated) onChanged(updated);
  };

  // V50: RUNNING -> RECOVERY: hoàn tất chạy và kích hoạt đếm ngược recovery của Phase.
  const startRecovery = async () => {
    const updated = await run(() => enterRecoverySession(session.sessionId));
    if (updated) onChanged(updated);
  };

  // V50: RECOVERY -> COMPLETED: gửi actual_hrr và hoàn tất buổi tập.
  const submitRecovery = async (event) => {
    event.preventDefault();
    const hrr = Number(recoveryHrr);
    if (!recoveryHrr || !Number.isInteger(hrr) || hrr < 20 || hrr > 300) {
      setError("Actual recovery heart rate must be a whole number between 20 and 300 bpm.");
      return;
    }
    const updated = await run(() => completeRecoverySession(session.sessionId, {
      actualHrr: hrr,
      remarks: recoveryRemarks,
    }));
    if (updated) {
      setRecoveryHrr("");
      setRecoveryRemarks("");
      onChanged(updated);
    }
  };

  const handleSaveVideo = async (event) => {
    event.preventDefault();
    setVideoNotice("");
    const trimmed = videoUrl.trim();
    if (trimmed && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
      setError("Video URL must start with http:// or https://");
      return;
    }
    const updated = await run(() => saveSessionVideo(session.sessionId, trimmed));
    if (updated) {
      setVideoNotice(trimmed ? "Session video saved." : "Session video cleared.");
      onChanged(updated);
    }
  };

  // Gửi một lần đo: kiểm khoảng hợp lệ (nhịp tim 20-300 nguyên, vận tốc 0-99.99) rồi POST vitals. Kết quả cho biết
  // có sinh cảnh báo hay không (alertRaised, severity) để hiện ngay cho Groom.
  const submitVitals = async (event) => {
    event.preventDefault();
    const hr = heartRate === "" ? null : Number(heartRate);
    if (hr !== null && (!Number.isInteger(hr) || hr < 20 || hr > 300)) {
      setError("Heart rate must be a whole number between 20 and 300.");
      return;
    }
    const v = velocity === "" ? null : Number(velocity);
    if (v != null && (!Number.isFinite(v) || v < 0 || v > 99.99)) {
      setError("Speed must be between 0 and 99.99 km/h.");
      return;
    }
    if (hr === null && v === null) {
      setError("Please provide at least Heart rate or Speed.");
      return;
    }
    const result = await run(() => sendVitals(session.sessionId, { heartRate: hr, velocity: v }));
    if (result) {
      setLastReading(result);
      setHeartRate("");
      setVelocity("");
      onChanged(null);
    }
  };

  // Finish trước giờ kết thúc dự kiến thì mở form kết thúc sớm thay vì kết thúc bình thường.
  const finishNormally = async () => {
    if (!isEndReached) {
      setEarlyOpen(true);
      return;
    }
    setConfirmConfig({
      isOpen: true,
      title: "Finish Training Session",
      message: `Finish the session with ${session.horseName}? You cannot send readings after this.`,
      confirmText: "Finish Session",
      confirmTone: "primary",
      icon: "🏁",
      onConfirm: async () => {
        setConfirmConfig((c) => ({ ...c, isOpen: false }));
        const updated = await run(() => finishSession(session.sessionId, { earlyStop: false }));
        if (updated) onChanged(updated);
      },
    });
  };

  // Kết thúc sớm. INJURY -> PUT /stop (backend mở báo cáo sự cố cho bác sĩ, ngựa bị IncidentGuard chặn tập tới khi
  // được khám; câu xác nhận ghi "Vet Call" nhưng thực tế là báo cáo sự cố, không phải VET_CALL của V27).
  // Lý do khác -> PUT /finish với earlyStop = true và earlyStopReason.
  const submitEarlyFinish = async (event) => {
    event.preventDefault();
    if (!canEndEarly) {
      setError("You must report both Heart Rate and Speed at least once during the session before ending early.");
      return;
    }
    if (earlyReason === "INJURY") {
      if (!stop.symptoms.trim()) {
        setError("Describe the injury/symptoms so the veterinarian knows what to check.");
        return;
      }
      setConfirmConfig({
        isOpen: true,
        title: "Emergency Injury Stop",
        message: `Confirm early termination due to INJURY for ${session.horseName}? A high-urgency Vet Call will be created immediately.`,
        confirmText: "Confirm & Call Vet",
        confirmTone: "danger",
        icon: "🚨",
        onConfirm: async () => {
          setConfirmConfig((c) => ({ ...c, isOpen: false }));
          const result = await run(() =>
            stopSession(session.sessionId, {
              symptoms: stop.symptoms.trim(),
              description: stop.description.trim(),
              urgency: stop.urgency,
            })
          );
          if (result) {
            setStopResult(result.report);
            setEarlyOpen(false);
            onChanged(null);
          }
        },
      });
      return;
    }

    const reasonLabel = EARLY_STOP_REASON_OPTIONS.find((o) => o.value === earlyReason)?.label || earlyReason;
    setConfirmConfig({
      isOpen: true,
      title: "End Session Early",
      message: `Are you sure you want to end the session with ${session.horseName} early?\nReason: ${reasonLabel}`,
      confirmText: "End Session Early",
      confirmTone: "danger",
      icon: "⏱️",
      onConfirm: async () => {
        setConfirmConfig((c) => ({ ...c, isOpen: false }));
        const updated = await run(() =>
          finishSession(session.sessionId, {
            earlyStop: true,
            earlyStopReason: earlyReason,
          })
        );
        if (updated) {
          setEarlyOpen(false);
          onChanged(updated);
        }
      },
    });
  };

  return (
    <div className="groom-card session-card">
      <div className="groom-card-title">
        <span className="groom-diamond" aria-hidden="true" />
        <div>
          <h2>{session.horseName}</h2>
          <p>
            {formatSessionDay(session.sessionDate)} · {sessionTime(session.sessionDate)} ({session.plannedDuration || 60} mins) · {session.phaseName}
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
          <dt>Planned duration</dt>
          <dd>{session.plannedDuration || 60} mins</dd>
        </div>
        <div>
          <dt>Peak HR so far</dt>
          <dd>{session.peakHeartRate != null ? `${session.peakHeartRate} bpm` : "—"}</dd>
        </div>
        <div>
          <dt>Peak speed so far</dt>
          <dd>{session.peakVelocity != null ? `${session.peakVelocity} km/h` : "—"}</dd>
        </div>
      </dl>

      {session.remarks && (
        <div
          style={{
            marginTop: "12px",
            marginBottom: "12px",
            padding: "10px 14px",
            borderRadius: "8px",
            backgroundColor: session.remarks.includes("[Recovery Session]") ? "#ecfdf5" : "#f8fafc",
            border: `1px solid ${session.remarks.includes("[Recovery Session]") ? "#a7f3d0" : "#e2e8f0"}`,
            fontSize: "12px",
            lineHeight: 1.5,
            color: session.remarks.includes("[Recovery Session]") ? "#065f46" : "#334155",
          }}
        >
          <strong>{session.remarks.includes("[Recovery Session]") ? "🩺 Recovery Protocol & Instructions:" : "📝 Trainer Instructions:"}</strong>
          <div style={{ marginTop: "4px" }}>
            {session.remarks.replace(/^\[Recovery Session\]\s*/, "")}
          </div>
        </div>
      )}

      {session.status === "SCHEDULED" && (
        <div>
          <button
            type="button"
            className="start-session"
            onClick={start}
            disabled={busy || !isDue}
            title={!isDue ? `Scheduled for ${sessionTime(session.sessionDate)} (not yet due)` : "Start training session"}
          >
            {busy
              ? "Starting…"
              : !isDue
                ? `Starts at ${sessionTime(session.sessionDate)} (Not yet due)`
                : "Start session"}
          </button>
          {!isDue && (
            <p
              className="session-not-due-hint"
              style={{
                marginTop: "10px",
                fontSize: "12px",
                color: "#92400e",
                backgroundColor: "#fef3c7",
                border: "1px solid #fde68a",
                padding: "8px 12px",
                borderRadius: "8px",
                textAlign: "center",
                fontWeight: "500",
              }}
            >
              ⏳ This session is scheduled for <strong>{sessionTime(session.sessionDate)}</strong>. It can only be started at or after the scheduled time.
            </p>
          )}
        </div>
      )}

      {session.status === "RUNNING" && (
        <form className="vitals-form" onSubmit={submitVitals} noValidate>
          <label>
            Heart rate (bpm)
            <input
              type="number" inputMode="numeric" min="20" max="300"
              placeholder="e.g. 150 (Optional)"
              value={heartRate} onChange={(e) => setHeartRate(e.target.value)}
            />
          </label>
          <label>
            Speed (km/h)
            <input
              type="number" inputMode="decimal" min="0" max="99.99" step="0.01"
              value={velocity} onChange={(e) => setVelocity(e.target.value)}
              placeholder="e.g. 55.0"
            />
          </label>
          <button type="submit" className="start-session" disabled={busy || (heartRate === "" && velocity === "")}>
            {busy ? "Sending…" : "Send reading"}
          </button>
        </form>
      )}

      {session.status === "RUNNING" && (
        <div
          style={{
            marginTop: "8px",
            marginBottom: "8px",
            padding: "8px 12px",
            borderRadius: "8px",
            fontSize: "12px",
            background: hasReportedBoth ? "#ecfdf5" : "#fffbeb",
            color: hasReportedBoth ? "#065f46" : "#92400e",
            border: `1px solid ${hasReportedBoth ? "#a7f3d0" : "#fde68a"}`,
          }}
        >
          {hasReportedBoth
            ? "✓ Both Heart Rate and Speed have been reported at least once. Early session finish is unlocked."
            : "ℹ️ To end this session early, you must report both Heart Rate and Speed at least once during the session."}
        </div>
      )}

      {session.status === "RUNNING" && !earlyOpen && (
        <>
          <button
            type="button"
            className="start-session"
            onClick={startRecovery}
            disabled={busy}
            style={{ background: "linear-gradient(135deg, #0284c7, #0369a1)", marginBottom: "8px" }}
          >
            {busy ? "Starting recovery…" : `⏱️ Complete Run & Start Recovery (${session.recoveryDurationMinutes || 15} min)`}
          </button>
          {isEndReached && (
            <button type="button" className="start-session" onClick={finishNormally} disabled={busy}>
              {busy ? "Saving…" : "Finish session (Scheduled duration completed)"}
            </button>
          )}
          <button
            type="button"
            className="report-incident"
            onClick={() => setEarlyOpen(true)}
            disabled={busy}
          >
            End session early / Call Vet
          </button>
        </>
      )}

      {session.status === "RECOVERY" && (
        <div
          style={{
            marginTop: "12px",
            padding: "14px",
            borderRadius: "12px",
            background: "#f0f9ff",
            border: "1px solid #bae6fd",
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px", gap: "10px", flexWrap: "wrap" }}>
            <div>
              <strong style={{ color: "#0369a1", fontSize: "15px" }}>
                ❄️ Recovery Phase Countdown
              </strong>
              <div style={{ fontSize: "12px", color: "#475569" }}>
                Phase cooldown: {session.recoveryDurationMinutes || 15} mins
                {session.targetHrr ? ` · Target HRR: ≤ ${session.targetHrr} bpm` : ""}
              </div>
            </div>
            <div
              style={{
                fontSize: "24px",
                fontWeight: 800,
                fontVariantNumeric: "tabular-nums",
                color: recoveryRemainingSeconds === 0 ? "#15803d" : "#0c4a6e",
                background: "#ffffff",
                padding: "6px 14px",
                borderRadius: "10px",
                border: "1px solid #7dd3fc",
              }}
            >
              {recoveryRemainingSeconds === 0 ? "00:00 · Done" : recoveryCountdown}
            </div>
          </div>

          <form onSubmit={submitRecovery} style={{ display: "grid", gap: "10px" }}>
            <label style={{ display: "grid", gap: "4px", fontSize: "13px", fontWeight: 600 }}>
              Actual Recovery Heart Rate (actual_hrr, bpm) *
              <input
                type="number"
                inputMode="numeric"
                min="20"
                max="300"
                required
                value={recoveryHrr}
                onChange={(e) => setRecoveryHrr(e.target.value)}
                placeholder={session.targetHrr ? `e.g. ${session.targetHrr}` : "e.g. 112"}
              />
            </label>

            {session.targetHrr != null && recoveryHrr !== "" && Number(recoveryHrr) > session.targetHrr && (
              <div
                role="alert"
                style={{
                  padding: "8px 12px",
                  borderRadius: "8px",
                  background: "#fef2f2",
                  border: "1px solid #fca5a5",
                  color: "#b91c1c",
                  fontSize: "12px",
                  fontWeight: 600,
                }}
              >
                ⚠️ Overload Warning: Actual HRR ({recoveryHrr} bpm) exceeds plan target HRR ({session.targetHrr} bpm). An overload alert will be sent to the Head Trainer.
              </div>
            )}

            <label style={{ display: "grid", gap: "4px", fontSize: "13px", fontWeight: 600 }}>
              Recovery notes (optional)
              <input
                type="text"
                maxLength={500}
                value={recoveryRemarks}
                onChange={(e) => setRecoveryRemarks(e.target.value)}
                placeholder="Breathing rate, stride cooldown, hydration..."
              />
            </label>

            <button type="submit" className="start-session" disabled={busy || !recoveryHrr}>
              {busy ? "Submitting…" : "✓ Submit Recovery HR & Complete Session"}
            </button>
          </form>
        </div>
      )}

      {session.status === "RUNNING" && earlyOpen && (
        <form className="stop-form" onSubmit={submitEarlyFinish} noValidate>
          <p className="stop-form__lead">
            <strong>End Session Early</strong> — Select the reason for ending before the {session.plannedDuration || 60}-minute duration completes.
          </p>

          {!canEndEarly && (
            <p className="session-error" role="alert" style={{ marginBottom: "10px" }}>
              ⚠️ Cannot end session early yet: You must report both Heart Rate (current: {session.peakHeartRate ?? "none"}) and Speed (current: {session.peakVelocity ?? "none"}) at least once first.
            </p>
          )}

          <label>
            Reason for ending early
            <select value={earlyReason} onChange={(e) => setEarlyReason(e.target.value)}>
              {EARLY_STOP_REASON_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>

          {earlyReason === "INJURY" && (
            <>
              <p className="stop-form__lead" style={{ color: "#b91c1c", marginTop: "6px" }}>
                Veterinary Alert: Stopping due to injury will create an incident report, notify the Veterinarian, and lock the horse from training until cleared.
              </p>
              <label>
                What symptoms / injury did you see?
                <input
                  list="common-symptoms"
                  value={stop.symptoms}
                  maxLength={255}
                  onChange={(e) => setStop((s) => ({ ...s, symptoms: e.target.value }))}
                  placeholder="e.g. Limping on the left foreleg"
                  required
                />
                <datalist id="common-symptoms">
                  {COMMON_SYMPTOMS.map((s) => (
                    <option key={s} value={s} />
                  ))}
                </datalist>
              </label>
              <label>
                Details for Veterinarian (optional)
                <textarea
                  rows={2}
                  maxLength={2000}
                  value={stop.description}
                  onChange={(e) => setStop((s) => ({ ...s, description: e.target.value }))}
                  placeholder="When it started, which lap, anything else the vet should know"
                />
              </label>
              <label>
                Urgency
                <select value={stop.urgency} onChange={(e) => setStop((s) => ({ ...s, urgency: e.target.value }))}>
                  {URGENCY_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
            </>
          )}

          <div className="stop-form__actions">
            <button
              type="submit"
              className="stop-button"
              disabled={busy || !canEndEarly}
            >
              {busy
                ? "Processing…"
                : earlyReason === "INJURY"
                  ? "Confirm: Stop session & Call Vet"
                  : "Confirm & End session early"}
            </button>
            <button type="button" className="stop-cancel" onClick={() => setEarlyOpen(false)} disabled={busy}>
              Keep training
            </button>
          </div>
        </form>
      )}

      {stopResult && (
        <p className="vitals-result alert" role="status">
          Session stopped early due to injury. Incident report #{stopResult.reportId} was sent to the veterinarian.
        </p>
      )}

      {lastReading && (
        <p className={`vitals-result${lastReading.alertRaised ? " alert" : ""}`} role="status">
          {lastReading.sessionStoppedEarly
            ? `⛔ ${lastReading.message}`
            : lastReading.alertRaised
              ? `Alert sent to the head trainer: ${lastReading.message || `${lastReading.heartRate} bpm is over the ${lastReading.heartRateCeiling} bpm ceiling`} (${toSeverityLabel(lastReading.severity)} severity).`
              : lastReading.severity
                ? `${lastReading.message || `${lastReading.heartRate} bpm is still over the ceiling`}. The head trainer has already been alerted.`
                : lastReading.heartRate != null
                  ? `Recorded ${lastReading.heartRate} bpm${lastReading.velocity != null ? ` at ${lastReading.velocity} km/h` : ""}.`
                  : `Recorded speed ${lastReading.velocity} km/h.`}
        </p>
      )}

      {(session.status === "RUNNING" || session.status === "RECOVERY" || session.status === "COMPLETED" || session.status === "STOPPED_EARLY") && (
        <form
          onSubmit={handleSaveVideo}
          style={{
            marginTop: 14,
            padding: "12px 14px",
            borderRadius: "10px",
            border: "1px solid #f0dce6",
            background: "#fff8fb",
          }}
        >
          <strong style={{ display: "block", fontSize: 13, color: "#1e1b4b", marginBottom: 6 }}>
            Training Session Video
          </strong>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
            <input
              type="url"
              value={videoUrl}
              maxLength={500}
              onChange={(e) => setVideoUrl(e.target.value)}
              placeholder="Paste recorded video link (https://...)"
              style={{
                flex: "1 1 220px",
                height: 36,
                padding: "0 10px",
                borderRadius: 8,
                border: "1px solid #e2e8f0",
                background: "#fff",
              }}
            />
            <button
              type="submit"
              disabled={busy}
              className="groom-primary-btn"
              style={{ height: 36, padding: "0 14px" }}
            >
              {busy ? "Saving…" : "Save Video"}
            </button>
          </div>
          {session.videoUrl && (
            <div style={{ marginTop: 6, fontSize: 12 }}>
              <a href={session.videoUrl} target="_blank" rel="noopener noreferrer" style={{ color: "#d93b87", fontWeight: 600 }}>
                Open current recorded video ↗
              </a>
            </div>
          )}
          {videoNotice && (
            <p style={{ margin: "6px 0 0", fontSize: 12, color: "#166534", fontWeight: 600 }}>✓ {videoNotice}</p>
          )}
        </form>
      )}

      {isFinished(session.status) && (
        <p className="session-note">
          {session.status === "CANCELLED"
            ? "This session was cancelled."
            : session.evaluation
              ? `Evaluated by the head trainer: ${session.evaluation.charAt(0)}${session.evaluation.slice(1).toLowerCase()}.`
              : `Finished${session.earlyStopReason ? ` (Early reason: ${session.earlyStopReason})` : ""}${session.actualHrr ? ` · Recovery HRR: ${session.actualHrr} bpm` : ""}.${session.overloadWarning ? " 🔥 Overload warning flagged!" : ""} Waiting for the head trainer's evaluation.`}
        </p>
      )}

      {session.status === "RUNNING" && (
        <p className="session-note">
          If the scheduled {session.plannedDuration || 60}-minute duration expires without an update, the system automatically completes this session and sends it to the Head Trainer for review.
        </p>
      )}

      {error && <p className="session-error" role="alert">{error}</p>}

      <ConfirmModal
        isOpen={confirmConfig.isOpen}
        title={confirmConfig.title}
        message={confirmConfig.message}
        confirmText={confirmConfig.confirmText}
        confirmTone={confirmConfig.confirmTone}
        icon={confirmConfig.icon}
        onConfirm={confirmConfig.onConfirm}
        onCancel={() => setConfirmConfig((c) => ({ ...c, isOpen: false }))}
      />
    </div>
  );
}

/* =====================================================
   SẮP TỚI
===================================================== */

// Các buổi SCHEDULED của những ngày sau hôm nay.
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
                {formatSessionDay(s.sessionDate)} {sessionTime(s.sessionDate)} ({s.plannedDuration || 60}m)
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

/*
 * Component trang. sessions: mọi buổi được giao cho Groom này (GET /api/training-sessions, backend tự lọc).
 * todays: buổi của hôm nay + buổi đang RUNNING; upcoming: buổi SCHEDULED các ngày sau.
 * selectedId: buổi đang mở ở SessionPanel - tự chọn buổi RUNNING, không có thì buổi SCHEDULED đầu tiên.
 */
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
        .filter((s) => s.status === "RUNNING" || sessionDay(s.sessionDate) === today)
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

  // Tự chọn buổi khi buổi đang chọn không còn trong danh sách hôm nay (hoặc lúc mới tải).
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

  // onChanged(updated): có bản cập nhật thì thay đúng dòng đó; null (vd. sau khi dừng vì chấn thương) thì tải lại hết.
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