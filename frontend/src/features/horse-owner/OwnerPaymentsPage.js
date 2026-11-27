// src/features/horse-owner/OwnerPaymentsPage.js
/*
 * Trang /horse-owner/payments (V57): chủ ngựa trả online các khoản tính riêng ngoài tiền tháng,
 * gồm y tế (MEDICAL) và lệ phí giải, phí khác (OTHER). Tiền tháng (hợp đồng) đã gồm chuồng,
 * ăn uống, chăm sóc và huấn luyện.
 *
 * Bấm Pay -> backend chốt danh sách khoản và tạo link payOS -> chuyển sang trang payOS (VietQR).
 * Trả xong payOS đưa về trang này; trang hỏi lại backend (/sync) - backend hỏi payOS - nên vẫn thấy
 * "Paid" khi chạy local, nơi webhook không tới được.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock } from "../horse/HorseShell";
import { formatDate, formatDateTime } from "../horse/horseApi";
import { formatVnd } from "../horse/horseRequestApi";
import { getCostPayments, startCostPayment, syncCostPayments, toCostLabel } from "./horseOwnerApi";

export default function OwnerPaymentsPage() {
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const cameBack = search.has("orderCode") || search.has("status") || search.has("cancel");
  const cancelled = search.get("cancel") === "true";

  const load = useCallback(async () => {
    setError("");
    try {
      let d = await getCostPayments();
      // A payment is waiting: ask payOS through the backend instead of waiting for the webhook.
      if (d.open?.orderCode) {
        try {
          d = await syncCostPayments();
        } catch (err) {
          if (handleAuthError(err, navigate)) return;
          d = { ...d, syncProblem: err.message || "Could not reach payOS" };
        }
      }
      setData(d);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
    }
  }, [navigate]);

  useEffect(() => { load(); }, [load]);

  const pay = async () => {
    setBusy(true);
    setActionError("");
    try {
      const res = await startCostPayment();
      if (res.alreadyPaid || !res.checkoutUrl) {
        await load();
        setBusy(false);
        return;
      }
      window.location.assign(res.checkoutUrl); // payOS hosted page (VietQR)
    } catch (err) {
      setBusy(false);
      if (handleAuthError(err, navigate)) return;
      setActionError(err.message);
    }
  };

  const check = async () => {
    setBusy(true);
    setActionError("");
    try {
      setData(await syncCostPayments());
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setActionError(err.message);
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return <HorseShell title="Payments"><StateBlock kind="error" title={error} onRetry={load} /></HorseShell>;
  }
  if (!data) return <HorseShell title="Payments"><StateBlock title="Loading…" /></HorseShell>;

  const items = data.items || [];
  const lastPaid = (data.recent || [])[0];

  return (
    <HorseShell
      title="Payments"
      subtitle="Medical costs and race entry fees, billed on top of the monthly boarding fee"
    >
      {cameBack && data.open && (
        <StateBlock title={cancelled ? "Payment cancelled" : "Waiting for payOS to confirm"}>
          {cancelled
            ? "Nothing was charged. You can pay again below."
            : "If you completed the transfer, press Check payment in a few seconds."}
        </StateBlock>
      )}
      {cameBack && !data.open && !cancelled && lastPaid && (
        <div className="hz-alert hz-alert--good" style={{ marginBottom: 16 }}>
          Payment received: {formatVnd(lastPaid.paidAmount)} for {lastPaid.itemCount} cost item
          {lastPaid.itemCount === 1 ? "" : "s"}.
        </div>
      )}

      <Card
        title="To pay"
        hint="The monthly boarding fee already covers the stall, feed, daily care and training. These costs come on top of it."
      >
        {items.length === 0 ? (
          <p className="hz-empty">Nothing to pay right now.</p>
        ) : (
          <div className="hz-table-wrap">
            <table className="hz-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Horse</th>
                  <th>Item</th>
                  <th>Note</th>
                  <th style={{ textAlign: "right" }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {items.map((it) => (
                  <tr key={it.expenseId}>
                    <td className="hz-muted">{formatDate(it.expenseDate)}</td>
                    <td className="hz-name">{it.horseName}</td>
                    <td>{toCostLabel(it.category)}</td>
                    <td className="hz-muted">{it.note || "—"}</td>
                    <td style={{ textAlign: "right" }}>{formatVnd(it.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {data.heldCount > 0 && (
          <p className="hz-muted hz-note">
            {data.heldCount} disputed item{data.heldCount === 1 ? "" : "s"} ({formatVnd(data.heldTotal)}) wait
            for the club's answer and are not charged yet.
          </p>
        )}
        {!data.onlinePayment && (
          <p className="hz-field__error">
            Online payment is not set up on this server yet. Ask the club to turn it on, then come back to pay.
          </p>
        )}
        {data.syncProblem && !actionError && (
          <p className="hz-form-error" role="alert">
            Could not check the payment with payOS just now ({data.syncProblem}). Press Check payment to try again.
          </p>
        )}
        {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}

        <div className="hz-form-footer">
          {items.length > 0 && data.onlinePayment && (
            <button type="button" className="hz-btn" disabled={busy} onClick={pay}>
              {busy ? "Opening payOS…" : `Pay ${formatVnd(data.total)}`}
            </button>
          )}
          {data.open?.orderCode && data.onlinePayment && (
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={check}>
              Check payment
            </button>
          )}
        </div>
      </Card>

      <Card title="Paid" hint="Your latest online payments for these costs">
        {(data.recent || []).length === 0 ? (
          <p className="hz-empty">No online payment yet.</p>
        ) : (
          <ul className="hz-list">
            {data.recent.map((p) => (
              <li key={p.paymentId}>
                <div>
                  <strong>{formatVnd(p.paidAmount)}</strong>
                  <p>
                    {p.itemCount} cost item{p.itemCount === 1 ? "" : "s"} · {formatDateTime(p.paidAt)}
                    {p.paymentRef ? ` · ref ${p.paymentRef}` : ""}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </HorseShell>
  );
}
