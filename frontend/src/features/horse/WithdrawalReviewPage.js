// src/features/horse/WithdrawalReviewPage.js
// Club Manager duyệt yêu cầu rút ngựa do chủ ngựa gửi (V15).
//
// Duyệt là việc không quay lại được: backend huỷ mọi buổi tập còn SCHEDULED
// (báo Groom phụ trách), kết thúc mọi giáo án chưa xong, trả chuồng và đánh dấu
// ngựa đã rút. Nên mỗi yêu cầu đang chờ hiện trước con số buổi tập / giáo án sẽ
// bị huỷ, và nút duyệt hỏi xác nhận lần hai. Từ chối bắt buộc có lý do.
import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import { formatDateTime, getHorseBasePath } from "./horseApi";
import {
  listWithdrawals, approveWithdrawal, rejectWithdrawal, toWithdrawalStatus, plural,
} from "./withdrawalApi";

function PendingRow({ row, horseBase, onDone }) {
  const navigate = useNavigate();
  const [note, setNote] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [noteError, setNoteError] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const run = async (fn) => {
    setBusy(true);
    setError("");
    try {
      await fn();
      onDone();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors?.note) setNoteError(err.fieldErrors.note);
      else setError(err.message);
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  };

  const approve = () => {
    setNoteError("");
    if (!confirming) {
      setConfirming(true);
      return;
    }
    run(() => approveWithdrawal(row.withdrawalId, note));
  };

  const reject = () => {
    setConfirming(false);
    if (!note.trim()) {
      setNoteError("A reason is required when rejecting");
      return;
    }
    setNoteError("");
    run(() => rejectWithdrawal(row.withdrawalId, note));
  };

  return (
    <li className="hz-review">
      <div className="hz-review__info">
        <strong>
          <Link to={`${horseBase}/${row.horseId}`}>{row.horseName}</Link>
        </strong>
        <p>
          <span className="hz-mono">{row.registrationCode}</span>
          {row.stallNo ? <> · stall <span className="hz-mono">{row.stallNo}</span></> : null}
        </p>
        <p>From {row.requesterName} · sent {formatDateTime(row.createdAt)}</p>
        <p className="hz-review__note">“{row.reason}”</p>
        <p>
          Approving will cancel {plural(row.scheduledSessions, "scheduled session")}, end{" "}
          {plural(row.openPlans, "training plan")} and release the stall.
        </p>
      </div>

      <div className="hz-review__form">
        <div className="hz-field">
          <label htmlFor={`wnote-${row.withdrawalId}`}>Note to the owner (required to reject)</label>
          <textarea id={`wnote-${row.withdrawalId}`} className="hz-textarea" rows={2} maxLength={255}
                    value={note} onChange={(e) => setNote(e.target.value)} />
          {noteError && <p className="hz-field__error">{noteError}</p>}
        </div>

        {confirming && (
          <p className="hz-field__error" role="alert">
            This cannot be undone. Click “Confirm withdrawal” to go ahead.
          </p>
        )}
        {error && <p className="hz-form-error" role="alert">{error}</p>}

        <div className="hz-form-footer">
          <button type="button" className="hz-btn" disabled={busy} onClick={approve}>
            {confirming ? "Confirm withdrawal" : "Approve"}
          </button>
          {confirming ? (
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy}
                    onClick={() => setConfirming(false)}>
              Back
            </button>
          ) : (
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={reject}>
              Reject
            </button>
          )}
        </div>
      </div>
    </li>
  );
}

export default function WithdrawalReviewPage() {
  const navigate = useNavigate();
  const user = getUser();
  const horseBase = getHorseBasePath(user);
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(
    (signal) => {
      setError("");
      listWithdrawals({ signal })
        .then(setRows)
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

  const pending = (rows || []).filter((r) => r.status === "PENDING");
  const handled = (rows || []).filter((r) => r.status !== "PENDING");

  return (
    <HorseShell title="Horse withdrawals" subtitle="Owners asking to take their horses out of the club">
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
                  <PendingRow key={r.withdrawalId} row={r} horseBase={horseBase} onDone={() => load()} />
                ))}
              </ul>
            )}
          </Card>

          <Card title="Already handled" hint={plural(handled.length, "request")}>
            {handled.length === 0 ? (
              <p className="hz-empty">No request has been handled yet.</p>
            ) : (
              <ul className="hz-list">
                {handled.map((r) => {
                  const st = toWithdrawalStatus(r.status);
                  return (
                    <li key={r.withdrawalId}>
                      <div>
                        <strong>
                          <Link to={`${horseBase}/${r.horseId}`}>{r.horseName}</Link>
                        </strong>
                        <p>
                          {r.requesterName}
                          {r.reviewerName ? ` · by ${r.reviewerName}` : ""} · {formatDateTime(r.reviewedAt)}
                        </p>
                        {r.status === "APPROVED" && (
                          <p>
                            {plural(r.cancelledSessions, "session")} cancelled ·{" "}
                            {plural(r.endedPlans, "plan")} ended
                            {r.releasedStall ? ` · stall ${r.releasedStall} released` : ""}
                          </p>
                        )}
                        {r.status === "REJECTED" && r.reviewNote && (
                          <p className="hz-reject-note">{r.reviewNote}</p>
                        )}
                        {r.status === "APPROVED" && r.reviewNote && (
                          <p className="hz-review__note">{r.reviewNote}</p>
                        )}
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
