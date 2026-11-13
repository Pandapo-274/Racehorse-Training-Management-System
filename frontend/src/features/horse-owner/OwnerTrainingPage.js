// src/features/horse-owner/OwnerTrainingPage.js
// Chủ ngựa xem lịch trình tập luyện, video chạy thử và nhật ký nhận xét của
// Head Trainer (đúng một mục trong đề bài của Horse Owner).
//
// Lập giáo án và xếp lịch là việc của Head Trainer; chủ ngựa chỉ có một việc
// ở đây: duyệt hoặc từ chối giáo án trước khi nó được kích hoạt (V34).
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  formatDate, formatDateTime, isPedigreeOnly, isWithdrawn, listHorses,
} from "../horse/horseApi";
import { toEvaluation } from "./horseOwnerApi";
import {
  formatMetres, getPlan, isCurrentPhase, listMyPlans, listMySessions, pickPlan, reviewPlan,
  sendOwnerFeedback, toPlanStatus, toSessionStatus, todayIso,
} from "./ownerTrainingApi";
import "./owner-training.css";

/** Duyệt / từ chối giáo án đang chờ. Từ chối bắt buộc ghi lý do cho Head Trainer. */
function ApprovalBox({ plan, onReviewed }) {
  const navigate = useNavigate();
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async (approve) => {
    if (!approve && !note.trim()) {
      setError("Tell the head trainer why you are rejecting this plan.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await reviewPlan(plan.planId, { approve, note: note.trim() });
      onReviewed();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(Object.values(err.fieldErrors || {})[0] || err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="ot-approval">
      <p className="ot-note">
        The head trainer is asking for your approval. Training starts only after you approve this plan.
      </p>
      <div className="hz-field">
        <label htmlFor={`plan-note-${plan.planId}`}>Note for the head trainer (required if you reject)</label>
        <textarea id={`plan-note-${plan.planId}`} rows={2} maxLength={500} value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="e.g. I want to aim for sprint races, please shorten the distances" />
      </div>
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      <div className="ot-approval__actions">
        <button type="button" className="hz-btn" disabled={busy} onClick={() => submit(true)}>
          {busy ? "Saving…" : "Approve plan"}
        </button>
        <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={() => submit(false)}>
          Reject
        </button>
      </div>
    </div>
  );
}

function OwnerFeedbackBox({ plan, onFeedbackSent }) {
  const navigate = useNavigate();
  const [feedback, setFeedback] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const handleSend = async () => {
    if (!feedback.trim()) {
      setError("Please enter your feedback before sending.");
      return;
    }
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      await sendOwnerFeedback(plan.planId, feedback.trim());
      setSuccess("Your feedback has been sent to the Head Trainer!");
      setFeedback("");
      if (onFeedbackSent) onFeedbackSent();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(Object.values(err.fieldErrors || {})[0] || err.message || "Failed to send feedback.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="ot-feedback-box" style={{ marginTop: 16, borderTop: "1px solid #f1e5ed", paddingTop: 14 }}>
      <strong style={{ fontSize: 13, color: "#1e1b4b", display: "block", marginBottom: 6 }}>
        💬 Feedback / Adjustment Request for Head Trainer (Optional)
      </strong>
      {plan.ownerNote && (
        <p style={{ fontSize: 12.5, color: "#475569", background: "#f8fafc", padding: "8px 12px", borderRadius: 8, border: "1px solid #e2e8f0", marginBottom: 10 }}>
          Latest note: <em>"{plan.ownerNote}"</em>
        </p>
      )}
      <div className="hz-field">
        <textarea
          rows={2}
          maxLength={500}
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          placeholder="Send feedback or requests to the head trainer (e.g. Training load seems appropriate, or please reduce distance next week)..."
        />
      </div>
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      {success && <p style={{ color: "#166534", fontSize: 12.5, fontWeight: 600, margin: "4px 0" }}>✓ {success}</p>}
      <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 8 }}>
        <button
          type="button"
          className="hz-btn hz-btn--ghost"
          style={{ fontSize: 12.5, padding: "6px 14px" }}
          disabled={busy || !feedback.trim()}
          onClick={handleSend}
        >
          {busy ? "Sending…" : "Send Feedback"}
        </button>
      </div>
    </div>
  );
}

function PlanCard({ plan, detail, today, onReviewed }) {
  if (!plan) {
    return (
      <Card title="Training plan">
        <p className="hz-empty">The head trainer has not made a training plan for this horse yet.</p>
      </Card>
    );
  }
  const st = toPlanStatus(plan.status);
  return (
    <Card
      title={plan.planName}
      hint={`${formatDate(plan.startDate)} – ${formatDate(plan.endDate)}`}
      actions={<StatusChip tone={st.tone} label={st.label} />}
    >
      {!detail ? (
        <p className="hz-empty">Loading phases…</p>
      ) : (
        <ol className="ot-phases">
          {detail.phases.map((ph) => {
            const current = plan.status === "ACTIVE" && isCurrentPhase(ph, today);
            return (
              <li key={ph.phaseId} className={`ot-phase${current ? " is-current" : ""}`}>
                <div className="ot-phase__head">
                  <strong>Phase {ph.phaseNo} · {ph.phaseName}</strong>
                  {current && <span className="ot-now">Now</span>}
                </div>
                <span className="ot-phase__dates">{formatDate(ph.startDate)} – {formatDate(ph.endDate)}</span>
                <span className="ot-phase__meta">
                  {[formatMetres(ph.distance),
                    ph.surface && ph.surface.charAt(0) + ph.surface.slice(1).toLowerCase(),
                    ph.workload,
                    ph.sessionsPerWeek ? `${ph.sessionsPerWeek} sessions / week` : null,
                    ph.targetVelocity ? `target ${ph.targetVelocity} m/s` : null]
                    .filter((x) => x && x !== "—").join(" · ")}
                </span>
              </li>
            );
          })}
        </ol>
      )}
      {plan.status === "PENDING_APPROVAL" ? (
        <ApprovalBox plan={plan} onReviewed={onReviewed} />
      ) : (
        <OwnerFeedbackBox plan={plan} onFeedbackSent={onReviewed} />
      )}
    </Card>
  );
}

function UpcomingCard({ sessions }) {
  return (
    <Card title="Coming up" hint={sessions.length ? `${sessions.length} scheduled` : "Nothing scheduled"}>
      {sessions.length === 0 ? (
        <p className="hz-empty">No upcoming training sessions.</p>
      ) : (
        <ul className="ot-upcoming">
          {sessions.slice(0, 6).map((s) => (
            <li key={s.sessionId}>
              <strong>{formatDateTime(s.sessionDate, { local: true })}</strong>
              <span>{s.phaseName} · {formatMetres(s.plannedDistance)} ({s.plannedDuration || 60} min){s.trialRun ? " · Trial run" : ""}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function LogEntry({ s }) {
  const st = toSessionStatus(s.status);
  const evaluated = s.evaluation != null;
  const isAutoPreliminary = s.reviewStatus === "ARCHIVED_AUTO";
  return (
    <li className="ot-entry">
      <div className="ot-entry__head">
        <div>
          <strong>{formatDateTime(s.sessionDate, { local: true })}</strong>
          <span className="ot-entry__phase">{s.phaseName}</span>
        </div>
        <div className="ot-entry__chips">
          {s.trialRun && <span className="ot-badge">Trial run</span>}
          {isAutoPreliminary && (
            <span
              className="ot-badge"
              style={{ backgroundColor: "#fef3c7", color: "#92400e", border: "1px solid #f59e0b" }}
            >
              ⚠️ Automated Preliminary Report
            </span>
          )}
          <StatusChip tone={st.tone} label={st.label} />
        </div>
      </div>

      {s.status !== "CANCELLED" && (
        <dl className="ot-metrics">
          <div><dt>Distance</dt><dd>{formatMetres(s.actualDistance)} <small>of {formatMetres(s.plannedDistance)}</small></dd></div>
          <div><dt>Duration</dt><dd>{s.durationMinutes != null ? `${s.durationMinutes} min` : (s.plannedDuration ? `${s.plannedDuration} min (planned)` : "—")}</dd></div>
          <div><dt>Heart rate</dt><dd>{s.avgHeartRate != null ? `${s.avgHeartRate} avg / ${s.peakHeartRate} peak` : (s.peakHeartRate != null ? `${s.peakHeartRate} peak` : "—")}</dd></div>
          <div><dt>Top speed</dt><dd>{s.peakVelocity != null ? `${s.peakVelocity} m/s` : "—"}</dd></div>
        </dl>
      )}

      {isAutoPreliminary && (
        <p
          role="alert"
          style={{
            margin: "8px 0",
            padding: "8px 12px",
            borderRadius: "8px",
            backgroundColor: "#fffbeb",
            border: "1px solid #fcd34d",
            color: "#92400e",
            fontSize: "13px",
          }}
        >
          ⚠️ <strong>Notice:</strong> This is an automated preliminary summary generated from Groom metrics because the 48-hour review window expired. It is NOT an official evaluation from the Head Trainer. The session is archived awaiting the Head Trainer&apos;s official review.
        </p>
      )}

      {evaluated ? (
        <blockquote className="ot-remark">
          <span className={`ot-eval ot-eval--${s.evaluation.toLowerCase()}`}>{toEvaluation(s.evaluation)}</span>
          {s.remarks || "Rated without a written remark."}
        </blockquote>
      ) : (
        s.status !== "CANCELLED" && s.status !== "RUNNING" && (
          <p className="ot-pending">Waiting for the head trainer's evaluation (within 48h of completion).</p>
        )
      )}

      {s.videoUrl && (
        <a className="ot-video" href={s.videoUrl} target="_blank" rel="noopener noreferrer">
          ▶ Watch the trial run video
        </a>
      )}
    </li>
  );
}

export default function OwnerTrainingPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const querySessionId = searchParams.get("sessionId");
  const queryPlanId = searchParams.get("planId");
  const queryHorseId = searchParams.get("horseId");

  const [horses, setHorses] = useState(null);
  const [plans, setPlans] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [horseId, setHorseId] = useState(null);
  const [planDetail, setPlanDetail] = useState(null);
  const [trialOnly, setTrialOnly] = useState(false);
  const today = todayIso();

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([listHorses(ctrl.signal), listMyPlans(ctrl.signal), listMySessions(ctrl.signal)])
      .then(([h, p, s]) => {
        // Hồ sơ PEDIGREE không tập; ngựa đã rút vẫn hiện để xem lại lịch sử.
        const trainable = (h || []).filter((x) => !isPedigreeOnly(x));
        setHorses(trainable);
        setPlans(p || []);
        setSessions(s || []);

        let targetHorseId = null;
        if (querySessionId && s) {
          const matchedSession = s.find((item) => String(item.sessionId) === String(querySessionId));
          if (matchedSession) {
            targetHorseId = matchedSession.horseId;
          }
        }
        if (!targetHorseId && queryPlanId && p) {
          const matchedPlan = p.find((item) => String(item.planId) === String(queryPlanId));
          if (matchedPlan) {
            targetHorseId = matchedPlan.horseId;
          }
        }
        if (!targetHorseId && queryHorseId) {
          targetHorseId = Number(queryHorseId);
        }

        setHorseId((cur) => {
          if (targetHorseId && trainable.some((x) => x.horseId === targetHorseId)) {
            return targetHorseId;
          }
          return trainable.some((x) => x.horseId === cur) ? cur : trainable[0]?.horseId ?? null;
        });

        if (querySessionId) {
          setTimeout(() => {
            const logEl = document.getElementById("training-log-section");
            if (logEl) {
              logEl.scrollIntoView({ behavior: "smooth" });
            }
          }, 350);
        }
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey, querySessionId, queryPlanId, queryHorseId]);

  const plan = useMemo(() => pickPlan(plans, horseId), [plans, horseId]);

  useEffect(() => {
    setPlanDetail(null);
    if (!plan) return undefined;
    const ctrl = new AbortController();
    getPlan(plan.planId, ctrl.signal)
      .then(setPlanDetail)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [plan, navigate]);

  const mine = useMemo(() => sessions.filter((s) => s.horseId === horseId), [sessions, horseId]);
  const upcoming = useMemo(
    () => mine.filter((s) => s.status === "SCHEDULED" && String(s.sessionDate).slice(0, 10) >= today)
      .sort((a, b) => String(a.sessionDate).localeCompare(String(b.sessionDate))),
    [mine, today]);
  const log = useMemo(
    () => mine.filter((s) => s.status !== "SCHEDULED" && (!trialOnly || s.trialRun))
      .sort((a, b) => String(b.sessionDate).localeCompare(String(a.sessionDate))),
    [mine, trialOnly]);
  const horse = horses?.find((h) => h.horseId === horseId);
  const waiting = useMemo(() => plans.filter((p) => p.status === "PENDING_APPROVAL"), [plans]);

  return (
    <HorseShell
      title="Training"
      subtitle="Your horses' training plans, schedule, the head trainer's remarks and trial run videos"
    >
      {error && (
        <StateBlock kind="error" title="Could not load training" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && horses === null && <StateBlock title="Loading training…" />}
      {horses && horses.length === 0 && (
        <StateBlock title="No horses in training">
          Horses you register with the club appear here once the head trainer plans their training.
        </StateBlock>
      )}

      {horses && horses.length > 0 && (
        <>
          {horses.length > 1 && (
            <div className="ot-tabs" role="tablist" aria-label="Choose a horse">
              {horses.map((h) => (
                <button
                  key={h.horseId}
                  type="button"
                  role="tab"
                  aria-selected={h.horseId === horseId}
                  className={`ot-tab${h.horseId === horseId ? " active" : ""}`}
                  onClick={() => setHorseId(h.horseId)}
                >
                  {h.horseName}
                </button>
              ))}
            </div>
          )}

          {waiting.length > 0 && (
            <p className="ot-note ot-note--attention" role="status">
              {waiting.length === 1 ? "1 training plan is" : `${waiting.length} training plans are`} waiting
              for your approval: {waiting.map((p) => p.horseName).join(", ")}.
            </p>
          )}

          {horse && isWithdrawn(horse) && (
            <p className="ot-note">{horse.horseName} has been withdrawn from training. Its past sessions are kept below.</p>
          )}

          <div className="ot-top">
            <PlanCard plan={plan} detail={planDetail} today={today}
              onReviewed={() => setReloadKey((k) => k + 1)} />
            <UpcomingCard sessions={upcoming} />
          </div>

          <div id="training-log-section">
            <Card
              title="Training log"
              hint={`${log.length} ${log.length === 1 ? "session" : "sessions"} · newest first`}
              actions={
                <label className="ot-filter">
                  <input type="checkbox" checked={trialOnly} onChange={(e) => setTrialOnly(e.target.checked)} />
                  Trial runs only
                </label>
              }
            >
              {log.length === 0 ? (
                <p className="hz-empty">
                  {trialOnly ? "No trial runs yet." : "No sessions have taken place yet."}
                </p>
              ) : (
                <ul className="ot-log">{log.map((s) => <LogEntry key={s.sessionId} s={s} />)}</ul>
              )}
            </Card>
          </div>
        </>
      )}
    </HorseShell>
  );
}