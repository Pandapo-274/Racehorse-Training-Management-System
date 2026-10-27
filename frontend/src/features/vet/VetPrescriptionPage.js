// src/features/vet/VetPrescriptionPage.js
// Màn Prescriptions: đơn thuốc đang dùng trên toàn đàn.
//
// Danh sách mở đầu lấy từ GET /api/treatments/active. Bấm "History" ở một dòng để
// xem toàn bộ đơn của con ngựa đó, kể cả mục đã hết hạn (bệnh sử dùng thuốc).
//
// Một mục điều trị luôn thuộc về MỘT lần khám (recordId), nên form kê đơn bắt chọn
// ngựa rồi chọn lần khám. Ngựa chưa có lần khám nào thì chưa kê được: phải khám
// trước, giống backend.
//
// Không có nút xoá: con ngựa đã thật sự dùng thuốc trong khoảng đó. Chỉ có "End
// today", đặt ngày kết thúc là hôm nay và giữ nguyên dòng trong bệnh sử.

import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { healthApplies } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import {
  listActiveTreatments,
  listTreatmentsForHorse,
  listMedicalRecords,
  listHorses,
  createTreatment,
  endTreatment,
  careTypeLabel,
  formatDate,
  todayInputValue,
  describeError,
} from "./vetApi";
import "./vet-care.css";

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/** "Painkiller" nếu có tên thuốc, không thì dòng hướng dẫn (mục không phải thuốc, vd. chườm lạnh). */
const treatmentTitle = (t) => t.medicineName || t.instruction || "Treatment";

const dose = (t) => [t.dosage, t.frequency].filter(Boolean).join(" · ") || "—";

const period = (t) =>
  `${formatDate(t.startDate)} → ${t.endDate ? formatDate(t.endDate) : "until review"}`;

