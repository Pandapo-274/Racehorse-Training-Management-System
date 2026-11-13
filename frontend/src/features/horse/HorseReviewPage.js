// src/features/horse/HorseReviewPage.js
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import {
  toGender, formatDate, formatDateTime, toAge, toStage, ageProblem, canReviewRequests, listActiveTrainers,
} from "./horseApi";
import { listRequests, approveRequest, rejectRequest, toRequestStatus } from "./horseRequestApi";

function PendingRow({ row, trainers, onDone }) {
  const navigate = useNavigate();
  const [code, setCode] = useState("");
  const [stall, setStall] = useState("");
  const [trainerId, setTrainerId] = useState("");
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const pedigreeOnly = row.recordType === "PEDIGREE";
  const stage = toStage({ recordType: row.recordType, dateOfBirth: row.dateOfBirth });
  const age = toAge(row.dateOfBirth);
  const ageWarning = ageProblem(row.recordType, row.dateOfBirth);

  const run = async (fn) => {
    setBusy(true);
    setError("");
    try {
      await fn();
      onDone();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) setErrors(err.fieldErrors);
      else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const approve = () => {
    const e = {};
    if (!code.trim()) e.registrationCode = "Enter the code the club assigns";
    if (!pedigreeOnly && !stall.trim()) e.stallNo = "Assign a stall";
    setErrors(e);
    if (Object.keys(e).length > 0) return;
    run(() => approveRequest(row.requestId, {
      registrationCode: code,
      stallNo: stall,
      note,
      trainerId: !pedigreeOnly && trainerId ? Number(trainerId) : null,
    }));
  };

  const reject = () => {
    if (!note.trim()) {
      setErrors({ note: "A reason is required when rejecting" });
      return;
    }
    setErrors({});
    run(() => rejectRequest(row.requestId, note));
  };

  return (
    <li className="hz-review">
      <div className="hz-review__info">
        <strong>{row.horseName}</strong>
        <p>
          {[toGender(row.horseGender) === "—" ? null : toGender(row.horseGender), row.breed, row.color,
            row.dateOfBirth ? `${formatDate(row.dateOfBirth)}${age == null ? "" : ` (${age}y)`}` : null]
            .filter(Boolean).join(" · ") || "No other details given"}
        </p>
        <p>From {row.ownerName} · sent {formatDateTime(row.createdAt)}</p>
        <p><StatusChip tone={stage.tone} label={stage.label} title={stage.hint} /></p>
        {/* V41: parents declared by the owner; the club checks them against the papers. */}
        <p>
          {row.sireName || row.damName
            ? <>Sire <strong>{row.sireName || "unknown"}</strong> · Dam <strong>{row.damName || "unknown"}</strong> <span className="hz-muted">(check against the papers)</span></>
            : <span className="hz-muted">Parents not given</span>}
        </p>
        {(errors.sireId || errors.damId) && (
          <p className="hz-field__error">{[errors.sireId, errors.damId].filter(Boolean).join(" ")}</p>
        )}
        {row.note && <p className="hz-review__note">“{row.note}”</p>}
        {ageWarning && <p className="hz-field__error">{ageWarning}</p>}
      </div>

      <div className="hz-review__form">
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor={`code-${row.requestId}`}>Registration code</label>
            <input id={`code-${row.requestId}`} className="hz-mono" value={code} maxLength={30}
                   onChange={(e) => setCode(e.target.value)} />
            {errors.registrationCode && <p className="hz-field__error">{errors.registrationCode}</p>}
          </div>

          {!pedigreeOnly && (
            <>
              <div className="hz-field">
                <label htmlFor={`stall-${row.requestId}`}>Stall</label>
                <input id={`stall-${row.requestId}`} className="hz-mono" value={stall} maxLength={20}
                       onChange={(e) => setStall(e.target.value)} />
                {errors.stallNo && <p className="hz-field__error">{errors.stallNo}</p>}
              </div>

              <div className="hz-field">
                <label htmlFor={`trainer-${row.requestId}`}>Assign Head Trainer</label>
                <select
                  id={`trainer-${row.requestId}`}
                  value={trainerId}
                  onChange={(e) => setTrainerId(e.target.value)}
                >
                  <option value="">— Assign later —</option>
                  {(trainers || []).map((t) => (
                    <option key={t.userId} value={t.userId} disabled={t.managedHorseCount >= t.maxHorses}>
                      {t.fullName} ({t.managedHorseCount}/{t.maxHorses} horses)
                    </option>
                  ))}
                </select>
                {errors.trainerId && <p className="hz-field__error">{errors.trainerId}</p>}
              </div>
            </>
          )}
        </div>

        <div className="hz-field">
          <label htmlFor={`note-${row.requestId}`}>Note to the owner (required to reject)</label>
          <textarea id={`note-${row.requestId}`} className="hz-textarea" rows={2} maxLength={255}
                    value={note} onChange={(e) => setNote(e.target.value)} />
          {errors.note && <p className="hz-field__error">{errors.note}</p>}
        </div>

        {error && <p className="hz-form-error" role="alert">{error}</p>}

        <div className="hz-form-footer">
          <button type="button" className="hz-btn" disabled={busy} onClick={approve}>Approve</button>
          <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={reject}>
            Reject
          </button>
        </div>
      </div>
    </li>
  );
}

export default function HorseReviewPage() {
  const navigate = useNavigate();
  const user = getUser();
  const [rows, setRows] = useState(null);
  const [trainers, setTrainers] = useState([]);
  const [error, setError] = useState("");

  const load = useCallback(
    (signal) => {
      setError("");
      Promise.all([
        listRequests(signal),
        listActiveTrainers(signal).catch(() => []),
      ])
        .then(([reqs, trs]) => {
          setRows(reqs);
          setTrainers(trs || []);
        })
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        });
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  if (!canReviewRequests(user)) {
    return (
      <HorseShell title="Horse registrations">
        <StateBlock kind="error" title="You cannot review registrations" />
      </HorseShell>
    );
  }

  const pending = (rows || []).filter((r) => r.status === "PENDING");
  const handled = (rows || []).filter((r) => r.status !== "PENDING");

  return (
    <HorseShell title="Horse registrations" subtitle="Requests sent by horse owners, waiting for the club">
      {error && <StateBlock kind="error" title={error} onRetry={() => load()} />}
      {!rows && !error && <StateBlock title="Loading…" />}

      {rows && (
        <>
          <Card title="Waiting for review" hint={`${pending.length} pending`}>
            {pending.length === 0 ? (
              <p className="hz-empty">Nothing is waiting.</p>
            ) : (
              <ul className="hz-list">
                {pending.map((r) => (
                  <PendingRow key={r.requestId} row={r} trainers={trainers} onDone={() => load()} />
                ))}
              </ul>
            )}
          </Card>

          <Card title="Already handled" hint={`${handled.length} requests`}>
            {handled.length === 0 ? (
              <p className="hz-empty">No request has been handled yet.</p>
            ) : (
              <ul className="hz-list">
                {handled.map((r) => {
                  const st = toRequestStatus(r.status);
                  return (
                    <li key={r.requestId}>
                      <div>
                        <strong>{r.horseName}</strong>
                        <p>{r.ownerName} · {r.reviewerName && r.status !== "CANCELLED" ? `by ${r.reviewerName}` : ""} {formatDateTime(r.reviewedAt)}</p>
                        {r.reviewNote && <p className="hz-reject-note">{r.reviewNote}</p>}
                      </div>
                      <StatusChip tone={st.tone} label={st.label} />
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </>
      )}
    </HorseShell>
  );
}
