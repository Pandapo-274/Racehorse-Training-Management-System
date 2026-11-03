// src/features/vet/VetRecoveryPage.js
// Màn 19 - Recovery (UC18).
//
// Mọi thứ dựng từ bốn endpoint đọc: ngựa, dấu chấn thương, hồ sơ khám, lệnh
// khoá. Nút "Release training lock" gọi POST /api/recovery-confirmations chứ
// không gọi /training-locks/{id}/release: lệnh dựa trên chấn thương chưa lành
// bị /release từ chối, còn recovery-confirmations khép chấn thương, mở khoá và
// nâng ngựa về ELIGIBLE trong cùng một giao dịch.
//
// Backend chỉ có hai trạng thái chấn thương ghi được từ UI (TREATING khi ghim,
// RECOVERED khi xác nhận), nên thanh tiến độ chỉ tiến thật được ở hai đầu.
//
// Báo cáo sự cố còn mở cũng phải được trả lời. Một con ngựa có thể có hai báo
// cáo (vd. một lần dừng buổi tập mà hoá ra bình thường, một lần chấn thương
// thật); nếu chỉ khám kèm một cái thì cái kia vẫn mở và vẫn chặn tập sau khi
// "hồi phục". Hộp thoại liệt kê chúng để bác sĩ tích, giống chấn thương.

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
} from "./vetApi";

const STAGES = ["Injured", "Treatment", "Light work", "Rating", "Healthy"];

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

/** Chặng hiện tại suy ra từ dữ liệu có thật (xem ghi chú đầu file). */
function stageOf(horse, injuries) {
  if (horse.status === "ELIGIBLE") return 4;
  const open = injuries.filter((i) => i.status !== "RECOVERED");
  if (injuries.length > 0 && open.length === 0) return 3;
  if (open.some((i) => i.status === "PARTIAL")) return 2;
  if (open.length > 0) return 1;
  return 0;
}

function captionOf(stage, open) {
  switch (stage) {
    case 4:
      return "Cleared. The horse is healthy and the training lock is released.";
    case 3:
      return "All marked injuries are recorded as recovered. Confirm recovery to release the training lock.";
    case 2:
      return `Partly recovered: ${open.map((i) => i.bodyLocation).join(", ")}.`;
    case 1:
      return `Under treatment: ${open.map((i) => `${i.bodyLocation} (${i.severity.replace("_", " ").toLowerCase()})`).join(", ")}.`;
    default:
      return "No injury has been marked for this horse yet.";
  }
}

