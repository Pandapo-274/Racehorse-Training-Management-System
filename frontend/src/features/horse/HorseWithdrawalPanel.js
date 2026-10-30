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
import { canRequestWithdrawal, formatDateTime, isPedigreeOnly, isWithdrawn, toActivity } from "./horseApi";
import {
  listWithdrawals, submitWithdrawal, cancelWithdrawal, toWithdrawalStatus, plural,
} from "./withdrawalApi";

const REASON_MAX = 500;

function Row({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

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
  const [fieldError, setFieldError] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");

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

  if (isPedigreeOnly(horse)) return null;
  // Ngựa còn ở học viện mà người xem không phải chủ hay manager: không có gì để nói.
  if (!withdrawn && !mayLoad) return null;

  const pending = (rows || []).find((r) => r.status === "PENDING");
  const approved = (rows || []).find((r) => r.status === "APPROVED");
  const lastClosed = (rows || []).find((r) => r.status === "REJECTED");
  const activity = toActivity(horse.activityStatus);

  const run = async (fn, okMessage) => {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await fn();
      setNotice(okMessage);
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors?.reason) setFieldError(err.fieldErrors.reason);
      else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

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
    setFieldError("");
    run(async () => {
      await submitWithdrawal(horse.horseId, text);
      setReason("");
    }, "Request sent. The club manager will review it.");
  };

  const cancel = () => {
    if (!pending) return;
    run(() => cancelWithdrawal(pending.withdrawalId), "Request cancelled.");
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
        <Row label="If approved">
          {plural(pending.scheduledSessions, "scheduled session")} cancelled ·{" "}
          {plural(pending.openPlans, "training plan")} ended · upcoming tasks and race entries
          cancelled · stall released
        </Row>
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
      {body}
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      {notice && !error && <p className="hz-muted hz-note" role="status">{notice}</p>}
    </Card>
  );
}
