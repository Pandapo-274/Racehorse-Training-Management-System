// src/features/vet/VetRecoveryPage.js
// Screen 19 - Recovery (UC18).
//
// Structured recovery workflow:
// Stage 1 (Treatment) -> Stage 2 (Light work) -> Stage 3 (Rating) -> Stage 4 (Healthy)
//
// Key clinical constraints:
// 1. Light work enforces lock level HEAVY_WORK_ONLY (sessions <= 800m, low intensity).
// 2. Light work advancement requires clinical evaluation and exercise protocol logging.
// 3. Rating requires fitness assessment (1-100) and locomotion evaluation.
// 4. Bidirectional state transitions: Relapse reverts horse to Treatment and escalates lock to FULL.
// 5. Training lock release is blocked if active prescriptions or open incident reports exist.

import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { formatDateTime } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import VetHorsePicker from "./VetHorsePicker";
import {
  getHorse,
  listInjuries,
  listMedicalRecords,
  listLocksForHorse,
  listOpenReportsForHorse,
  toUrgency,
  formatReportTime,
  confirmRecovery,
  statusLabel,
  nowForApi,
  formatShortDate,
  daysSince,
  describeError,
  todayInputValue,
  listTreatmentsForHorse,
  normalizeDate,
  updateInjuryStatus,
  updateLockLevel,
  createMedicalRecord,
} from "./vetApi";

const STAGES = ["Examine", "Treatment", "Light work", "Rating", "Healthy"];

const REMINDERS = [
  ["VACCINATION", "Vaccination"],
  ["DEWORMING", "Deworming"],
  ["FARRIER", "Farrier"],
];

export default function VetRecoveryPage() {
  const { horseId } = useParams();
  if (!horseId) return <VetHorsePicker mode="recovery" />;
  return <RecoveryView key={horseId} horseId={Number(horseId)} />;
}

/** Determine recovery stage based on actual entity states */
function stageOf(horse, injuries, activeTreatments = []) {
  if (horse.status === "ELIGIBLE") return 4;
  const open = injuries.filter((i) => i.status !== "RECOVERED");
  if (injuries.length > 0 && open.length === 0 && activeTreatments.length === 0) return 3;
  if (activeTreatments.length === 0 && open.length > 0 && open.every((i) => i.status === "PARTIAL")) return 2;
  if (open.length > 0 || activeTreatments.length > 0) return 1;
  return 0;
}

function captionOf(stage, open, activeTreatments = [], activeLock = null, lightWorkInfo = null) {
  switch (stage) {
    case 4:
      return "Cleared. The horse is healthy and the training lock is released.";
    case 3:
      return "Fitness assessment completed. All injuries have healed. Confirm recovery to release the training lock.";
    case 2:
      return `Light work rehabilitation in progress (${lightWorkInfo || "Stage 2"}). Restricted to light sessions (max 800m, trotting/walking, HR <= 130 bpm). Lock level: ${activeLock?.lockLevel || "HEAVY_WORK_ONLY"}.`;
    case 1:
      if (activeTreatments.length > 0) {
        const medNames = activeTreatments.map((t) => t.medicineName || t.instruction).filter(Boolean).join(", ");
        return `Under active treatment: Receiving prescribed medication (${medNames || "medication"}). Training lock: FULL.`;
      }
      return `Under treatment: ${open.map((i) => `${i.bodyLocation} (${i.severity.replace("_", " ").toLowerCase()})`).join(", ")}. Training lock: FULL.`;
    default:
      return "No injury has been marked for this horse yet.";
  }
}