function RecoveryView({ horseId }) {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [dialog, setDialog] = useState(false);
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
    ])
      .then(([horse, injuries, records, locks, reports]) =>
        setData({ horse, injuries, records, locks, reports: reports || [] }))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [horseId, navigate, reload]);

  const view = useMemo(() => {
    if (!data) return null;
    const { horse, injuries, records, locks, reports } = data;

    const exams = records.filter((r) => r.recordType === "EXAM"); // mới nhất lên trước
    const base = exams.find((r) => r.injuryConfirmed) || null;
    const followUps = base ? exams.filter((r) => new Date(r.examDate) > new Date(base.examDate)) : [];
    const activeLock = locks.find((l) => l.status === "ACTIVE") || null;
    const openInjuries = injuries.filter((i) => i.status !== "RECOVERED");

    const startIso = base?.examDate || activeLock?.issuedAt || null;
    const day = startIso ? daysSince(startIso) : null;
    const rest = base?.expectedRestDays || null;

    const reminders = REMINDERS.map(([type, label]) => {
      const r = records.find((x) => x.recordType === type && x.nextDueDate);
      return r ? { label, date: r.nextDueDate } : null;
    }).filter(Boolean);

    return {
      horse,
      exams: [...exams].reverse(), // cũ nhất lên trước, như thiết kế
      activeLock,
      openInjuries,
      openReports: reports,
      stage: stageOf(horse, injuries),
      reminders,
      subtitle:
        day != null
          ? `Day ${day + 1}${rest ? `/${rest}` : ""}${followUps.length ? ` · Follow-up ${followUps.length}` : ""}`
          : `${horse.registrationCode} · ${statusLabel(horse.status)}`,
    };
  }, [data]);

  const handleDone = useCallback((res) => {
    setDialog(false);
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

  const { horse, exams, activeLock, openInjuries, openReports, stage, reminders } = view;
  const canClear = horse.status !== "ELIGIBLE" || !!activeLock;
  const today = todayInputValue();

  return (
    <VetShell active="recovery" title={`Recovery · ${horse.horseName}`} subtitle={view.subtitle}>
      {result && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>{horse.horseName} is now {statusLabel(horse.status)}.</strong>
          <p>
            Closed {result.injuriesClosed.length} injur{result.injuriesClosed.length === 1 ? "y" : "ies"},
            answered {(result.reportsClosed || []).length} incident report
            {(result.reportsClosed || []).length === 1 ? "" : "s"} and
            released {result.locksReleased.length} training lock
            {result.locksReleased.length === 1 ? "" : "s"}.
          </p>
        </div>
      )}

      {/* Ngựa đã Healthy mà còn báo cáo mở thì vẫn bị chặn tập (IncidentGuard):
          dữ liệu cũ từ trước bản sửa, hoặc một báo cáo mới gửi sau khi clear. */}
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

      <VetCard title="Recovery progress" tone="pink">
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
        <p className="vx-caption">{captionOf(stage, openInjuries)}</p>
      </VetCard>

      <div className="vx-grid vx-grid--recovery">
        <VetCard title="Follow-up history" tone="gold">
          {exams.length === 0 ? (
            <div className="vet-inline-state">No examinations recorded yet.</div>
          ) : (
            <ul className="vx-history">
              {exams.map((r) => {
                const cleared = r.healthStatus === "ELIGIBLE";
                return (
                  <li key={r.recordId} className={cleared ? "done" : ""}>
                    <b>{formatShortDate(r.examDate)}</b>
                    <span>{r.diagnosis || r.notes || "—"}</span>
                    <em className={`vx-pill ${cleared ? "vx-pill--ok" : "vx-pill--wait"}`}>
                      {cleared ? "Recovered" : "Not yet"}
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
              Releasing the lock sets the horse back to Healthy and lets the head trainer resume
              the plan.
            </p>
            {activeLock && (
              <p className="vx-meta">
                Locked by {activeLock.issuedByName} on {formatDateTime(activeLock.issuedAt)}.
              </p>
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
              {!canClear ? "Already healthy" : activeLock ? "Release training lock" : "Confirm recovery"}
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

      {dialog && (
        <ConfirmDialog
          horse={horse}
          openInjuries={openInjuries}
          openReports={openReports}
          hasLock={!!activeLock}
          onClose={() => setDialog(false)}
          onDone={handleDone}
        />
      )}
    </VetShell>
  );
}

/* ---------- hộp thoại xác nhận ---------- */

function ConfirmDialog({ horse, openInjuries, openReports, hasLock, onClose, onDone }) {
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

    // Backend từ chối nếu còn chấn thương / báo cáo nào chưa được tích; báo sớm.
    const missing = openInjuries.filter((i) => !picked.has(i.injuryId));
    const unanswered = openReports.filter((r) => !answered.has(r.reportId));
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
            <p className="vx-meta">No open injuries on record.</p>
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

        {/* Chỉ hiện khi có: phần lớn ca hồi phục không còn báo cáo nào mở. */}
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
          <span>Conclusion</span>
          <input
            className="vx-input"
            value={diagnosis}
            maxLength={255}
            onChange={(e) => setDiagnosis(e.target.value)}
            placeholder="e.g. Swelling resolved, sound at trot"
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