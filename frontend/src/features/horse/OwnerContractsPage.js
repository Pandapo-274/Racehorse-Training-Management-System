// src/features/horse/OwnerContractsPage.js
/*
 * Chủ ngựa: /horse-owner/contracts (danh sách) và /horse-owner/contracts/:id (một hợp đồng).
 *
 * Các bước (V49): đọc -> ký online (gõ đúng họ tên + tích đồng ý) -> trả tiền qua payOS ->
 * mang ngựa tới, CLB nhận ngựa -> thời hạn bắt đầu. Gần hết hạn thì gia hạn ở đây.
 *
 * payOS: bấm "Pay" -> backend tạo link -> chuyển sang trang payOS (quét VietQR bằng app ngân hàng).
 * Trả xong payOS đưa về đúng trang này; trang hỏi lại backend (/sync) - backend hỏi payOS - nên
 * vẫn thấy "Paid" ngay cả khi webhook chưa tới (chạy local, payOS không gọi được localhost).
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { useAppDialog } from "../common/AppDialog";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import HorsePhoto from "./HorsePhoto";
import { formatDate, formatDateTime } from "./horseApi";
import { formatVnd, CONTRACT_MONTH_OPTIONS } from "./horseRequestApi";
import {
  listContracts, getContract, signContract, payContract, syncContract, cancelContract, renewContract,
  toContractStatus, stayLine,
} from "./contractApi";

export default function OwnerContractsPage() {
  const { id } = useParams();
  return id ? <ContractDetail id={Number(id)} /> : <ContractList />;
}

function ContractList() {
  const navigate = useNavigate();
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const ctrl = new AbortController();
    listContracts(ctrl.signal).then(setRows).catch((err) => {
      if (err.name === "AbortError") return;
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
    });
    return () => ctrl.abort();
  }, [navigate]);

  return (
    <HorseShell title="Contracts" subtitle="How long each horse stays at the club, and what you paid">
      {error && <StateBlock kind="error" title={error} />}
      {!rows && !error && <StateBlock title="Loading…" />}
      {rows && (
        <Card title="Your contracts" hint={`${rows.length} so far`}>
          {rows.length === 0 ? (
            <p className="hz-empty">No contract yet. A contract arrives when the club approves a registration.</p>
          ) : (
            <ul className="hz-list">
              {rows.map((c) => {
                const st = toContractStatus(c.status);
                return (
                  <li key={c.contractId}>
                    <HorsePhoto horse={c} className="hz-photo--sm" />
                    <div>
                      <strong>{c.horseName}{c.renewsContractId ? " · renewal" : ""}</strong>
                      <p>{c.months} month{c.months === 1 ? "" : "s"} · {formatVnd(c.totalAmount)}</p>
                      <p>{stayLine(c, formatDate) || `Contract #${c.contractId}`}</p>
                    </div>
                    <div className="hz-row-action">
                      <StatusChip tone={st.tone} label={st.label} />
                      <button type="button" className="hz-btn" style={{ marginLeft: 8 }}
                              onClick={() => navigate(`/horse-owner/contracts/${c.contractId}`)}>
                        {c.status === "SENT" ? "Read and sign" : c.status === "SIGNED" ? "Pay" : "Open"}
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      )}
    </HorseShell>
  );
}

function ContractDetail({ id }) {
  const navigate = useNavigate();
  const dialog = useAppDialog();
  const [search] = useSearchParams();
  const user = getUser();
  const [c, setC] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [fieldErrors, setFieldErrors] = useState({});
  const [fullName, setFullName] = useState("");
  const [agree, setAgree] = useState(false);
  const [renewMonths, setRenewMonths] = useState("1");
  const cameBackFromPayOS = search.has("orderCode") || search.has("status") || search.has("cancel");

  const load = useCallback(async () => {
    setError("");
    try {
      let row = await getContract(id);
      // Back from payOS, or still waiting: ask payOS through the backend instead of waiting for the webhook.
      if (row.status === "SIGNED" && row.orderCode) {
        row = await syncContract(id);
      }
      setC(row);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
    }
  }, [id, navigate]);

  useEffect(() => { load(); }, [load]);

  const act = async (fn) => {
    setBusy(true);
    setActionError("");
    setFieldErrors({});
    try {
      await fn();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) setFieldErrors(err.fieldErrors);
      setActionError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const sign = () => act(async () => {
    const e = {};
    if (!fullName.trim()) e.fullName = "Type your full name to sign";
    if (!agree) e.agree = "Tick the box to confirm you have read and agree";
    if (Object.keys(e).length) { setFieldErrors(e); return; }
    setC(await signContract(id, { fullName, agree, termsSha256: c.termsSha256 }));
  });

  const pay = () => act(async () => {
    const res = await payContract(id);
    if (res.alreadyPaid || !res.checkoutUrl) {
      await load();
      return;
    }
    window.location.assign(res.checkoutUrl); // payOS hosted page (VietQR)
  });

  const decline = async () => {
    const reason = await dialog.prompt({
      title: "Decline this contract?",
      label: "Reason (optional)",
      defaultValue: "",
      confirmLabel: "Decline",
      validate: () => null, // the reason may stay empty
    });
    if (reason === null) return;
    act(async () => setC(await cancelContract(id, reason)));
  };

  const renew = () => act(async () => {
    const created = await renewContract(c.horseId, renewMonths);
    navigate(`/horse-owner/contracts/${created.contractId}`);
  });

  if (error) return <HorseShell title="Contract"><StateBlock kind="error" title={error} onRetry={load} /></HorseShell>;
  if (!c) return <HorseShell title="Contract"><StateBlock title="Loading…" /></HorseShell>;

  const st = toContractStatus(c.status);
  const canRenew = (c.status === "ACTIVE" || c.status === "EXPIRED") && c.horseId;

  return (
    <HorseShell
      title={`Contract #${c.contractId}`}
      subtitle={`${c.horseName}${c.renewsContractId ? " · renewal" : ""}`}
      actions={
        <button type="button" className="hz-btn hz-btn--ghost" onClick={() => navigate("/horse-owner/contracts")}>
          All contracts
        </button>
      }
    >
      {cameBackFromPayOS && c.status === "SIGNED" && (
        <StateBlock title={search.get("cancel") === "true" ? "Payment cancelled" : "Waiting for payOS to confirm"}>
          {search.get("cancel") === "true"
            ? "Nothing was charged. You can pay again below."
            : "If you completed the transfer, press Check payment in a few seconds."}
        </StateBlock>
      )}

      <Card title="Summary" actions={<StatusChip tone={st.tone} label={st.label} />}>
        <div className="hz-basis">
          <div className="hz-basis__item"><span>Stay</span><strong>{c.months} month{c.months === 1 ? "" : "s"}</strong></div>
          <div className="hz-basis__item"><span>Training asked</span><strong>
            {c.trainingStart && c.trainingEnd
              ? `${formatDate(c.trainingStart)} - ${formatDate(c.trainingEnd)}${c.trainingWeeks ? ` (${c.trainingWeeks} weeks)` : ""}`
              : c.trainingWeeks ? `${c.trainingWeeks} weeks` : "—"}
          </strong></div>
          <div className="hz-basis__item"><span>Total</span><strong>{formatVnd(c.totalAmount)}</strong></div>
          <div className="hz-basis__item"><span>Sign and pay by</span><strong>{formatDate(c.respondBy)}</strong></div>
          {c.endDate && <div className="hz-basis__item"><span>Stay</span><strong>{stayLine(c, formatDate)}</strong></div>}
          {c.signedAt && <div className="hz-basis__item"><span>Signed</span><strong>{c.signedName} · {formatDateTime(c.signedAt)}</strong></div>}
          {c.paidAt && <div className="hz-basis__item"><span>Paid</span><strong>{formatVnd(c.paidAmount)} · {formatDateTime(c.paidAt)}{c.paymentRef ? ` · ref ${c.paymentRef}` : ""}</strong></div>}
          {c.registrationCode && <div className="hz-basis__item"><span>Code / stall</span><strong>{c.registrationCode} · {c.stallNo || "—"}</strong></div>}
        </div>
        {c.closedNote && <p className="hz-reject-note">{c.closedNote}</p>}
      </Card>

      <Card title="Contract text" hint={`SHA-256 ${c.termsSha256.slice(0, 16)}…`}>
        <pre className="hz-contract-text">{c.termsSnapshot}</pre>
      </Card>

      {c.status === "SENT" && (
        <Card title="Sign online" hint="Type your full name exactly as on your account">
          <div className="hz-field">
            <label htmlFor="fullName">Full name</label>
            <input id="fullName" value={fullName} onChange={(e) => setFullName(e.target.value)}
                   placeholder={user?.fullName || ""} maxLength={100} autoComplete="name"
                   aria-invalid={!!fieldErrors.fullName} />
            {fieldErrors.fullName && <p className="hz-field__error">{fieldErrors.fullName}</p>}
          </div>
          <label className="hz-check">
            <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
            I have read this contract and agree to it
          </label>
          {fieldErrors.agree && <p className="hz-field__error">{fieldErrors.agree}</p>}
          {actionError && !Object.keys(fieldErrors).length && <p className="hz-form-error" role="alert">{actionError}</p>}
          <div className="hz-form-footer">
            <button type="button" className="hz-btn" disabled={busy} onClick={sign}>Sign contract</button>
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={decline}>Decline</button>
          </div>
        </Card>
      )}

      {c.status === "SIGNED" && (
        <Card title="Pay" hint="payOS: scan the VietQR code with your banking app">
          <p>Amount: <strong>{formatVnd(c.totalAmount)}</strong>. The transfer note is filled in by payOS; do not change it.</p>
          {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}
          <div className="hz-form-footer">
            <button type="button" className="hz-btn" disabled={busy} onClick={pay}>
              {busy ? "Opening payOS…" : `Pay ${formatVnd(c.totalAmount)}`}
            </button>
            {c.orderCode && (
              <button type="button" className="hz-btn hz-btn--ghost" disabled={busy}
                      onClick={() => act(async () => setC(await syncContract(id)))}>
                Check payment
              </button>
            )}
            <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={decline}>Decline</button>
          </div>
        </Card>
      )}

      {c.status === "PAID" && !c.renewsContractId && (
        <StateBlock title="Paid - bring the horse in">
          The stay starts the day the club receives the horse. The club assigns the registration code,
          stall and Head Trainer then.
        </StateBlock>
      )}

      {canRenew && (
        <Card title="Renew the stay" hint="A new contract following on from this one. Nothing renews by itself.">
          <div className="hz-inline">
            <select value={renewMonths} onChange={(e) => setRenewMonths(e.target.value)} aria-label="Months">
              {CONTRACT_MONTH_OPTIONS.map((m) => <option key={m} value={m}>{m} month{m === 1 ? "" : "s"}</option>)}
            </select>
            <button type="button" className="hz-btn" disabled={busy} onClick={renew}>Renew</button>
          </div>
          {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}
        </Card>
      )}
      {dialog.element}
    </HorseShell>
  );
}
