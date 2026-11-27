// src/features/horse/HorseWithdrawalPanel.js
// Thẻ "Leave the club" trên hồ sơ ngựa (V15).
//
//  - Chủ ngựa: gửi yêu cầu rút (bắt buộc có lý do), xem yêu cầu đang chờ và
//    huỷ nếu đổi ý, xem lý do nếu học viện từ chối.
//  - Club Manager: thấy yêu cầu đang chờ, kèm đường tới hàng chờ duyệt.
//  - Ngựa đã rút: mọi vai trò thấy ngày rút; chủ ngựa và manager thấy thêm
//    chuồng đã trả và số buổi tập / giáo án bị huỷ theo.
//
// Chỉ HORSE_OWNER và CLUB_MANAGER gọi được GET /api/horse-withdrawals, nên các
// vai trò khác không gọi API, chỉ đọc activityStatus / withdrawnAt của con ngựa.

import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, handleAuthError } from "../auth/authService";
import { Card, StatusChip } from "./HorseShell";
import { canRequestWithdrawal, formatDate, formatDateTime, isPedigreeOnly, isWithdrawn, toActivity } from "./horseApi";
import {
  listWithdrawals, submitWithdrawal, cancelWithdrawal, toWithdrawalStatus, plural,
  previewWithdrawal, getWithdrawalBasis, NOTICE_DAYS, MAX_LEAVE_AHEAD_DAYS,
} from "./withdrawalApi";
import { isoAddDays, todayIsoLocal, formatVnd } from "./horseRequestApi";
import WithdrawalBasis from "./WithdrawalBasis";
import { useAppDialog } from "../common/AppDialog";
import ResultLine, { ok, fail } from "../common/ResultLine";

const REASON_MAX = 500;

