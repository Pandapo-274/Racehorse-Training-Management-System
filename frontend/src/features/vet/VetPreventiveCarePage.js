// src/features/vet/VetPreventiveCarePage.js
// Màn Preventive care (UC17): ai sắp đến hạn tiêm phòng, tẩy giun, cắt móng.
//
// Dữ liệu từ GET /api/preventive-care?includeOk=true. Backend sinh đủ ba ô cho
// MỌI con ngựa, kể cả con chưa từng được làm (trạng thái NEVER) - nên đây là nơi
// duy nhất thấy được những con "im lặng" mà bảng hồ sơ khám không có dòng nào.
//
// Màn này vừa đọc vừa ghi:
//   - Ghi một lần tiêm / tẩy giun / móng: POST /api/medical-records với recordType
//     tương ứng. Form khám ở /veterinarian/examination chỉ ghi loại EXAM nên phải
//     có đường ghi này ở đây, nếu không bảng nhắc lịch không bao giờ hết việc.
//   - Nút "Send reminders now" chạy ngay tác vụ mà backend vẫn tự chạy lúc 07:00.
//     Bấm lại liên tiếp không gửi trùng thông báo.

import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import {
  getPreventiveBoard,
  runPreventiveReminders,
  createMedicalRecord,
  CARE_TYPES,
  careTypeLabel,
  careStatusMeta,
  describeDue,
  examTimestamp,
  formatDate,
  todayInputValue,
  tomorrowInputValue,
  describeError,
} from "./vetApi";
import "./vet-care.css";

const PAGE_SIZE = 30;

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/* Bốn thẻ đếm. `attention` là bộ lọc mặc định: mọi thứ cần làm, trừ ô đang ổn. */
const STAT_CARDS = [
  { key: "OVERDUE", label: "Overdue", type: "red" },
  { key: "NEVER", label: "Never done", type: "pink" },
  { key: "DUE_SOON", label: "Due soon", type: "gold" },
  { key: "OK", label: "Up to date", type: "green" },
];

function StatCard({ value, label, type, active, onClick }) {
  return (
    <button
      type="button"
      className={`vet-stat-card ${type}${active ? " selected" : ""}`}
      onClick={onClick}
      aria-pressed={active}
    >
      <span className="vet-stat-value">{value}</span>
      <span className="vet-stat-label">{label}</span>
    </button>
  );
}

