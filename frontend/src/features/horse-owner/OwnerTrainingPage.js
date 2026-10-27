// src/features/horse-owner/OwnerTrainingPage.js
// Chủ ngựa xem lịch trình tập luyện, video chạy thử và nhật ký nhận xét của
// Head Trainer (đúng một mục trong đề bài của Horse Owner).
//
// Chỉ xem: mọi thay đổi về giáo án và lịch tập là việc của Head Trainer.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  formatDate, formatDateTime, isPedigreeOnly, isWithdrawn, listHorses,
} from "../horse/horseApi";
import { toEvaluation } from "./horseOwnerApi";
import {
  formatMetres, getPlan, isCurrentPhase, listMyPlans, listMySessions, pickPlan,
  toPlanStatus, toSessionStatus, todayIso,
} from "./ownerTrainingApi";
import "./owner-training.css";

function PlanCard({ plan, detail, today }) {
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
                    ph.sessionsPerWeek ? `${ph.sessionsPerWeek} sessions / week` : null]
                    .filter((x) => x && x !== "—").join(" · ")}
                </span>
              </li>
            );
          })}
        </ol>
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
              <strong>{formatDateTime(s.sessionDate)}</strong>
              <span>{s.phaseName} · {formatMetres(s.plannedDistance)}{s.trialRun ? " · Trial run" : ""}</span>
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
  return (
    <li className="ot-entry">
      <div className="ot-entry__head">
        <div>
          <strong>{formatDateTime(s.sessionDate)}</strong>
          <span className="ot-entry__phase">{s.phaseName}</span>
        </div>
        <div className="ot-entry__chips">
          {s.trialRun && <span className="ot-badge">Trial run</span>}
          <StatusChip tone={st.tone} label={st.label} />
        </div>
      </div>

      {s.status !== "CANCELLED" && (
        <dl className="ot-metrics">
          <div><dt>Distance</dt><dd>{formatMetres(s.actualDistance)} <small>of {formatMetres(s.plannedDistance)}</small></dd></div>
          <div><dt>Duration</dt><dd>{s.durationMinutes != null ? `${s.durationMinutes} min` : "—"}</dd></div>
          <div><dt>Heart rate</dt><dd>{s.avgHeartRate != null ? `${s.avgHeartRate} avg / ${s.peakHeartRate} peak` : (s.peakHeartRate != null ? `${s.peakHeartRate} peak` : "—")}</dd></div>
          <div><dt>Top speed</dt><dd>{s.peakVelocity != null ? `${s.peakVelocity} m/s` : "—"}</dd></div>
        </dl>
      )}

      {evaluated ? (
        <blockquote className="ot-remark">
          <span className={`ot-eval ot-eval--${s.evaluation.toLowerCase()}`}>{toEvaluation(s.evaluation)}</span>
          {s.remarks || "Rated without a written remark."}
        </blockquote>
      ) : (
        s.status !== "CANCELLED" && s.status !== "RUNNING" && (
          <p className="ot-pending">Waiting for the head trainer's evaluation.</p>
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
        setHorseId((cur) => (trainable.some((x) => x.horseId === cur) ? cur : trainable[0]?.horseId ?? null));
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

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

          {horse && isWithdrawn(horse) && (
            <p className="ot-note">{horse.horseName} has been withdrawn from training. Its past sessions are kept below.</p>
          )}

          <div className="ot-top">
            <PlanCard plan={plan} detail={planDetail} today={today} />
            <UpcomingCard sessions={upcoming} />
          </div>

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
        </>
      )}
    </HorseShell>
  );
}