function Row({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

// Props: horse, user. isOwner: chủ ĐÚNG của con ngựa này (ownerId khớp), không chỉ là vai trò HORSE_OWNER.
export default function HorseWithdrawalPanel({ horse, user }) {
  const navigate = useNavigate();
  const role = user?.role;
  const isOwner = role === "HORSE_OWNER" && horse.ownerId === user?.userId;
  const isManager = role === "CLUB_MANAGER";
  const mayLoad = isOwner || isManager;
  const withdrawn = isWithdrawn(horse);

  const [rows, setRows] = useState(null);
  const [loadError, setLoadError] = useState("");
  const [reason, setReason] = useState("");
  // V62: the collection day; 15 days ahead by default, the notice in clause 9.
  const [leaveOn, setLeaveOn] = useState(() => isoAddDays(todayIsoLocal(), NOTICE_DAYS));
  const [leaveError, setLeaveError] = useState("");
  const [basis, setBasis] = useState(null);
  const [fieldError, setFieldError] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [result, setResult] = useState(null);
  const dialog = useAppDialog();

  // Tải lịch sử yêu cầu rút của con ngựa (chỉ chủ ngựa và Manager mới gọi được API này).
  const load = useCallback(
    (signal) => {
      if (!mayLoad) return;
      setLoadError("");
      listWithdrawals({ horseId: horse.horseId, signal })
        .then(setRows)
        .catch((err) => {
          if (err.name === "AbortError") return;
          // Phần phụ của trang hồ sơ: chỉ hết phiên mới rời trang.
          if (err instanceof ApiError && err.status === 401 && handleAuthError(err, navigate)) return;
          setLoadError(err.message);
        });
    },
    [mayLoad, horse.horseId, navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const pendingId = (rows || []).find((r) => r.status === "PENDING")?.withdrawalId;
  const canAsk = !withdrawn && isOwner && rows && !pendingId;
  // V62: the basis - for the request waiting, or a preview of the day being chosen.
  useEffect(() => {
    if (withdrawn || !mayLoad || !rows) return undefined;
    if (!pendingId && !canAsk) { setBasis(null); return undefined; }
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      (pendingId ? getWithdrawalBasis(pendingId, ctrl.signal) : previewWithdrawal(horse.horseId, leaveOn, ctrl.signal))
        .then(setBasis).catch(() => setBasis(null));
    }, 250);
    return () => { clearTimeout(timer); ctrl.abort(); };
  }, [withdrawn, mayLoad, rows, pendingId, canAsk, horse.horseId, leaveOn]);

  if (isPedigreeOnly(horse)) return null;
  // Ngựa còn ở học viện mà người xem không phải chủ hay manager: không có gì để nói.
  if (!withdrawn && !mayLoad) return null;

  // Một ngựa có thể có nhiều yêu cầu theo thời gian; lấy yêu cầu đang chờ, yêu cầu đã duyệt và lần bị từ chối
  // gần nhất để hiện đúng khối thông tin.
  const pending = (rows || []).find((r) => r.status === "PENDING");
  const approved = (rows || []).find((r) => r.status === "APPROVED");
  const lastClosed = (rows || []).find((r) => r.status === "REJECTED");
  const activity = toActivity(horse.activityStatus);

  /* The outcome is shown right under the button (ResultLine), with the horse's name. */
  const run = async (fn, okMessage, failText) => {
    setBusy(true);
    setError("");
    setNotice("");
    setResult(null);
    try {
      await fn();
      setResult(ok(okMessage));
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors?.reason) setFieldError(err.fieldErrors.reason);
      if (err.fieldErrors?.leaveOn) setLeaveError(err.fieldErrors.leaveOn);
      setResult(fail(`${failText}: ${err.message}`));
    } finally {
      setBusy(false);
    }
  };

  // Gửi yêu cầu rút (POST /api/horse-withdrawals): lý do bắt buộc, tối đa REASON_MAX ký tự. Manager nhận thông báo.
  const submit = (e) => {
    e.preventDefault();
    const text = reason.trim();
    if (!text) {
      setFieldError("Say why the horse is leaving the club");
      return;
    }
    if (text.length > REASON_MAX) {
      setFieldError(`${REASON_MAX} characters at most`);
      return;
    }
    const today = todayIsoLocal();
    if (!leaveOn) { setLeaveError("Choose the day you collect the horse"); return; }
    if (leaveOn < today) { setLeaveError("Choose today or a later day"); return; }
    if (leaveOn > isoAddDays(today, MAX_LEAVE_AHEAD_DAYS)) {
      setLeaveError(`Choose a day within ${MAX_LEAVE_AHEAD_DAYS} days`);
      return;
    }
    setFieldError("");
    setLeaveError("");
    run(async () => {
      await submitWithdrawal(horse.horseId, text, leaveOn);
      setReason("");
    }, `Withdrawal request for ${horse.horseName} sent: you collect it on ${formatDate(leaveOn)}`
       + (basis?.refundAmount > 0 ? `, and the club refunds ${formatVnd(basis.refundAmount)}.` : ".")
       + " The club manager confirms on that day.",
       `Could not send the withdrawal request for ${horse.horseName}`);
  };

  // Huỷ yêu cầu đang chờ (chủ ngựa đổi ý): hỏi xác nhận bằng AppDialog trước khi gọi API.
  const cancel = async () => {
    if (!pending) return;
    const confirmed = await dialog.confirm({
      title: `Cancel the withdrawal of ${horse.horseName}?`,
      message: "The request is taken back. The horse stays at the club and you can ask again later.",
      confirmLabel: "Cancel request",
      cancelLabel: "Keep it",
      danger: true,
    });
    if (!confirmed) return;
    run(() => cancelWithdrawal(pending.withdrawalId),
        `Withdrawal request for ${horse.horseName} cancelled. The horse stays at the club.`,
        `Could not cancel the withdrawal request for ${horse.horseName}`);
  };

  let body;

  if (withdrawn) {
    body = (
      <>
        <Row label="Status">
          <StatusChip tone={activity.tone} label={activity.label} title={activity.hint} />
        </Row>
        <Row label="Left the club">{formatDateTime(horse.withdrawnAt)}</Row>
        {approved && (
          <>
            {approved.byClub && <Row label="By">The club: the stay ended without renewal</Row>}
            <Row label="Reason">{approved.reason}</Row>
            <Row label="Approved by">
              {approved.reviewerName || "—"}
              {approved.reviewNote && <p className="hz-muted hz-note">{approved.reviewNote}</p>}
            </Row>
            <Row label="Released stall">
              <span className="hz-mono">{approved.releasedStall || "—"}</span>
            </Row>
            <Row label="Called off">
              {plural(approved.cancelledSessions, "session")} cancelled ·{" "}
              {plural(approved.endedPlans, "plan")} ended
            </Row>
            {approved.refundAmount > 0 && (
              <Row label="Refund owed">
                {formatVnd(approved.refundAmount)} for {plural(approved.refundMonths, "month")} not started
                {" "}(stay paid until {formatDate(approved.noticeUntil)})
              </Row>
            )}
          </>
        )}
        <p className="hz-muted hz-note">
          The horse is kept on record with its history, but no longer appears on training
          plans, the health board or the groom schedule.
        </p>
      </>
    );
  } else if (loadError) {
    body = <p className="hz-muted">Could not load withdrawal requests: {loadError}</p>;
  } else if (!rows) {
    body = <p className="hz-muted">Loading…</p>;
  } else if (pending) {
    const st = toWithdrawalStatus(pending.status);
    body = (
      <>
        <Row label="Request">
          <StatusChip tone={st.tone} label={st.label} />
        </Row>
        <Row label="Sent">
          {formatDateTime(pending.createdAt)}
          {isManager && <span className="hz-muted"> · by {pending.requesterName}</span>}
        </Row>
        <Row label="Reason">{pending.reason}</Row>
        {pending.leaveOn && <Row label="Collect on">{formatDate(pending.leaveOn)}</Row>}
        <WithdrawalBasis basis={basis} />
        {isOwner && (
          <div className="hz-form-footer">
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={cancel}>
              Cancel request
            </button>
          </div>
        )}
        {isManager && (
          <p className="hz-note">
            <Link to="/manager/withdrawals">Review it in Withdrawals</Link>
          </p>
        )}
      </>
    );
  } else if (canRequestWithdrawal(user, horse)) {
    body = (
      <form onSubmit={submit} noValidate>
        <p className="hz-muted hz-note">
          Ask the club to release this horse. If the club manager approves, its scheduled
          sessions, stable tasks and upcoming race entries are cancelled, its training plans
          end and its stall is released.
        </p>

        {lastClosed && (
          <p className="hz-reject-note">
            Your last request was turned down: {lastClosed.reviewNote}
          </p>
        )}

        <div className="hz-field">
          <label htmlFor="withdraw-leave">Collect the horse on</label>
          <input id="withdraw-leave" type="date" value={leaveOn} min={todayIsoLocal()}
                 max={isoAddDays(todayIsoLocal(), MAX_LEAVE_AHEAD_DAYS)}
                 onChange={(e) => setLeaveOn(e.target.value)} aria-invalid={!!leaveError} />
          {leaveError ? <p className="hz-field__error">{leaveError}</p>
            : <p className="hz-field__hint">The horse keeps training until then. The stay is paid for at least {NOTICE_DAYS} days from today (contract clause 9).</p>}
        </div>
        <WithdrawalBasis basis={basis} />

        <div className="hz-field">
          <label htmlFor="withdraw-reason">Reason</label>
          <textarea
            id="withdraw-reason" className="hz-textarea" rows={3} maxLength={REASON_MAX}
            value={reason} onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Moving the horse to another stable"
          />
          {fieldError ? <p className="hz-field__error">{fieldError}</p>
            : <p className="hz-field__hint">{reason.length}/{REASON_MAX}</p>}
        </div>

        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy}>
            {busy ? "Sending…" : "Request withdrawal"}
          </button>
        </div>
      </form>
    );
  } else {
    // Manager xem ngựa còn ở học viện, không có yêu cầu nào đang chờ.
    body = (
      <Row label="Status">
        <StatusChip tone={activity.tone} label={activity.label} title={activity.hint} />
      </Row>
    );
  }

  return (
    <Card title={withdrawn ? "Left the club" : "Leave the club"}>
      {dialog.element}
      {body}
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      {notice && !error && <p className="hz-muted hz-note" role="status">{notice}</p>}
      <ResultLine result={result} />
    </Card>
  );
}
