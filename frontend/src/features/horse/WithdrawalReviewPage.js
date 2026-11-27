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
import { formatDate, formatDateTime, getHorseBasePath } from "./horseApi";
import {
  listWithdrawals, approveWithdrawal, rejectWithdrawal, toWithdrawalStatus, plural, getWithdrawalBasis,
} from "./withdrawalApi";
import { formatVnd, todayIsoLocal } from "./horseRequestApi";
import WithdrawalBasis from "./WithdrawalBasis";
import { useAppDialog } from "../common/AppDialog";
import ResultLine, { ok, fail } from "../common/ResultLine";

// Một dòng yêu cầu đang chờ: ô ghi chú + hai nút Approve / Reject. Mỗi dòng có state riêng (note, busy...)
// nên xử lý dòng này không ảnh hưởng dòng khác. onDone(kết quả) báo trang cha tải lại danh sách.
function PendingRow({ row, horseBase, onDone }) {
  const navigate = useNavigate();
  const dialog = useAppDialog();
  const [note, setNote] = useState("");
  const [noteError, setNoteError] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  // V62: the basis to decide on (contract, refund, unpaid costs, vet lock, races).
  const [basis, setBasis] = useState(null);
  useEffect(() => {
    const ctrl = new AbortController();
    getWithdrawalBasis(row.withdrawalId, ctrl.signal).then(setBasis).catch(() => setBasis(null));
    return () => ctrl.abort();
  }, [row.withdrawalId]);
  const tooEarly = row.leaveOn && todayIsoLocal() < row.leaveOn;

  /* On success the row leaves this list, so the page shows the result where it was. */
  const run = async (fn, okText, failText) => {
    setBusy(true);
    setResult(null);
    try {
      await fn();
      onDone(ok(okText));
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors?.note) setNoteError(err.fieldErrors.note);
      setResult(fail(`${failText}: ${err.message}`));
    } finally {
      setBusy(false);
    }
  };

  // Duyệt: xác nhận lần hai với con số cụ thể (scheduledSessions, openPlans, chuồng) do backend tính sẵn,
  // rồi PUT /api/horse-withdrawals/{id}/approve - WithdrawalService.approve làm mọi việc trong một giao dịch.
  const approve = async () => {
    setNoteError("");
    const confirmed = await dialog.confirm({
      title: `Approve withdrawal of ${row.horseName}?`,
      message: `This cannot be undone. It cancels ${plural(row.scheduledSessions, "scheduled session")}, ends `
        + `${plural(row.openPlans, "training plan")}, removes upcoming stable tasks, scratches upcoming race entries`
        + `${row.stallNo ? ` and releases stall ${row.stallNo}` : ""}.`
        + (basis?.refundAmount > 0 ? ` The club then owes ${row.requesterName} ${formatVnd(basis.refundAmount)}.` : ""),
      confirmLabel: "Approve withdrawal",
      danger: true,
    });
    if (!confirmed) return;
    run(() => approveWithdrawal(row.withdrawalId, note),
        `Approved: ${row.horseName} has left the club${row.stallNo ? ` and stall ${row.stallNo} is free` : ""}. `
          + `${row.requesterName} has been notified.`,
        `Could not approve the withdrawal of ${row.horseName}`);
  };

  const reject = () => {
    if (!note.trim()) {
      setNoteError("A reason is required when rejecting");
      return;
    }
    setNoteError("");
    run(() => rejectWithdrawal(row.withdrawalId, note),
        `Rejected: ${row.horseName} stays at the club. ${row.requesterName} has been told why.`,
        `Could not reject the withdrawal of ${row.horseName}`);
  };

  return (
    <li className="hz-review">
      {dialog.element}
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
        {row.leaveOn && <p>Collects the horse on <strong>{formatDate(row.leaveOn)}</strong></p>}
        <WithdrawalBasis basis={basis} />
      </div>

      <div className="hz-review__form">
        <div className="hz-field">
          <label htmlFor={`wnote-${row.withdrawalId}`}>Note to the owner (required to reject)</label>
          <textarea id={`wnote-${row.withdrawalId}`} className="hz-textarea" rows={2} maxLength={255}
                    value={note} onChange={(e) => setNote(e.target.value)} />
          {noteError && <p className="hz-field__error">{noteError}</p>}
        </div>

        <div className="hz-form-footer">
          <button type="button" className="hz-btn" disabled={busy || tooEarly} onClick={approve}
                  title={tooEarly ? `The horse keeps training until ${formatDate(row.leaveOn)}` : undefined}>
            {busy ? "Saving…" : "Approve"}
          </button>
          <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={reject}>
            Reject
          </button>
        </div>
        {tooEarly && (
          <p className="hz-field__hint">Approve on or after {formatDate(row.leaveOn)}: the horse keeps training until then.</p>
        )}
        <ResultLine result={result} />
      </div>
    </li>
  );
}

// Trang /manager/withdrawals: tải mọi yêu cầu, tách nhóm đang chờ (PendingRow) và lịch sử đã xử lý.
// results giữ dòng kết quả của yêu cầu vừa xử lý (dòng đó đã rời danh sách chờ).
export default function WithdrawalReviewPage() {
  const navigate = useNavigate();
  const user = getUser();
  const horseBase = getHorseBasePath(user);
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");
  const [results, setResults] = useState([]); // outcomes of rows handled on this visit, newest first

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
            {results.map((res, i) => <ResultLine key={i} result={res} />)}
            {pending.length === 0 ? (
              <p className="hz-empty">Nothing is waiting.</p>
            ) : (
              <ul className="hz-list">
                {pending.map((r) => (
                  <PendingRow key={r.withdrawalId} row={r} horseBase={horseBase} onDone={(res) => { setResults((list) => [res, ...list]); load(); }} />
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
                          {r.byClub ? "Withdrawn by the club (stay ended)" : r.requesterName}
                          {r.reviewerName ? ` · by ${r.reviewerName}` : ""} · {formatDateTime(r.reviewedAt)}
                        </p>
                        {r.status === "APPROVED" && (
                          <p>
                            {plural(r.cancelledSessions, "session")} cancelled ·{" "}
                            {plural(r.endedPlans, "plan")} ended
                            {r.releasedStall ? ` · stall ${r.releasedStall} released` : ""}
                          </p>
                        )}
                        {r.status === "APPROVED" && r.refundAmount > 0 && (
                          <p>Refund owed: {formatVnd(r.refundAmount)} for {plural(r.refundMonths, "month")} not started</p>
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