function RecoveryView({ horseId }) {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  // Dialog states
  const [dialog, setDialog] = useState(false);
  const [lightWorkDialog, setLightWorkDialog] = useState(false);
  const [ratingDialog, setRatingDialog] = useState(false);
  const [relapseDialog, setRelapseDialog] = useState(false);
  const [result, setResult] = useState(null);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([
      getHorse(horseId, ctrl.signal),
      listInjuries(horseId, ctrl.signal),
      listMedicalRecords(horseId, ctrl.signal),
      listLocksForHorse(horseId, ctrl.signal),
      listOpenReportsForHorse(horseId, ctrl.signal),
      listTreatmentsForHorse(horseId, ctrl.signal),
    ])
      .then(([horse, injuries, records, locks, reports, treatments]) =>
        setData({ horse, injuries, records, locks, reports: reports || [], treatments: treatments || [] }))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [horseId, navigate, reload]);

  const view = useMemo(() => {
    if (!data) return null;
    const { horse, injuries, records, locks, reports, treatments } = data;
    const today = todayInputValue();
    const activeTreatments = (treatments || []).filter((t) => {
      const endIso = normalizeDate(t.endDate);
      const isEnded = Boolean(
        t.isActive === false ||
        t.active === false ||
        (endIso && endIso < today)
      );
      return !isEnded;
    });

    const exams = records.filter((r) => r.recordType === "EXAM");
    const base = exams.find((r) => r.injuryConfirmed) || null;
    const followUps = base ? exams.filter((r) => new Date(r.examDate) > new Date(base.examDate)) : [];
    const activeLock = locks.find((l) => l.status === "ACTIVE") || null;
    const openInjuries = injuries.filter((i) => i.status !== "RECOVERED");

    const startIso = base?.examDate || activeLock?.issuedAt || null;
    const day = startIso ? daysSince(startIso) : null;
    const rest = base?.expectedRestDays || null;

    const currentStage = stageOf(horse, injuries, activeTreatments);

    let lightWorkInfo = null;
    if (currentStage === 2) {
      const lwExam = exams.find((r) => (r.diagnosis && r.diagnosis.toLowerCase().includes("light work")) || (r.notes && r.notes.toLowerCase().includes("light work")));
      const lwStart = lwExam ? lwExam.examDate : base?.examDate || activeLock?.issuedAt;
      const lwDays = lwStart ? daysSince(lwStart) + 1 : 1;
      const expectedDays = 5;
      lightWorkInfo = `Light work · Day ${lwDays}/${expectedDays} (Lock: ${activeLock?.lockLevel || "HEAVY_WORK_ONLY"})`;
    }

    const reminders = REMINDERS.map(([type, label]) => {
      const r = records.find((x) => x.recordType === type && x.nextDueDate);
      return r ? { label, date: r.nextDueDate } : null;
    }).filter(Boolean);

    let subtitle = "";
    if (currentStage === 2 && lightWorkInfo) {
      subtitle = lightWorkInfo;
    } else if (currentStage === 3) {
      subtitle = "Rating · Clinical Assessment Complete";
    } else if (currentStage === 4) {
      subtitle = `${horse.registrationCode} · Healthy (Race-fit)`;
    } else if (day != null) {
      subtitle = `Day ${day + 1}${rest ? `/${rest}` : ""}${followUps.length ? ` · Follow-up ${followUps.length}` : ""}`;
    } else {
      subtitle = `${horse.registrationCode} · ${statusLabel(horse.status)}`;
    }

    return {
      horse,
      exams: [...exams].reverse(),
      activeLock,
      openInjuries,
      allInjuries: injuries,
      openReports: reports,
      activeTreatments,
      stage: currentStage,
      lightWorkInfo,
      reminders,
      subtitle,
    };
  }, [data]);

  const handleActionDone = useCallback((res) => {
    setDialog(false);
    setLightWorkDialog(false);
    setRatingDialog(false);
    setRelapseDialog(false);
    setResult(res);
    setReload((n) => n + 1);
  }, []);

  if (error || !view) {
    return (
      <VetShell active="recovery" title="Recovery" subtitle="Loading…">
        {error ? (
          <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)} />
        ) : (
          <VetState title="Loading recovery…" />
        )}
      </VetShell>
    );
  }

  const { horse, exams, activeLock, openInjuries, allInjuries, openReports, activeTreatments, stage, reminders, lightWorkInfo } = view;
  const hasActiveTreatment = (activeTreatments || []).length > 0;
  const canClear = (horse.status !== "ELIGIBLE" || !!activeLock) && !hasActiveTreatment;
  const today = todayInputValue();

  return (
    <VetShell active="recovery" title={`Recovery · ${horse.horseName}`} subtitle={view.subtitle}>
      {result && (
        <div className="vx-banner vx-banner--ok" role="status">
          {result.message ? (
            <p style={{ margin: 0, fontWeight: 600 }}>{result.message}</p>
          ) : (
            <>
              <strong>{horse.horseName} is now {statusLabel(horse.status)}.</strong>
              <p>
                Closed {result.injuriesClosed?.length || 0} injur{(result.injuriesClosed?.length || 0) === 1 ? "y" : "ies"},
                answered {(result.reportsClosed || []).length} incident report
                {(result.reportsClosed || []).length === 1 ? "" : "s"} and
                released {result.locksReleased?.length || 0} training lock
                {(result.locksReleased?.length || 0) === 1 ? "" : "s"}.
              </p>
            </>
          )}
        </div>
      )}

      {openReports.length > 0 && horse.status === "ELIGIBLE" && (
        <div className="vx-banner vx-banner--warn" role="status">
          <strong>
            {horse.horseName} is healthy but still has {openReports.length} incident report
            {openReports.length === 1 ? "" : "s"} waiting, so training stays paused.
          </strong>
          <span>
            Examine each one to close it. If nothing is wrong, set "Injury confirmed" to No.
          </span>
          <ul>
            {openReports.map((r) => (
              <li key={r.reportId}>
                #{r.reportId} {r.symptoms} ({toUrgency(r.urgency).label}, {formatReportTime(r.reportedAt)}){" "}
                <Link className="vx-link" to={`/veterinarian/examination/${horse.horseId}?reportId=${r.reportId}`}>
                  Examine
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      <VetCard
        title="Recovery progress"
        tone="pink"
        actions={
          <div className="vc-actions" style={{ display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap" }}>
            {stage === 1 && !hasActiveTreatment && openInjuries.length > 0 && (
              <button
                type="button"
                className="examine-button"
                onClick={() => setLightWorkDialog(true)}
              >
                Advance to Light work →
              </button>
            )}
            {stage === 2 && openInjuries.length > 0 && (
              <>
                <button
                  type="button"
                  className="examine-button examine-button--warning"
                  onClick={() => setRelapseDialog(true)}
                >
                  Relapse / Return to Treatment ↺
                </button>
                <button
                  type="button"
                  className="examine-button"
                  onClick={() => setRatingDialog(true)}
                >
                  Progress to Rating →
                </button>
              </>
            )}
            {stage === 3 && (
              <button
                type="button"
                className="examine-button examine-button--warning"
                onClick={() => setRelapseDialog(true)}
              >
                Relapse / Return to Treatment ↺
              </button>
            )}
          </div>
        }
      >
        <div className="vx-track-wrap">
          <div className="vx-track" role="img" aria-label={`Stage: ${STAGES[stage]}`}>
            <div className="vx-track-line">
              <div className="vx-track-fill" style={{ width: `${(stage / 4) * 100}%` }} />
            </div>
            {STAGES.map((label, i) => (
              <div
                key={label}
                className={`vx-node${i < stage ? " done" : ""}${i === stage ? " current" : ""}${
                  i === 4 ? " last" : ""
                }`}
                style={{ left: `${(i / 4) * 100}%` }}
              >
                <i />
                <span>{label}</span>
              </div>
            ))}
          </div>
        </div>
        <p className="vx-caption">{captionOf(stage, openInjuries, activeTreatments, activeLock, lightWorkInfo)}</p>
      </VetCard>

      <div className="vx-grid vx-grid--recovery">
        <VetCard title="Follow-up history" tone="gold">
          {exams.length === 0 ? (
            <div className="vet-inline-state">No examinations recorded yet.</div>
          ) : (
            <ul className="vx-history">
              {exams.map((r) => {
                const cleared = r.healthStatus === "ELIGIBLE";
                const isRating = r.diagnosis && r.diagnosis.includes("Fitness Rating");
                const isLightWork = r.diagnosis && r.diagnosis.includes("Light work");
                const isRelapse = r.diagnosis && r.diagnosis.includes("Relapse");
                let badgeClass = cleared ? "vx-pill--ok" : "vx-pill--wait";
                let badgeText = cleared ? "Recovered" : "Follow-up";
                if (isRating) {
                  badgeText = "Rating";
                  badgeClass = "vx-pill--ok";
                } else if (isLightWork) {
                  badgeText = "Light work";
                  badgeClass = "vx-pill--ok";
                } else if (isRelapse) {
                  badgeText = "Relapse";
                  badgeClass = "vx-pill--wait";
                }
                return (
                  <li key={r.recordId} className={cleared ? "done" : ""}>
                    <b>{formatShortDate(r.examDate)}</b>
                    <span>{r.diagnosis || r.notes || "—"}</span>
                    <em className={`vx-pill ${badgeClass}`}>
                      {badgeText}
                    </em>
                  </li>
                );
              })}
            </ul>
          )}
        </VetCard>

        <div className="vx-stack">
          <VetCard title="Release training lock" tone="green">
            <p className="vx-lead">
              Releasing the lock sets the horse back to Healthy and lets the head trainer resume the full training plan.
            </p>
            {activeLock && (
              <p className="vx-meta">
                Locked by {activeLock.issuedByName} on {formatDateTime(activeLock.issuedAt)}. Current lock level: <strong>{activeLock.lockLevel}</strong>.
              </p>
            )}
            {hasActiveTreatment && (
              <div className="vx-banner vx-banner--warn" style={{ marginTop: "10px", marginBottom: "12px", textAlign: "left" }}>
                <strong>Treatment phase in progress</strong>
                <p style={{ margin: "4px 0 0", fontSize: "13px" }}>
                  {horse.horseName} is currently receiving prescribed medication:{" "}
                  <strong>{activeTreatments.map((t) => t.medicineName || t.instruction).filter(Boolean).join(", ")}</strong>.
                </p>
                <p style={{ margin: "4px 0 0", fontSize: "12px", opacity: 0.9 }}>
                  The training lock cannot be released until the treatment phase is complete (or end the prescription in{" "}
                  <Link className="vx-link" to="/veterinarian/prescriptions">Prescriptions</Link>).
                </p>
              </div>
            )}
            {stage === 2 && (
              <div className="vx-banner vx-banner--info" style={{ marginTop: "10px", marginBottom: "12px", textAlign: "left", background: "#f0f9ff", borderLeft: "4px solid #0284c7" }}>
                <strong style={{ color: "#0369a1" }}>Light work protocol active</strong>
                <p style={{ margin: "4px 0 0", fontSize: "12px", color: "#0c4a6e" }}>
                  The horse is undergoing light exercise rehabilitation (HEAVY_WORK_ONLY). When the horse is ready, progress to Rating to evaluate fitness, or release lock when fully recovered.
                </p>
              </div>
            )}

            <button
              type="button"
              className="vx-btn vx-btn--green vx-btn--wide"
              disabled={!canClear}
              onClick={() => {
                setResult(null);
                setDialog(true);
              }}
            >
              {hasActiveTreatment
                ? "Under treatment (Lock release blocked)"
                : !canClear
                ? "Already healthy"
                : stage === 3
                ? "Release training lock"
                : activeLock
                ? "Release training lock"
                : "Confirm recovery"}
            </button>
          </VetCard>

          <VetCard title="Routine reminders" tone="red">
            {reminders.length === 0 ? (
              <p className="vx-meta">No routine care is scheduled.</p>
            ) : (
              <p className="vx-reminders">
                {reminders.map((r) => (
                  <span key={r.label} className={r.date < today ? "overdue" : ""}>
                    {r.label} {formatShortDate(`${r.date}T00:00:00`)}
                    {r.date < today ? " (overdue)" : ""}
                  </span>
                ))}
              </p>
            )}
          </VetCard>
        </div>
      </div>

      {lightWorkDialog && (
        <LightWorkDialog
          horse={horse}
          openInjuries={openInjuries}
          onClose={() => setLightWorkDialog(false)}
          onDone={handleActionDone}
        />
      )}

      {ratingDialog && (
        <RatingDialog
          horse={horse}
          openInjuries={openInjuries}
          onClose={() => setRatingDialog(false)}
          onDone={handleActionDone}
        />
      )}

      {relapseDialog && (
        <RelapseDialog
          horse={horse}
          injuries={allInjuries}
          onClose={() => setRelapseDialog(false)}
          onDone={handleActionDone}
        />
      )}

      {dialog && (
        <ConfirmDialog
          horse={horse}
          openInjuries={openInjuries}
          openReports={openReports}
          hasActiveTreatment={hasActiveTreatment}
          hasLock={!!activeLock}
          onClose={() => setDialog(false)}
          onDone={handleActionDone}
        />
      )}
    </VetShell>
  );
}

/* ---------- Modal 1: Light Work Evaluation ---------- */

function LightWorkDialog({ horse, openInjuries, onClose, onDone }) {
  const [findings, setFindings] = useState("Swelling resolved, pain on palpation significantly reduced, sound at walk and easy trot.");
  const [protocol, setProtocol] = useState("Protocol B: Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm");
  const [durationDays, setDurationDays] = useState(5);
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const submit = async (e) => {
    e.preventDefault();
    if (!findings.trim()) {
      setError({ message: "Clinical examination findings are required." });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // 1. Advance open injuries to PARTIAL
      await Promise.all(
        openInjuries.map((i) => updateInjuryStatus(i.injuryId, "PARTIAL"))
      );

      // 2. Switch training lock to HEAVY_WORK_ONLY
      await updateLockLevel(horse.horseId, "HEAVY_WORK_ONLY");

      // 3. Log follow-up medical record
      await createMedicalRecord({
        horseId: horse.horseId,
        examDate: nowForApi(),
        recordType: "EXAM",
        diagnosis: `Light work clearance: ${findings.trim()}`,
        injuryConfirmed: false,
        notes: `Light work protocol: ${protocol}. Duration: ${durationDays} days. Training lock set to HEAVY_WORK_ONLY (max 800m). ${notes.trim()}`.trim(),
      });

      onDone({
        message: `${horse.horseName} has advanced to Light work. Training lock updated to HEAVY_WORK_ONLY (max 800m).`,
      });
    } catch (err) {
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true">
      <div className="vx-dialog">
        <h2>Light Work Evaluation · {horse.horseName}</h2>
        <p className="vx-lead">
          Evaluate the horse's healing condition before approving transition from Treatment to Light work rehabilitation.
        </p>

        <VetError error={error} />

        <div className="vx-banner vx-banner--warn" style={{ marginTop: "12px", marginBottom: "16px" }}>
          <strong>Safety Rule & Training Restriction</strong>
          <p style={{ margin: "4px 0 0", fontSize: "13px" }}>
            The training lock will automatically switch to <strong>HEAVY_WORK_ONLY</strong>. The head trainer will be restricted to light rehabilitation sessions only (maximum 800m, trotting/walking, HR &le; 130 bpm).
          </p>
        </div>

        <form onSubmit={submit}>
          <label className="vx-field vx-field--full">
            <span>Clinical Examination Findings (Required)</span>
            <textarea
              className="vx-input vx-textarea"
              rows={2}
              value={findings}
              onChange={(e) => setFindings(e.target.value)}
              placeholder="e.g. Tendon swelling subsided, heat absent, sound at easy trot"
              required
            />
          </label>

          <label className="vx-field vx-field--full">
            <span>Rehabilitation Protocol</span>
            <select
              className="vx-input"
              value={protocol}
              onChange={(e) => setProtocol(e.target.value)}
            >
              <option value="Protocol A: Hand-walking (20 min) & treadmill (10 min), max HR 120 bpm">
                Protocol A: Hand-walking (20 min) & treadmill (10 min), max HR 120 bpm
              </option>
              <option value="Protocol B: Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm">
                Protocol B: Controlled walking (15 min) & light trotting (max 800m), HR ceiling 130 bpm
              </option>
              <option value="Protocol C: Aquatrainer / hydrotherapy & light walking (max 15 min)">
                Protocol C: Aquatrainer / hydrotherapy & light walking (max 15 min)
              </option>
            </select>
          </label>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
            <label className="vx-field">
              <span>Expected Protocol Duration (Days)</span>
              <input
                type="number"
                min={1}
                max={30}
                className="vx-input"
                value={durationDays}
                onChange={(e) => setDurationDays(Number(e.target.value))}
              />
            </label>
            <label className="vx-field">
              <span>Updated Lock Level</span>
              <input
                type="text"
                disabled
                className="vx-input"
                value="HEAVY_WORK_ONLY (Light work only)"
                style={{ background: "#f8f9fa", fontWeight: 600 }}
              />
            </label>
          </div>

          <label className="vx-field vx-field--full">
            <span>Additional Instructions / Notes (Optional)</span>
            <input
              type="text"
              className="vx-input"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="e.g. Check fetlock joint temperature after every session"
            />
          </label>

          <div className="vx-actions" style={{ marginTop: "20px" }}>
            <button type="submit" className="vx-btn vx-btn--green" disabled={busy}>
              {busy ? "Saving Evaluation…" : "Approve & Advance to Light Work"}
            </button>
            <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ---------- Modal 2: Fitness Rating & Assessment ---------- */

function RatingDialog({ horse, openInjuries, onClose, onDone }) {
  const [fitnessScore, setFitnessScore] = useState(80);
  const [gaitAssessment, setGaitAssessment] = useState("Grade 0: Completely sound, clean symmetrical trot, no lameness");
  const [cardioResponse, setCardioResponse] = useState("Optimal: HR returns to resting baseline (<100 bpm) within 5 minutes");
  const [conclusion, setConclusion] = useState("Horse tolerated light work protocol well. Full range of joint motion restored with no residual heat or lameness.");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const submit = async (e) => {
    e.preventDefault();
    if (!conclusion.trim()) {
      setError({ message: "Assessment conclusion is required." });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // 1. Advance open injuries to RECOVERED
      await Promise.all(
        openInjuries.map((i) => updateInjuryStatus(i.injuryId, "RECOVERED"))
      );

      // 2. Log fitness assessment exam
      await createMedicalRecord({
        horseId: horse.horseId,
        examDate: nowForApi(),
        recordType: "EXAM",
        diagnosis: `Fitness Rating: ${fitnessScore}/100 - ${gaitAssessment.split(":")[0]}`,
        injuryConfirmed: false,
        notes: `Fitness Assessment: Score ${fitnessScore}/100. Gait: ${gaitAssessment}. Cardiovascular: ${cardioResponse}. Conclusion: ${conclusion.trim()}. ${notes.trim()}`.trim(),
      });

      onDone({
        message: `Fitness assessment recorded for ${horse.horseName} (Score: ${fitnessScore}/100). The horse is now in Rating stage and ready for final health clearance.`,
      });
    } catch (err) {
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true">
      <div className="vx-dialog">
        <h2>Fitness Rating & Assessment · {horse.horseName}</h2>
        <p className="vx-lead">
          Record clinical fitness rating and gait evaluation after completing the light work rehabilitation period.
        </p>

        <VetError error={error} />

        <form onSubmit={submit}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
            <label className="vx-field">
              <span>Fitness Rating Score: <strong>{fitnessScore}/100</strong></span>
              <input
                type="range"
                min={50}
                max={100}
                step={5}
                className="vx-input"
                style={{ padding: "8px 0" }}
                value={fitnessScore}
                onChange={(e) => setFitnessScore(Number(e.target.value))}
              />
            </label>
            <label className="vx-field">
              <span>Rating Category</span>
              <input
                type="text"
                disabled
                className="vx-input"
                value={fitnessScore >= 80 ? "Race-ready / Excellent" : fitnessScore >= 70 ? "Satisfactory / Fit for training" : "Marginal / Needs monitoring"}
                style={{ background: "#f8f9fa", fontWeight: 600 }}
              />
            </label>
          </div>

          <label className="vx-field vx-field--full">
            <span>Gait & Locomotion Evaluation</span>
            <select
              className="vx-input"
              value={gaitAssessment}
              onChange={(e) => setGaitAssessment(e.target.value)}
            >
              <option value="Grade 0: Completely sound, clean symmetrical trot, no lameness">
                Grade 0: Completely sound, clean symmetrical trot, no lameness
              </option>
              <option value="Grade 1: Trace irregularity, resolves after warm-up, non-functional">
                Grade 1: Trace irregularity, resolves after warm-up, non-functional
              </option>
            </select>
          </label>

          <label className="vx-field vx-field--full">
            <span>Cardiovascular & Heart Rate Recovery</span>
            <select
              className="vx-input"
              value={cardioResponse}
              onChange={(e) => setCardioResponse(e.target.value)}
            >
              <option value="Optimal: HR returns to resting baseline (<100 bpm) within 5 minutes">
                Optimal: HR returns to resting baseline (&lt;100 bpm) within 5 minutes
              </option>
              <option value="Good: HR returns to baseline within 10 minutes, respiration normal">
                Good: HR returns to baseline within 10 minutes, respiration normal
              </option>
              <option value="Adequate: Minor recovery lag, no signs of distress">
                Adequate: Minor recovery lag, no signs of distress
              </option>
            </select>
          </label>

          <label className="vx-field vx-field--full">
            <span>Clinical Conclusion (Required)</span>
            <textarea
              className="vx-input vx-textarea"
              rows={2}
              value={conclusion}
              onChange={(e) => setConclusion(e.target.value)}
              placeholder="Clinical evaluation and recommendation"
              required
            />
          </label>

          <label className="vx-field vx-field--full">
            <span>Additional Notes (Optional)</span>
            <input
              type="text"
              className="vx-input"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="e.g. Cleared for gallop trials next week"
            />
          </label>

          <div className="vx-actions" style={{ marginTop: "20px" }}>
            <button type="submit" className="vx-btn vx-btn--green" disabled={busy}>
              {busy ? "Saving Assessment…" : "Complete Assessment & Advance to Rating"}
            </button>
            <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ---------- Modal 3: Relapse / Return to Treatment ---------- */

function RelapseDialog({ horse, injuries, onClose, onDone }) {
  // Only active / in-progress injuries can experience relapse (PARTIAL or newly marked TREATING)
  const eligibleInjuries = (injuries || []).filter((i) => i.status !== "RECOVERED");
  const [selectedIds, setSelectedIds] = useState(() => new Set(eligibleInjuries.map((i) => i.injuryId)));
  const [reason, setReason] = useState("");
  const [restDays, setRestDays] = useState(7);
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const toggleInjury = (id) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const submit = async (e) => {
    e.preventDefault();
    if (selectedIds.size === 0) {
      setError({ message: "Please select at least one active or new injury experiencing relapse." });
      return;
    }
    if (!reason.trim()) {
      setError({ message: "Relapse observation / reason is required." });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // 1. Revert ONLY the selected active/new injuries to TREATING (old recovered injuries stay recovered!)
      await Promise.all(
        [...selectedIds].map((id) => updateInjuryStatus(id, "TREATING"))
      );

      // 2. Escalate training lock back to FULL
      await updateLockLevel(horse.horseId, "FULL");

      // 3. Log relapse medical record
      await createMedicalRecord({
        horseId: horse.horseId,
        examDate: nowForApi(),
        recordType: "EXAM",
        diagnosis: `Relapse / Recurrence: ${reason.trim()}`,
        injuryConfirmed: true,
        healthStatus: "INJURED",
        expectedRestDays: restDays,
        notes: `Clinical Relapse: Horse experienced adverse symptoms during recovery. Reverted to Treatment phase. Training lock escalated to FULL. Rest prescribed: ${restDays} days. ${notes.trim()}`.trim(),
      });

      onDone({
        message: `Relapse recorded. ${horse.horseName} has been reverted to Treatment and placed under a FULL training lock.`,
      });
    } catch (err) {
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true">
      <div className="vx-dialog">
        <h2>Record Relapse · Return to Treatment</h2>
        <p className="vx-lead">
          Record recurrence of pain, swelling, or lameness during exercise. This will revert the horse back to the Treatment phase and escalate the training lock.
        </p>

        <VetError error={error} />

        {eligibleInjuries.length === 0 ? (
          /* Case 1: No active or in-progress injuries to relapse */
          <div>
            <div className="vx-banner vx-banner--warn" style={{ marginTop: "16px", marginBottom: "16px" }}>
              <strong>No active injuries to relapse</strong>
              <p style={{ margin: "6px 0 0", fontSize: "13px", lineHeight: "1.5" }}>
                All previous injuries for {horse.horseName} are already marked as <strong>Recovered</strong>. Relapse cannot be applied to old cured injuries.
              </p>
              <p style={{ margin: "8px 0 0", fontSize: "13px", lineHeight: "1.5" }}>
                If {horse.horseName} sustained a <strong>new injury</strong> during light work or rating sessions, please record an examination first to map the new injury on the 3D model:
              </p>
              <div style={{ marginTop: "14px" }}>
                <Link
                  className="examine-button"
                  to={`/veterinarian/examination/${horse.horseId}`}
                  style={{ display: "inline-block", textDecoration: "none", textAlign: "center", lineHeight: "42px" }}
                >
                  Go to Examination &amp; Mark New Injury →
                </Link>
              </div>
            </div>

            <div className="vx-actions" style={{ marginTop: "20px" }}>
              <button type="button" className="vx-btn vx-btn--ghost" onClick={onClose}>
                Close
              </button>
            </div>
          </div>
        ) : (
          /* Case 2: Active or new injuries exist - allow selecting which one relapsed */
          <form onSubmit={submit}>
            <div className="vx-banner vx-banner--warn" style={{ marginTop: "12px", marginBottom: "16px" }}>
              <strong>Lock Escalation Warning</strong>
              <p style={{ margin: "4px 0 0", fontSize: "13px" }}>
                The training lock will be escalated to <strong>FULL LOCK</strong> (no training permitted). The horse returns to the Treatment phase for renewed medical care and rest.
              </p>
            </div>

            <fieldset className="vx-injuries" style={{ marginBottom: "16px" }}>
              <legend>Select active / new injuries experiencing relapse</legend>
              {eligibleInjuries.map((i) => (
                <label key={i.injuryId}>
                  <input
                    type="checkbox"
                    checked={selectedIds.has(i.injuryId)}
                    onChange={() => toggleInjury(i.injuryId)}
                  />
                  <span>
                    {i.bodyLocation} <small>{i.severity.replace("_", " ").toLowerCase()} · {i.status.toLowerCase()}</small>
                  </span>
                </label>
              ))}
            </fieldset>

            <label className="vx-field vx-field--full">
              <span>Relapse Clinical Observation (Required)</span>
              <textarea
                className="vx-input vx-textarea"
                rows={2}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Heat and swelling recurred at left hind hock after 15m trot, grade 2 lameness observed."
                required
              />
            </label>

            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
              <label className="vx-field">
                <span>Prescribed Rest Days</span>
                <input
                  type="number"
                  min={1}
                  max={60}
                  className="vx-input"
                  value={restDays}
                  onChange={(e) => setRestDays(Number(e.target.value))}
                />
              </label>
              <label className="vx-field">
                <span>Escalated Lock Level</span>
                <input
                  type="text"
                  disabled
                  className="vx-input"
                  value="FULL (Zero training allowed)"
                  style={{ background: "#fef2f2", color: "#b91c1c", fontWeight: 700 }}
                />
              </label>
            </div>

            <label className="vx-field vx-field--full">
              <span>New Treatment Instructions / Notes (Optional)</span>
              <input
                type="text"
                className="vx-input"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="e.g. Resume cold hosing and anti-inflammatory medication"
              />
            </label>

            <div className="vx-actions" style={{ marginTop: "20px" }}>
              <button type="submit" className="vx-btn vx-btn--danger" disabled={busy || selectedIds.size === 0}>
                {busy ? "Processing Relapse…" : "Confirm Relapse & Revert to Treatment"}
              </button>
              <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
                Cancel
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

/* ---------- Modal 4: Final Clearance & Release Training Lock ---------- */

function ConfirmDialog({ horse, openInjuries, openReports, hasActiveTreatment, hasLock, onClose, onDone }) {
  const navigate = useNavigate();
  const [picked, setPicked] = useState(() => new Set());
  const [answered, setAnswered] = useState(() => new Set());
  const [diagnosis, setDiagnosis] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const toggle = (id) =>
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const toggleReport = (id) =>
    setAnswered((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const submit = async () => {
    setError(null);

    const missing = openInjuries.filter((i) => !picked.has(i.injuryId));
    const unanswered = openReports.filter((r) => !answered.has(r.reportId));
    if (hasActiveTreatment) {
      setError({ message: "Cannot confirm recovery: Horse is still under active medical treatment/prescription." });
      return;
    }
    if (!diagnosis.trim()) {
      setError({ message: "Enter the conclusion for this recovery." });
      return;
    }
    if (missing.length > 0) {
      setError({
        message: "Every open injury must be checked before the horse can be cleared.",
        details: missing.map((i) => `Still open: ${i.bodyLocation}`),
      });
      return;
    }
    if (unanswered.length > 0) {
      setError({
        message: "Every open incident report must be answered before the horse can be cleared.",
        details: unanswered.map((r) => `Still waiting: #${r.reportId} ${r.symptoms}`),
      });
      return;
    }

    setBusy(true);
    try {
      const res = await confirmRecovery({
        horseId: horse.horseId,
        recoveredInjuryIds: [...picked],
        answeredReportIds: [...answered],
        examDate: nowForApi(),
        diagnosis: diagnosis.trim(),
        notes: notes.trim() || null,
      });
      onDone(res);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vx-dialog-title">
      <div className="vx-dialog">
        <h2 id="vx-dialog-title">
          {hasLock ? "Release training lock" : "Confirm recovery"} · {horse.horseName}
        </h2>
        <p className="vx-lead">
          This closes the checked injuries
          {openReports.length > 0 ? ", answers the checked incident reports" : ""}
          {hasLock ? ", releases the lock" : ""} and sets {horse.horseName} back to Race-fit.
        </p>

        <VetError error={error} />

        <fieldset className="vx-injuries">
          <legend>Injuries recovered</legend>
          {openInjuries.length === 0 ? (
            <p className="vx-meta">No open injuries on record (all injuries recovered).</p>
          ) : (
            openInjuries.map((i) => (
              <label key={i.injuryId}>
                <input
                  type="checkbox"
                  checked={picked.has(i.injuryId)}
                  onChange={() => toggle(i.injuryId)}
                />
                <span>
                  {i.bodyLocation} <small>{i.severity.replace("_", " ").toLowerCase()} · {i.status.toLowerCase()}</small>
                </span>
              </label>
            ))
          )}
        </fieldset>

        {openReports.length > 0 && (
          <fieldset className="vx-injuries">
            <legend>Incident reports answered by this exam</legend>
            {openReports.map((r) => (
              <label key={r.reportId}>
                <input
                  type="checkbox"
                  checked={answered.has(r.reportId)}
                  onChange={() => toggleReport(r.reportId)}
                />
                <span>
                  #{r.reportId} {r.symptoms}{" "}
                  <small>
                    {toUrgency(r.urgency).label.toLowerCase()} · {formatReportTime(r.reportedAt)} · by{" "}
                    {r.reporterName}
                  </small>
                </span>
              </label>
            ))}
          </fieldset>
        )}

        <label className="vx-field vx-field--full">
          <span>Conclusion (Required)</span>
          <input
            className="vx-input"
            value={diagnosis}
            maxLength={255}
            onChange={(e) => setDiagnosis(e.target.value)}
            placeholder="e.g. Full recovery confirmed, completely sound at all gaits"
          />
        </label>

        <label className="vx-field vx-field--full">
          <span>Notes (required if clearing before the expected rest ends)</span>
          <textarea
            className="vx-input vx-textarea"
            rows={2}
            placeholder="Why the horse can return earlier than planned"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </label>

        <div className="vx-actions">
          <button type="button" className="vx-btn vx-btn--green" disabled={busy} onClick={submit}>
            {busy ? "Saving…" : "Confirm recovery"}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