export default function VetPreventiveCarePage() {
  const navigate = useNavigate();

  const [board, setBoard] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("attention");
  const [typeFilter, setTypeFilter] = useState("all");
  const [shown, setShown] = useState(PAGE_SIZE);

  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState(null);
  const [runError, setRunError] = useState(null);
  const [notice, setNotice] = useState("");
  const [recording, setRecording] = useState(null); // ô đang mở hộp thoại ghi

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    getPreventiveBoard(ctrl.signal)
      .then(setBoard)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    // Backend đã xếp theo mức cấp thiết (quá hạn lâu nhất lên đầu): giữ nguyên thứ tự.
    return (board?.items || [])
      .filter((i) =>
        statusFilter === "attention" ? i.careStatus !== "OK" : i.careStatus === statusFilter
      )
      .filter((i) => typeFilter === "all" || i.careType === typeFilter)
      .filter(
        (i) =>
          !q ||
          i.horseName.toLowerCase().includes(q) ||
          String(i.registrationCode || "").toLowerCase().includes(q) ||
          String(i.stallNo || "").toLowerCase().includes(q)
      );
  }, [board, statusFilter, typeFilter, query]);

  // Đổi bộ lọc thì về trang đầu, không để người dùng đứng ở "trang 5" của danh sách mới.
  useEffect(() => setShown(PAGE_SIZE), [statusFilter, typeFilter, query]);

  const counts = board?.counts || {};
  const unscheduled = counts.UNSCHEDULED || 0;
  const needAction = (counts.OVERDUE || 0) + (counts.NEVER || 0) + (counts.DUE_SOON || 0);

  const runNow = async () => {
    setRunning(true);
    setRunResult(null);
    setRunError(null);
    setNotice("");
    try {
      setRunResult(await runPreventiveReminders());
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setRunError(describeError(err));
    } finally {
      setRunning(false);
    }
  };

  const handleRecorded = (message) => {
    setRecording(null);
    setRunResult(null);
    setNotice(message);
    setReload((n) => n + 1);
  };

  const today = new Date().toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  return (
    <VetShell
      active="preventive"
      title="Preventive care"
      subtitle={`Vaccination, deworming and farrier · ${today}`}
      query={query}
      onQueryChange={setQuery}
    >
      {notice && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>{notice}</strong>
        </div>
      )}

      <RunBanner result={runResult} error={runError} />

      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </VetState>
      )}

      {!error && !board && <VetState title="Loading preventive care…" />}

      {board && (
        <>
          <div className="vet-stats">
            {STAT_CARDS.map((card) => (
              <StatCard
                key={card.key}
                value={counts[card.key] || 0}
                label={card.label}
                type={card.type}
                active={statusFilter === card.key}
                onClick={() => setStatusFilter(statusFilter === card.key ? "attention" : card.key)}
              />
            ))}
          </div>

          <VetCard
            title="Care schedule"
            hint={`${plural(needAction, "item")} need attention${
              unscheduled > 0 ? ` · ${plural(unscheduled, "record")} without a next due date` : ""
            }`}
            tone="gold"
            className="vc-section"
            actions={
              <div className="vc-actions">
                <button
                  type="button"
                  className="examine-button"
                  disabled={running}
                  onClick={runNow}
                >
                  {running ? "Sending…" : "Send reminders now"}
                </button>
              </div>
            }
          >
            <div className="vc-toolbar">
              <div className="vet-status-segment" role="group" aria-label="Filter by care type">
                <button
                  type="button"
                  aria-pressed={typeFilter === "all"}
                  onClick={() => setTypeFilter("all")}
                >
                  All
                </button>
                {CARE_TYPES.map((t) => (
                  <button
                    key={t.value}
                    type="button"
                    aria-pressed={typeFilter === t.value}
                    onClick={() => setTypeFilter(t.value)}
                  >
                    {t.label}
                  </button>
                ))}
              </div>

              <span className="vx-meta">
                {statusFilter === "attention"
                  ? "Showing everything that needs doing. Pick a card above to narrow it down."
                  : `Showing: ${careStatusMeta(statusFilter).label}. Click the card again to go back.`}
              </span>
            </div>

            {rows.length === 0 ? (
              <div className="vet-inline-state">
                {query || typeFilter !== "all"
                  ? "No items match your search."
                  : "Nothing to do here. Every horse is up to date."}
              </div>
            ) : (
              <>
                <div className="vc-table-wrap">
                  <table className="vc-table">
                    <thead>
                      <tr>
                        <th>Horse</th>
                        <th>Care</th>
                        <th>Last done</th>
                        <th>Next due</th>
                        <th>Status</th>
                        <th aria-label="Actions" />
                      </tr>
                    </thead>
                    <tbody>
                      {rows.slice(0, shown).map((item) => {
                        const meta = careStatusMeta(item.careStatus);
                        return (
                          <tr key={`${item.horseId}-${item.careType}`}>
                            <td>
                              <span className="vc-name">{item.horseName}</span>
                              <span className="vc-sub">
                                {item.registrationCode} · Stall {item.stallNo || "—"}
                              </span>
                            </td>
                            <td>{careTypeLabel(item.careType)}</td>
                            <td>
                              {item.lastExamDate ? formatDate(item.lastExamDate) : "Never"}
                              {item.vetName && <span className="vc-sub">by {item.vetName}</span>}
                            </td>
                            <td>
                              {item.nextDueDate ? formatDate(item.nextDueDate) : "—"}
                              <span className="vc-sub">{describeDue(item)}</span>
                            </td>
                            <td>
                              <span className={`vc-pill vc-pill--${meta.tone}`}>{meta.label}</span>
                            </td>
                            <td>
                              <div className="vc-cell-actions">
                                <button
                                  type="button"
                                  className="vc-btn"
                                  onClick={() => setRecording(item)}
                                >
                                  Record
                                </button>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>

                {rows.length > shown && (
                  <div className="vc-more">
                    <button
                      type="button"
                      className="vx-btn vx-btn--ghost"
                      onClick={() => setShown((n) => n + PAGE_SIZE)}
                    >
                      Show more ({rows.length - shown} left)
                    </button>
                  </div>
                )}
              </>
            )}
          </VetCard>
        </>
      )}

      {recording && (
        <RecordDialog
          item={recording}
          onClose={() => setRecording(null)}
          onDone={handleRecorded}
        />
      )}
    </VetShell>
  );
}

/* ---------- kết quả của nút "Send reminders now" ---------- */

function RunBanner({ result, error }) {
  if (error) return <VetError error={error} />;
  if (!result) return null;

  if (result.reminded === 0) {
    return (
      <div className="vx-banner vx-banner--ok" role="status">
        <strong>Nothing new to remind.</strong>
        <p>
          {result.skipped > 0
            ? `${plural(result.skipped, "item")} already reminded recently, so no new notification was sent.`
            : "Every horse is up to date."}
        </p>
      </div>
    );
  }

  // Có việc nhưng chẳng có bác sĩ nào đang ACTIVE để báo: không có gì được gửi đi.
  if (result.vetsNotified === 0) {
    return (
      <div className="vx-banner vx-banner--warn" role="status">
        <strong>No notification was sent.</strong>
        <p>
          {plural(result.reminded, "item")} need attention, but there is no active veterinarian
          account to notify.
        </p>
      </div>
    );
  }

  return (
    <div className="vx-banner vx-banner--ok" role="status">
      <strong>
        Reminder sent to {plural(result.vetsNotified, "veterinarian")}. Check the bell for it.
      </strong>
      <p>
        {plural(result.reminded, "item")}: {result.overdue} overdue, {result.dueSoon} due soon,{" "}
        {result.never} never done.
        {result.skipped > 0 ? ` ${plural(result.skipped, "item")} skipped (reminded recently).` : ""}
      </p>
    </div>
  );
}

/* ---------- hộp thoại ghi một lần chăm sóc ---------- */

function RecordDialog({ item, onClose, onDone }) {
  const navigate = useNavigate();
  const [doneOn, setDoneOn] = useState(todayInputValue());
  const [nextDue, setNextDue] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const label = careTypeLabel(item.careType);

  const submit = async () => {
    setError(null);

    // Báo sớm đúng những điều backend sẽ từ chối (UC14: ngày hẹn bắt buộc và ở tương lai).
    const problems = [];
    if (!doneOn) problems.push("Enter the date it was done.");
    else if (doneOn > todayInputValue()) problems.push("The date done cannot be in the future.");
    if (!nextDue) problems.push("Enter the date it is next due, or it will never be reminded.");
    else if (nextDue <= todayInputValue()) problems.push("The next due date must be after today.");
    if (problems.length) {
      setError({ message: problems[0], details: problems.slice(1) });
      return;
    }

    setBusy(true);
    try {
      await createMedicalRecord({
        horseId: item.horseId,
        recordType: item.careType,
        examDate: examTimestamp(doneOn),
        // Gửi đủ các trường của MedicalExamRequest như form khám: injuryConfirmed là
        // boolean nguyên thủy, thiếu nó thì backend (Jackson 3) từ chối cả body với
        // "Malformed JSON request body".
        healthStatus: null,
        injuryConfirmed: false,
        expectedRestDays: null,
        nextDueDate: nextDue,
        notes: notes.trim() || null,
      });
      onDone(`${label} recorded for ${item.horseName}. Next due ${formatDate(nextDue)}.`);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vc-record-title">
      <div className="vx-dialog">
        <h2 id="vc-record-title">
          Record {label.toLowerCase()} · {item.horseName}
        </h2>
        <p className="vx-lead">
          {item.lastExamDate
            ? `Last done ${formatDate(item.lastExamDate)}${
                item.nextDueDate ? `, was due ${formatDate(item.nextDueDate)}` : ""
              }.`
            : "This has never been recorded for this horse."}
        </p>

        <VetError error={error} />

        <div className="vc-form-grid">
          <label className="vx-field">
            <span>Date done</span>
            <input
              type="date"
              className="vx-input"
              value={doneOn}
              max={todayInputValue()}
              onChange={(e) => setDoneOn(e.target.value)}
            />
          </label>

          <label className="vx-field">
            <span>Next due</span>
            <input
              type="date"
              className="vx-input"
              value={nextDue}
              min={tomorrowInputValue()}
              onChange={(e) => setNextDue(e.target.value)}
            />
          </label>
        </div>

        <label className="vx-field vx-field--full">
          <span>Notes (optional)</span>
          <textarea
            className="vx-input vx-textarea"
            rows={2}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="e.g. Product used, batch number"
          />
        </label>

        <div className="vx-actions">
          <button type="button" className="vx-btn vx-btn--green" disabled={busy} onClick={submit}>
            {busy ? "Saving…" : `Save ${label.toLowerCase()}`}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