export default function VetPrescriptionPage() {
  const navigate = useNavigate();

  const [active, setActive] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");

  const [history, setHistory] = useState(null); // { horseId, horseName }
  const [prescribing, setPrescribing] = useState(false);
  const [ending, setEnding] = useState(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    listActiveTreatments(ctrl.signal)
      .then(setActive)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (active || []).filter(
      (t) =>
        !q ||
        t.horseName.toLowerCase().includes(q) ||
        String(t.medicineName || "").toLowerCase().includes(q) ||
        String(t.instruction || "").toLowerCase().includes(q)
    );
  }, [active, query]);

  const handleSaved = (message) => {
    setPrescribing(false);
    setEnding(null);
    setNotice(message);
    setReload((n) => n + 1);
  };

  return (
    <VetShell
      active="prescriptions"
      title="Prescriptions"
      subtitle="Treatments in use today, across the herd"
      query={query}
      onQueryChange={setQuery}
    >
      {notice && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>{notice}</strong>
        </div>
      )}

      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </VetState>
      )}

      {!error && !active && <VetState title="Loading prescriptions…" />}

      {active && (
        <VetCard
          title="Active prescriptions"
          hint={`${plural(rows.length, "treatment")} in use today`}
          tone="green"
          actions={
            <div className="vc-actions">
              <button
                type="button"
                className="examine-button"
                onClick={() => {
                  setNotice("");
                  setPrescribing(true);
                }}
              >
                New prescription
              </button>
            </div>
          }
        >
          {rows.length === 0 ? (
            <div className="vet-inline-state">
              {query
                ? "No prescriptions match your search."
                : "No horse is on a treatment today. Use New prescription to add one."}
            </div>
          ) : (
            <div className="vc-table-wrap">
              <table className="vc-table">
                <thead>
                  <tr>
                    <th>Horse</th>
                    <th>Treatment</th>
                    <th>Dose</th>
                    <th>Period</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((t) => (
                    <tr key={t.treatmentId}>
                      <td>
                        <span className="vc-name">{t.horseName}</span>
                      </td>
                      <td>
                        <strong>{treatmentTitle(t)}</strong>
                        {t.medicineName && t.instruction && (
                          <span className="vc-sub vc-instruction">{t.instruction}</span>
                        )}
                      </td>
                      <td>{dose(t)}</td>
                      <td>{period(t)}</td>
                      <td>
                        <div className="vc-cell-actions">
                          <button
                            type="button"
                            className="vc-btn"
                            onClick={() => setHistory({ horseId: t.horseId, horseName: t.horseName })}
                          >
                            History
                          </button>
                          <button
                            type="button"
                            className="vc-btn vc-btn--danger"
                            onClick={() => setEnding(t)}
                          >
                            End today
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </VetCard>
      )}

      {history && (
        <HistoryCard
          key={`${history.horseId}-${reload}`}
          horse={history}
          onClose={() => setHistory(null)}
        />
      )}

      {prescribing && (
        <PrescribeDialog onClose={() => setPrescribing(false)} onDone={handleSaved} />
      )}

      {ending && (
        <EndDialog treatment={ending} onClose={() => setEnding(null)} onDone={handleSaved} />
      )}
    </VetShell>
  );
}

/* ---------- lịch sử đơn thuốc của một con ngựa ---------- */

function HistoryCard({ horse, onClose }) {
  const navigate = useNavigate();
  const [items, setItems] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const ctrl = new AbortController();
    listTreatmentsForHorse(horse.horseId, ctrl.signal)
      .then(setItems)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [horse.horseId, navigate]);

  return (
    <VetCard
      title={`Prescription history · ${horse.horseName}`}
      hint="Every treatment ever prescribed, including ended ones"
      tone="gold"
      className="vc-section"
      actions={
        <div className="vc-actions">
          <button type="button" className="vc-btn" onClick={onClose}>
            Close
          </button>
        </div>
      }
    >
      {error ? (
        <VetState kind="error" title={error} />
      ) : !items ? (
        <VetState title="Loading history…" />
      ) : items.length === 0 ? (
        <div className="vet-inline-state">No treatment has been prescribed for this horse.</div>
      ) : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>Treatment</th>
                <th>Dose</th>
                <th>Period</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {items.map((t) => (
                <tr key={t.treatmentId}>
                  <td>
                    <strong>{treatmentTitle(t)}</strong>
                    {t.medicineName && t.instruction && (
                      <span className="vc-sub vc-instruction">{t.instruction}</span>
                    )}
                  </td>
                  <td>{dose(t)}</td>
                  <td>{period(t)}</td>
                  <td>
                    <span className={`vc-pill vc-pill--${t.active ? "green" : "muted"}`}>
                      {t.active ? "Active" : "Ended"}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </VetCard>
  );
}

/* ---------- hộp thoại kết thúc một mục điều trị ---------- */

function EndDialog({ treatment, onClose, onDone }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await endTreatment(treatment.treatmentId);
      onDone(
        `${treatmentTitle(treatment)} ends today for ${treatment.horseName}. ` +
          "It stays in this list until the end of today, then moves to the history."
      );
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vc-end-title">
      <div className="vx-dialog">
        <h2 id="vc-end-title">End treatment · {treatment.horseName}</h2>
        <p className="vx-lead">
          The end date will be set to today, so {treatmentTitle(treatment)} is still listed as
          active until the end of today. The prescription stays in the horse's history; it is
          not deleted.
        </p>

        <VetError error={error} />

        <div className="vx-actions">
          <button type="button" className="vx-btn vx-btn--danger" disabled={busy} onClick={submit}>
            {busy ? "Saving…" : "End today"}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

/* ---------- hộp thoại kê đơn ---------- */

function PrescribeDialog({ onClose, onDone }) {
  const navigate = useNavigate();

  const [horses, setHorses] = useState(null);
  const [horseId, setHorseId] = useState("");
  const [records, setRecords] = useState(null); // null = chưa chọn ngựa / đang tải
  const [recordId, setRecordId] = useState("");

  const [medicine, setMedicine] = useState("");
  const [dosage, setDosage] = useState("");
  const [frequency, setFrequency] = useState("");
  const [instruction, setInstruction] = useState("");
  const [startDate, setStartDate] = useState(todayInputValue());
  const [endDate, setEndDate] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    const ctrl = new AbortController();
    listHorses(ctrl.signal)
      .then((list) =>
        setHorses(
          list.filter(healthApplies).sort((a, b) => a.horseName.localeCompare(b.horseName))
        )
      )
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError({ message: err.message });
      });
    return () => ctrl.abort();
  }, [navigate]);

  // Chọn ngựa thì tải các lần khám của nó để chọn lần khám cần kê đơn theo.
  useEffect(() => {
    setRecords(null);
    setRecordId("");
    if (!horseId) return undefined;
    const ctrl = new AbortController();
    listMedicalRecords(horseId, ctrl.signal)
      .then((list) => {
        setRecords(list);
        if (list.length > 0) setRecordId(String(list[0].recordId)); // mới nhất lên trước
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError({ message: err.message });
      });
    return () => ctrl.abort();
  }, [horseId, navigate]);

  const horseName = horses?.find((h) => String(h.horseId) === horseId)?.horseName;

  const submit = async () => {
    setError(null);

    // Báo sớm đúng những điều backend sẽ từ chối.
    const problems = [];
    if (!horseId) problems.push("Choose a horse.");
    else if (!recordId) problems.push("Choose the examination this prescription belongs to.");
    if (!medicine.trim() && !instruction.trim()) {
      problems.push("Give a medicine name or an instruction, otherwise nobody can tell what this is.");
    }
    if (!startDate) problems.push("Enter the start date.");
    if (startDate && endDate && endDate < startDate) {
      problems.push("The end date must be on or after the start date.");
    }
    if (problems.length) {
      setError({ message: problems[0], details: problems.slice(1) });
      return;
    }

    setBusy(true);
    try {
      await createTreatment({
        recordId: Number(recordId),
        medicineName: medicine.trim() || null,
        dosage: dosage.trim() || null,
        frequency: frequency.trim() || null,
        instruction: instruction.trim() || null,
        startDate,
        endDate: endDate || null,
      });
      onDone(
        startDate > todayInputValue()
          ? `Prescription saved for ${horseName}. It will show as active from ${formatDate(startDate)}.`
          : `Prescription saved for ${horseName}.`
      );
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
      setBusy(false);
    }
  };

  const noRecords = records && records.length === 0;

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vc-rx-title">
      <div className="vx-dialog vc-dialog-wide">
        <h2 id="vc-rx-title">New prescription</h2>
        <p className="vx-lead">A prescription is written against one examination of the horse.</p>

        <VetError error={error} />

        <div className="vc-form-grid">
          <label className="vx-field vx-field--full">
            <span>Horse</span>
            <select
              className="vx-input vc-select"
              value={horseId}
              disabled={!horses}
              onChange={(e) => setHorseId(e.target.value)}
            >
              <option value="">{horses ? "Choose a horse…" : "Loading horses…"}</option>
              {(horses || []).map((h) => (
                <option key={h.horseId} value={h.horseId}>
                  {h.horseName} · {h.registrationCode}
                </option>
              ))}
            </select>
          </label>

          {horseId && (
            <label className="vx-field vx-field--full">
              <span>Examination</span>
              <select
                className="vx-input vc-select"
                value={recordId}
                disabled={!records || noRecords}
                onChange={(e) => setRecordId(e.target.value)}
              >
                {!records && <option value="">Loading examinations…</option>}
                {noRecords && <option value="">No examination on record</option>}
                {(records || []).map((r) => (
                  <option key={r.recordId} value={r.recordId}>
                    {formatDate(r.examDate)} · {r.recordType === "EXAM" ? "Examination" : careTypeLabel(r.recordType)}
                    {r.diagnosis ? ` · ${r.diagnosis}` : ""}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>

        {noRecords && (
          <div className="vx-banner vx-banner--warn">
            <strong>{horseName} has no examination yet.</strong>
            <p>Log an examination first, then come back to write the prescription.</p>
            <button
              type="button"
              className="vx-link"
              onClick={() => navigate(`/veterinarian/examination/${horseId}`)}
            >
              Go to examination →
            </button>
          </div>
        )}

        <div className="vc-form-grid">
          <label className="vx-field vx-field--full">
            <span>Medicine</span>
            <input
              className="vx-input"
              value={medicine}
              maxLength={150}
              onChange={(e) => setMedicine(e.target.value)}
              placeholder="e.g. Phenylbutazone (or leave empty for a non-drug treatment)"
            />
          </label>

          <label className="vx-field">
            <span>Dosage</span>
            <input
              className="vx-input"
              value={dosage}
              maxLength={50}
              onChange={(e) => setDosage(e.target.value)}
              placeholder="e.g. 2 g"
            />
          </label>

          <label className="vx-field">
            <span>Frequency</span>
            <input
              className="vx-input"
              value={frequency}
              maxLength={50}
              onChange={(e) => setFrequency(e.target.value)}
              placeholder="e.g. Twice daily"
            />
          </label>

          <label className="vx-field vx-field--full">
            <span>Instruction (optional)</span>
            <textarea
              className="vx-input vx-textarea"
              rows={2}
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="e.g. Give with feed. Cold hose 20 minutes after work."
            />
          </label>

          <label className="vx-field">
            <span>Start date</span>
            <input
              type="date"
              className="vx-input"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
            />
          </label>

          <label className="vx-field">
            <span>End date (optional)</span>
            <input
              type="date"
              className="vx-input"
              value={endDate}
              min={startDate || undefined}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </label>
        </div>

        <p className="vc-hint">Leave the end date empty if the treatment runs until the next review.</p>

        <div className="vx-actions">
          <button
            type="button"
            className="vx-btn vx-btn--green"
            disabled={busy || !recordId}
            onClick={submit}
          >
            {busy ? "Saving…" : "Save prescription"}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
