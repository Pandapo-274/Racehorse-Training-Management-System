/*
 * src/features/trainer/SessionEvaluationPage.js - trang /trainer/session-review (Head Trainer đánh giá buổi tập).
 * Trái: danh sách buổi đã chạy (COMPLETED / STOPPED_EARLY) với bộ lọc NEEDS_REVIEW / REVIEWED / ARCHIVED / RECOVERY /
 * theo xếp loại. Phải: form đánh giá buổi đang chọn - số liệu thực tế, xếp loại POOR..EXCELLENT, nhận xét, đánh dấu
 * chạy thử + link video. Lưu = PUT /api/sessions/{id}/evaluation; chủ ngựa thấy đánh giá trên trang của họ.
 * Hạn đánh giá 48 giờ sau khi buổi kết thúc; quá hạn backend tự chuyển reviewStatus = ARCHIVED_AUTO.
 */
import { useSignOutModal } from "../auth/SignOutModal";
import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import { handleAuthError } from "../auth/authService";
import { evaluateSession, fetchSessions, hasNotRun, submitRecoveryToVet } from "./sessionEvaluationApi";
import "./trainer.css";
import "./schedule.css";
import "./session-evaluation.css";

function isRecoverySession(session) {
  if (!session) return false;
  return Boolean(
    (session.remarks && session.remarks.includes("[Recovery Session]")) ||
    (session.phaseName && session.phaseName.toLowerCase().includes("recovery")) ||
    (session.plannedDistance && session.plannedDistance <= 800 && session.remarks && session.remarks.toLowerCase().includes("recovery"))
  );
}

function HorseshoeIcon() {
  return (
    <svg
      className="trainer-nav-icon"
      viewBox="0 0 24 24"
      width="20"
      height="20"
      aria-hidden="true"
    >
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

function SessionEvaluationSidebar({ onSignOut }) {
  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.png" alt="Tracen logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>

      <div className="trainer-menu-title">
        <HorseshoeIcon /> MENU
      </div>

      <nav className="trainer-nav">
        <NavItems role="HEAD_TRAINER" prefix="trainer" icon={<HorseshoeIcon />} />
      </nav>

      <div className="trainer-sidebar-bottom">
        <button type="button" className="trainer-signout" onClick={onSignOut}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          Sign out
        </button>

        <ProfileCard
          className="trainer-user"
          avatarClassName="trainer-avatar"
          role="Head Trainer"
        />
      </div>
    </aside>
  );
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

function statusLabel(status) {
  return String(status || "UNKNOWN").replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());
}

// Huy hiệu hạn đánh giá: đã lưu trữ tự động / đã gửi / còn bao nhiêu giờ trong 48 giờ (đỏ khi còn <= 12 giờ).
// Lưu ý: endedAt là giờ UTC không kèm múi giờ nhưng được đọc bằng new Date() (hiểu là giờ địa phương), nên số giờ
// còn lại hiển thị lệch múi giờ (7 giờ ở Việt Nam).
function computeReviewDeadlineBadge(session) {
  if (!session) return null;
  if (session.reviewStatus === "ARCHIVED_AUTO") {
    return {
      text: "Archived · Auto-reported (>48h)",
      bg: "#fef3c7",
      color: "#92400e",
      border: "#fcd34d",
    };
  }
  if (session.reviewStatus === "REVIEWED") {
    return {
      text: "Official Review Sent",
      bg: "#dcfce7",
      color: "#166534",
      border: "#86efac",
    };
  }
  const endRef = session.endedAt || session.sessionDate;
  if (!endRef) return null;
  const endMs = new Date(endRef).getTime();
  if (Number.isNaN(endMs)) return null;
  const deadlineMs = endMs + 48 * 3600 * 1000;
  const remainingHours = Math.max(0, Math.ceil((deadlineMs - Date.now()) / (3600 * 1000)));
  return {
    text: `Review due in ${remainingHours}h (48h limit)`,
    bg: remainingHours <= 12 ? "#fee2e2" : "#e0f2fe",
    color: remainingHours <= 12 ? "#991b1b" : "#075985",
    border: remainingHours <= 12 ? "#fca5a5" : "#7dd3fc",
  };
}

const RATING_LABEL = {
  POOR: "Poor",
  FAIR: "Fair",
  GOOD: "Good",
  EXCELLENT: "Excellent",
};

function computeAutoRating(session, formValues) {
  if (!session) return { rating: "GOOD", scorePct: null };
  let sum = 0;
  let count = 0;

  const actualDist = formValues?.actualDistance !== "" && formValues?.actualDistance != null
    ? Number(formValues.actualDistance)
    : session.actualDistance;
  if (session.plannedDistance > 0 && actualDist != null && Number.isFinite(actualDist)) {
    sum += Math.min(100, (actualDist * 100) / session.plannedDistance);
    count++;
  }

  const actualDur = formValues?.durationMinutes !== "" && formValues?.durationMinutes != null
    ? Number(formValues.durationMinutes)
    : session.durationMinutes;
  if (session.plannedDuration > 0 && actualDur != null && Number.isFinite(actualDur)) {
    sum += Math.min(100, (actualDur * 100) / session.plannedDuration);
    count++;
  }

  const peakHr = formValues?.peakHeartRate !== "" && formValues?.peakHeartRate != null
    ? Number(formValues.peakHeartRate)
    : session.peakHeartRate;
  if (session.heartRateCeiling > 0 && peakHr != null && Number.isFinite(peakHr)) {
    const hrScore = peakHr <= session.heartRateCeiling
      ? 100
      : Math.max(0, 100 - ((peakHr - session.heartRateCeiling) * 100) / session.heartRateCeiling);
    sum += hrScore;
    count++;
  }

  const peakVel = formValues?.peakVelocity !== "" && formValues?.peakVelocity != null
    ? Number(formValues.peakVelocity)
    : session.peakVelocity;
  if (session.targetVelocity > 0 && peakVel != null && Number.isFinite(Number(peakVel))) {
    const target = Number(session.targetVelocity);
    const actual = Number(peakVel);
    let speedScore = 100;
    if (actual > target * 1.10) {
      speedScore = Math.max(0, 100 - ((actual - target) * 100) / target);
    } else if (actual < target * 0.85) {
      speedScore = Math.max(0, (actual * 100) / target);
    }
    sum += speedScore;
    count++;
  }

  const recHrr = formValues?.recoveryHeartRate !== "" && formValues?.recoveryHeartRate != null
    ? Number(formValues.recoveryHeartRate)
    : (session.recoveryHeartRate ?? session.actualHrr);
  if (session.targetHrr > 0 && recHrr != null && Number.isFinite(Number(recHrr))) {
    const hrrScore = Number(recHrr) <= session.targetHrr
      ? 100
      : Math.max(0, 100 - ((Number(recHrr) - session.targetHrr) * 100) / session.targetHrr);
    sum += hrrScore;
    count++;
  }

  if (count === 0) {
    return { rating: session.evaluation || "GOOD", scorePct: null };
  }
  const pct = Math.round(sum / count);
  let rating = "POOR";
  if (pct >= 100) rating = "EXCELLENT";
  else if (pct >= 90) rating = "GOOD";
  else if (pct >= 80) rating = "FAIR";
  return { rating, scorePct: pct };
}

// Giá trị ban đầu của form: lấy số đã có của buổi; chưa có thì dùng số dự kiến (planned...) làm gợi ý.
function emptyForm(session) {
  const auto = computeAutoRating(session, null);
  return {
    actualDistance: session?.actualDistance ?? session?.plannedDistance ?? "",
    durationMinutes: session?.durationMinutes ?? session?.plannedDuration ?? "",
    avgHeartRate: session?.avgHeartRate ?? session?.peakHeartRate ?? "",
    peakHeartRate: session?.peakHeartRate ?? "",
    recoveryHeartRate: session?.recoveryHeartRate ?? session?.actualHrr ?? "",
    peakVelocity: session?.peakVelocity ?? "",
    evaluation: auto.rating,
    remarks: session?.remarks || "",
    status: session?.status === "STOPPED_EARLY" ? "STOPPED_EARLY" : "COMPLETED",
    trialRun: !!session?.trialRun,
  };
}

export default function SessionEvaluationPage() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [form, setForm] = useState(emptyForm(null));
  const [filter, setFilter] = useState("NEEDS_REVIEW");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [sendingToVet, setSendingToVet] = useState(false);
  const [sentToVetList, setSentToVetList] = useState([]);

  // Tải danh sách; giữ buổi đang chọn nếu vẫn còn, không thì chọn buổi đầu tiên chưa được đánh giá.
  const loadSessions = useCallback(async (signal) => {
    setError("");
    try {
      const result = await fetchSessions(signal);
      const next = result || [];
      setSessions(next);
      setSelectedId((current) => {
        if (current && next.some((item) => item.sessionId === current)) return current;
        return (
          next.find((item) => !hasNotRun(item.status) && item.reviewStatus !== "REVIEWED")?.sessionId ??
          next.find((item) => !hasNotRun(item.status))?.sessionId ??
          null
        );
      });
    } catch (err) {
      if (err?.name !== "AbortError" && !handleAuthError(err, navigate)) {
        setError(err.message || "Unable to load training sessions.");
      }
    } finally {
      setLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    const controller = new AbortController();
    loadSessions(controller.signal);
    return () => controller.abort();
  }, [loadSessions]);

  const selectedSession = useMemo(
    () => sessions.find((item) => item.sessionId === selectedId) || null,
    [sessions, selectedId]
  );

  useEffect(() => {
    setForm(emptyForm(selectedSession));
    setNotice("");
  }, [selectedSession]);

  const archivedCount = useMemo(
    () => sessions.filter((s) => !hasNotRun(s.status) && s.reviewStatus === "ARCHIVED_AUTO").length,
    [sessions]
  );

  const recoveryCount = useMemo(
    () => sessions.filter((s) => !hasNotRun(s.status) && isRecoverySession(s)).length,
    [sessions]
  );

  // Lọc ở trình duyệt theo bộ lọc đang chọn, sắp mới nhất trước.
  const visibleSessions = useMemo(
    () =>
      sessions
        .filter((session) => !hasNotRun(session.status))
        .filter((session) => {
          if (filter === "ALL") return true;
          if (filter === "RECOVERY") {
            return isRecoverySession(session);
          }
          if (filter === "NEEDS_REVIEW") {
            return session.reviewStatus === "PENDING" || (!session.evaluation && session.reviewStatus !== "ARCHIVED_AUTO");
          }
          if (filter === "ARCHIVED") {
            return session.reviewStatus === "ARCHIVED_AUTO";
          }
          if (filter === "REVIEWED") {
            return session.reviewStatus === "REVIEWED";
          }
          return session.evaluation === filter;
        })
        .sort((a, b) => new Date(b.sessionDate) - new Date(a.sessionDate)),
    [sessions, filter]
  );

  const updateField = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
    setNotice("");
  };

  const autoEval = useMemo(
    () => computeAutoRating(selectedSession, form),
    [selectedSession, form],
  );
  const lockedFinalStatus = selectedSession?.status === "STOPPED_EARLY" ? "STOPPED_EARLY" : "COMPLETED";

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!selectedSession) return;
    setSaving(true);
    setError("");
    setNotice("");
    const payload = {
      actualDistance: form.actualDistance !== "" ? Number(form.actualDistance) : (selectedSession.actualDistance ?? selectedSession.plannedDistance ?? 0),
      durationMinutes: form.durationMinutes !== "" ? Number(form.durationMinutes) : (selectedSession.durationMinutes ?? selectedSession.plannedDuration ?? 60),
      avgHeartRate: form.avgHeartRate !== "" ? Number(form.avgHeartRate) : null,
      peakHeartRate: form.peakHeartRate !== "" ? Number(form.peakHeartRate) : null,
      recoveryHeartRate: form.recoveryHeartRate === "" ? null : Number(form.recoveryHeartRate),
      peakVelocity: form.peakVelocity === "" ? null : Number(form.peakVelocity),
      evaluation: autoEval.rating,
      remarks: form.remarks.trim() || null,
      status: lockedFinalStatus,
      trialRun: form.trialRun,
      videoUrl: null,
    };
    try {
      const updated = await evaluateSession(selectedSession.sessionId, payload);
      setSessions((current) => current.map((item) => item.sessionId === updated.sessionId ? updated : item));
      setForm(emptyForm(updated));
      setNotice(`Session #${updated.sessionId} official review saved and sent to the Horse Owner.`);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message || "Unable to save this evaluation.");
    } finally {
      setSaving(false);
    }
  };

  // Buổi hồi phục: gửi số liệu cho bác sĩ chấm (POST submit-to-vet); sentToVetList nhớ các buổi đã gửi trong phiên.
  const handleSendToVet = async () => {
    if (!selectedSession) return;
    setSendingToVet(true);
    setError("");
    setNotice("");
    try {
      await submitRecoveryToVet(selectedSession.sessionId);
      setSentToVetList((prev) => [...prev, selectedSession.sessionId]);
      setNotice(`Session #${selectedSession.sessionId} recovery data successfully submitted to Veterinarian for Rating!`);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message || "Failed to send data to Veterinarian.");
    } finally {
      setSendingToVet(false);
    }
  };

  const { requestSignOut, modalElement } = useSignOutModal(navigate);

  return (
    <div className="trainer-page">
      {modalElement}
      <SessionEvaluationSidebar onSignOut={requestSignOut} />
      <main className="trainer-main schedule-main session-review-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Session Review</h1>
            </div>
            <p>Record workout results within the 48-hour review window or finalize archived auto-reviews.</p>
          </div>
          <div className="trainer-header-actions">
            <ProfileCircle className="trainer-profile" />
          </div>
        </header>

        {error && <div className="session-review-message error" role="alert">{error}</div>}
        {notice && <div className="session-review-message success" role="status">{notice}</div>}

        <section className="session-review-layout">
          <div className="session-review-panel session-review-list-panel">
            <div className="session-review-panel-heading">
              <div>
                <h2>Training sessions</h2>
                <p>
                  {visibleSessions.length} session{visibleSessions.length === 1 ? "" : "s"}
                </p>
              </div>
              <select aria-label="Filter sessions" value={filter} onChange={(event) => setFilter(event.target.value)}>
                <option value="NEEDS_REVIEW">Pending review (within 48h)</option>
                <option value="RECOVERY">Recovery training ({recoveryCount})</option>
                <option value="ARCHIVED">Archive ({archivedCount})</option>
                <option value="REVIEWED">Official review completed</option>
                <option value="ALL">All sessions</option>
                <option value="POOR">Poor</option>
                <option value="FAIR">Fair</option>
                <option value="GOOD">Good</option>
                <option value="EXCELLENT">Excellent</option>
              </select>
            </div>
            {loading ? <p className="session-review-empty">Loading sessions…</p> : visibleSessions.length === 0 ? (
              <p className="session-review-empty">No sessions match this filter.</p>
            ) : (
              <div className="session-review-list">
                {visibleSessions.map((session) => {
                  const badge = computeReviewDeadlineBadge(session);
                  return (
                    <button
                      type="button"
                      key={session.sessionId}
                      className={`session-review-item ${session.sessionId === selectedId ? "selected" : ""}`}
                      onClick={() => {
                        setSelectedId(session.sessionId);
                        setError("");
                      }}
                    >
                      <span className="session-review-item-top">
                        <strong>{session.horseName || `Horse #${session.horseId}`}</strong>
                        <span
                          className={`session-review-status status-${String(session.status).toLowerCase()}`}
                        >
                          {statusLabel(session.status)}
                        </span>
                      </span>
                      <span className="session-review-item-meta">
                        Session #{session.sessionId} · {formatDate(session.sessionDate)}
                      </span>
                      <span className="session-review-item-meta">
                        {session.phaseName || `Phase #${session.phaseId}`} · Groom:{" "}
                        {session.assignedToName || `#${session.assignedTo}`}
                      </span>
                      {badge && (
                        <span
                          style={{
                            display: "inline-block",
                            marginTop: "4px",
                            padding: "2px 8px",
                            borderRadius: "999px",
                            fontSize: "11px",
                            fontWeight: 600,
                            backgroundColor: badge.bg,
                            color: badge.color,
                            border: `1px solid ${badge.border}`,
                          }}
                        >
                          {badge.text}
                        </span>
                      )}
                      {isRecoverySession(session) && (
                        <span
                          style={{
                            display: "inline-block",
                            marginTop: "4px",
                            marginLeft: badge ? "6px" : "0",
                            padding: "2px 8px",
                            borderRadius: "999px",
                            fontSize: "11px",
                            fontWeight: 700,
                            backgroundColor: "#ecfdf5",
                            color: "#059669",
                            border: "1px solid #a7f3d0",
                          }}
                        >
                          🩺 Recovery
                        </span>
                      )}
                      <span className="session-review-item-bottom">
                        <span>
                          {session.reviewStatus === "ARCHIVED_AUTO"
                            ? "Auto preliminary report"
                            : session.evaluation
                              ? `Rating: ${statusLabel(session.evaluation)}`
                              : "Awaiting evaluation"}
                        </span>
                        <span>
                          {session.actualDistance == null ? "No result yet" : `${session.actualDistance} m`}
                        </span>
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          <div className="session-review-panel session-review-form-panel">
            {!selectedSession ? (
              <div className="session-review-empty session-review-no-selection">
                <h2>Select a session</h2>
                <p>Choose a started session from the list to record its results.</p>
              </div>
            ) : (
              <>
                <div className="session-review-panel-heading session-review-detail-heading">
                  <div>
                    <h2>{selectedSession.horseName || `Horse #${selectedSession.horseId}`}</h2>
                    <p>
                      Session #{selectedSession.sessionId} · {formatDate(selectedSession.sessionDate)}
                      {selectedSession.endedAt ? ` · Ended ${formatDate(selectedSession.endedAt)}` : ""}
                    </p>
                  </div>
                  <span
                    className={`session-review-status status-${String(selectedSession.status).toLowerCase()}`}
                  >
                    {statusLabel(selectedSession.status)}
                  </span>
                </div>

                {selectedSession.reviewStatus === "ARCHIVED_AUTO" && (
                  <div
                    role="alert"
                    style={{
                      backgroundColor: "#fffbeb",
                      border: "1px solid #f59e0b",
                      color: "#92400e",
                      padding: "12px 16px",
                      borderRadius: "10px",
                      marginBottom: "16px",
                      fontSize: "13px",
                      lineHeight: 1.5,
                    }}
                  >
                    <strong>🗄️ Archived Overdue Session (Automated Preliminary Report Sent)</strong>
                    <p style={{ margin: "4px 0 0" }}>
                      This session exceeded the 2-day (48-hour) review deadline. The system automatically sent a preliminary summary report (based on Groom metrics) with a warning to the Horse Owner and moved this session to the Archive. Submitting your review below will replace the preliminary report and send an official Head Trainer notification to the Horse Owner.
                    </p>
                  </div>
                )}

                {selectedSession.earlyStopReason && (
                  <div
                    style={{
                      backgroundColor: "#eff6ff",
                      border: "1px solid #bfdbfe",
                      color: "#1e40af",
                      padding: "8px 14px",
                      borderRadius: "8px",
                      marginBottom: "12px",
                      fontSize: "13px",
                    }}
                  >
                    <strong>Early stop reason reported by Groom:</strong> {selectedSession.earlyStopReason}
                  </div>
                )}

                {isRecoverySession(selectedSession) && (
                  <div
                    style={{
                      backgroundColor: "#f0fdf4",
                      border: "1px solid #86efac",
                      borderRadius: "10px",
                      padding: "12px 16px",
                      marginBottom: "16px",
                      fontSize: "13px",
                      lineHeight: 1.5,
                    }}
                  >
                    <strong style={{ color: "#166534", display: "flex", alignItems: "center", gap: "6px" }}>
                      🩺 Recovery Training Session (Light Work Protocol)
                    </strong>
                    <p style={{ margin: "4px 0 0", color: "#15803d" }}>
                      This session was scheduled as part of the veterinarian-prescribed rehabilitation plan. Groom vitals are recorded below. After reviewing, click <strong>"Send Data to Veterinarian"</strong> to submit the metrics to the Vet for Recovery Rating.
                    </p>
                  </div>
                )}

                {selectedSession.status === "SCHEDULED" ? (
                  <p className="session-review-empty">This session has not started and cannot be evaluated yet.</p>
                ) : (
                  <form className="session-review-form" onSubmit={handleSubmit}>
                    <div className="session-review-context">
                      <span>
                        <small>Training phase</small>
                        <strong>{selectedSession.phaseName || `Phase #${selectedSession.phaseId}`}</strong>
                      </span>
                      <span>
                        <small>Planned distance / duration</small>
                        <strong>
                          {selectedSession.plannedDistance ?? "—"} m · {selectedSession.plannedDuration ?? 60} mins
                        </strong>
                      </span>
                      <span>
                        <small>Heart-rate ceiling</small>
                        <strong>{selectedSession.heartRateCeiling ?? "—"} bpm</strong>
                      </span>
                    </div>
                    <h3>Actual results</h3>
                    <p style={{ margin: "-4px 0 10px", fontSize: "12px", color: "#64748b" }}>
                      Recorded during training session by Groom (Read-only)
                    </p>
                    <div className="session-review-fields">
                      <label>
                        Actual distance (m)
                        <input
                          name="actualDistance"
                          type="text"
                          readOnly
                          value={form.actualDistance !== "" ? `${Number(form.actualDistance).toLocaleString()} m` : "—"}
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                      <label>
                        Duration (minutes)
                        <input
                          name="durationMinutes"
                          type="text"
                          readOnly
                          value={form.durationMinutes !== "" ? `${form.durationMinutes} min` : "—"}
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                      <label>
                        Average heart rate (bpm)
                        <input
                          name="avgHeartRate"
                          type="text"
                          readOnly
                          value={form.avgHeartRate !== "" ? `${form.avgHeartRate} bpm` : "—"}
                          placeholder="—"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                      <label>
                        Peak heart rate (bpm)
                        <input
                          name="peakHeartRate"
                          type="text"
                          readOnly
                          value={form.peakHeartRate !== "" ? `${form.peakHeartRate} bpm` : "—"}
                          placeholder="—"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                      <label>
                        Recovery heart rate (bpm)
                        <input
                          name="recoveryHeartRate"
                          type="text"
                          readOnly
                          value={form.recoveryHeartRate !== "" ? `${form.recoveryHeartRate} bpm` : "—"}
                          placeholder="—"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                      <label>
                        Peak velocity (km/h)
                        <input
                          name="peakVelocity"
                          type="text"
                          readOnly
                          value={form.peakVelocity !== "" ? `${form.peakVelocity} km/h` : "—"}
                          placeholder="—"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#334155" }}
                        />
                      </label>
                    </div>
                    <h3>Performance evaluation</h3>
                    <div className="session-review-fields">
                      <label>
                        Overall rating (Auto-evaluated)
                        <input
                          type="text"
                          name="evaluation"
                          readOnly
                          value={`${RATING_LABEL[autoEval.rating] || autoEval.rating}${autoEval.scorePct != null ? ` (${autoEval.scorePct}% of plan criteria)` : ""}`}
                          title="Auto-calculated: <80% Poor, >=80% Fair, >=90% Good, 100% Excellent"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#1e1b4b", fontWeight: 700 }}
                        />
                      </label>
                      <label>
                        Final session status (Auto-selected)
                        <input
                          type="text"
                          name="status"
                          readOnly
                          value={lockedFinalStatus === "STOPPED_EARLY" ? "Stopped early" : "Completed"}
                          title="Automatically matches the session's completion status"
                          style={{ backgroundColor: "#f8fafc", cursor: "not-allowed", color: "#1e1b4b", fontWeight: 700 }}
                        />
                      </label>
                    </div>

                    <div style={{ margin: "16px 0", padding: "14px 16px", background: "#fdf8fb", border: "1px solid #f2dee8", borderRadius: "10px" }}>
                      <strong style={{ fontSize: 13, display: "block", marginBottom: 6, color: "#1e1b4b" }}>Trial run</strong>
                      <label className="trial-run-check" style={{ display: "flex", alignItems: "center", gap: 8, cursor: "pointer", fontWeight: 600, fontSize: 13 }}>
                        <input
                          type="checkbox"
                          name="trialRun"
                          checked={form.trialRun}
                          onChange={(e) => setForm((old) => ({ ...old, trialRun: e.target.checked }))}
                        />
                        This session was a trial run
                      </label>
                      <p style={{ margin: "6px 0 0", fontSize: 12, color: "#64748b" }}>
                        When checked, the Horse Owner is notified that this session was a trial run.
                      </p>
                    </div>

                    <div style={{ margin: "16px 0", padding: "14px 16px", background: "#f8fafc", border: "1px solid #e2e8f0", borderRadius: "10px" }}>
                      <strong style={{ fontSize: 13, display: "block", marginBottom: 6, color: "#1e1b4b" }}>
                        Training Video (Recorded by Groom)
                      </strong>
                      {selectedSession.videoUrl ? (
                        <a
                          className="trial-run-link"
                          href={selectedSession.videoUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{ display: "inline-block", fontSize: 13, fontWeight: 600, color: "#d93b87" }}
                        >
                          Watch Groom's Session Video ↗
                        </a>
                      ) : (
                        <p style={{ margin: 0, fontSize: 12.5, color: "#64748b" }}>
                          No video recorded by the Groom for this session yet.
                        </p>
                      )}
                    </div>

                    <label className="session-review-remarks">
                      Trainer remarks
                      <textarea
                        name="remarks"
                        value={form.remarks}
                        onChange={updateField}
                        maxLength={2000}
                        rows={4}
                        placeholder="Record observations, fatigue, form, or recommended follow-up…"
                      />
                    </label>
                    <div className="session-review-form-footer">
                      {isRecoverySession(selectedSession) && (
                        <button
                          type="button"
                          disabled={saving || sendingToVet || sentToVetList.includes(selectedSession.sessionId)}
                          onClick={handleSendToVet}
                          style={{
                            backgroundColor: sentToVetList.includes(selectedSession.sessionId) ? "#64748b" : "#059669",
                            color: "#fff",
                            border: "none",
                            borderRadius: "8px",
                            padding: "9px 16px",
                            fontWeight: 700,
                            fontSize: "13px",
                            cursor: sentToVetList.includes(selectedSession.sessionId) ? "default" : "pointer",
                            marginRight: "auto",
                          }}
                        >
                          {sendingToVet
                            ? "Sending to Vet…"
                            : sentToVetList.includes(selectedSession.sessionId)
                              ? "✓ Data Sent to Vet"
                              : "🩺 Send Data to Veterinarian"}
                        </button>
                      )}
                      <span>
                        {selectedSession.reviewStatus === "ARCHIVED_AUTO"
                          ? "Submitting will finalize this archived session and notify the Horse Owner."
                          : selectedSession.evaluation
                            ? "Updating the existing evaluation will replace its current values."
                            : "Official review must be submitted within 48 hours of session completion."}
                      </span>
                      <button type="submit" disabled={saving}>
                        {saving
                          ? "Saving…"
                          : selectedSession.reviewStatus === "ARCHIVED_AUTO"
                            ? "Submit official review & notify owner"
                            : selectedSession.evaluation
                              ? "Update evaluation"
                              : "Save evaluation"}
                      </button>
                    </div>
                  </form>
                )}
              </>
            )}
          </div>
        </section>
      </main>
    </div>
  );
}
