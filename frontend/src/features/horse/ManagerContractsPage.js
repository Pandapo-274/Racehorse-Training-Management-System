// src/features/horse/ManagerContractsPage.js
/*
 * Trang /manager/contracts (V49). Club Manager theo dõi hợp đồng gửi ngựa:
 *  - "Paid - receive the horse": chủ đã ký và trả tiền. NHẬN NGỰA ở đây = phần việc nút Approve
 *    cũ làm: cấp mã đăng ký (gợi ý + kiểm trùng khi gõ), xếp chuồng, chốt Head Trainer (chỉ HLV còn
 *    chỗ, điền sẵn HLV chủ ngựa xin), chấm BCS 1-9. Lúc này hồ sơ HORSE mới được tạo.
 *  - "Ended - follow up": hết hạn chưa gia hạn; ngựa không nhận giáo án mới.
 *  - "Waiting for the owner": chưa ký / chưa trả; Manager có thể rút lại lời mời.
 *  - Đang chạy và đã đóng.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { useAppDialog } from "../common/AppDialog";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import HorsePhoto from "./HorsePhoto";
import { formatDate, formatDateTime, listAvailableTrainers, toBcsGroup, BCS_GROUPS } from "./horseApi";
import { formatVnd } from "./horseRequestApi";
import { listContracts, receiveHorse, cancelContract, toContractStatus, stayLine } from "./contractApi";
import RegistrationCodeField from "./RegistrationCodeField";

const BCS_TEXT = {
  1: "Poor", 2: "Very thin", 3: "Thin", 4: "Moderately thin", 5: "Moderate",
  6: "Moderately fleshy", 7: "Fleshy", 8: "Fat", 9: "Extremely fat",
};

function ReceiveForm({ c, trainers, onDone }) {
  const navigate = useNavigate();
  const preferredAvailable = trainers.some((t) => t.userId === c.preferredTrainerId);
  const [form, setForm] = useState({
    registrationCode: "", stallNo: "",
    trainerId: preferredAvailable ? String(c.preferredTrainerId) : "",
    bcs: "", weight: c.weight ?? "", heightCm: c.heightCm ?? "", note: "",
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const overridesWish = c.preferredTrainerId && String(c.preferredTrainerId) !== form.trainerId;
  const group = toBcsGroup(form.bcs);

  const submit = async () => {
    const e = {};
    if (!form.registrationCode.trim()) e.registrationCode = "Enter the code the club assigns";
    if (!form.stallNo.trim()) e.stallNo = "Assign a stall";
    if (!form.bcs) e.bcs = "Score the body condition (1-9)";
    if (overridesWish && !form.note.trim()) e.note = `The owner asked for ${c.preferredTrainerName}. Say why`;
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setError("");
    try {
      await receiveHorse(c.contractId, form);
      onDone();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length) setErrors(err.fieldErrors);
      else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="hz-review__form">
      <div className="hz-form-grid">
        <RegistrationCodeField id={`code-${c.contractId}`} value={form.registrationCode}
                               onChange={(v) => setForm((f) => ({ ...f, registrationCode: v }))}
                               error={errors.registrationCode} autoSuggest />
        <div className="hz-field">
          <label htmlFor={`stall-${c.contractId}`}>Stall</label>
          <input id={`stall-${c.contractId}`} className="hz-mono" value={form.stallNo} maxLength={20}
                 onChange={set("stallNo")} />
          {errors.stallNo && <p className="hz-field__error">{errors.stallNo}</p>}
        </div>
        <div className="hz-field">
          <label htmlFor={`trainer-${c.contractId}`}>Head Trainer</label>
          {/* Review feedback 5: only trainers who can take another horse are listed. */}
          <select id={`trainer-${c.contractId}`} value={form.trainerId} onChange={set("trainerId")}>
            <option value="">— Assign later —</option>
            {trainers.map((t) => (
              <option key={t.userId} value={t.userId}>
                {t.fullName} ({t.managedHorseCount}/{t.maxHorses}){t.userId === c.preferredTrainerId ? " · owner's choice" : ""}
              </option>
            ))}
          </select>
          {errors.trainerId ? <p className="hz-field__error">{errors.trainerId}</p>
            : c.preferredTrainerId && !preferredAvailable
              ? <p className="hz-field__hint">{c.preferredTrainerName}, the owner's choice, is now full.</p>
              : trainers.length === 0
                ? <p className="hz-field__hint">Every Head Trainer is at full capacity. Raise a limit on the Staff page.</p>
                : null}
        </div>
        <div className="hz-field">
          <label htmlFor={`bcs-${c.contractId}`}>Body condition score (Henneke)</label>
          <select id={`bcs-${c.contractId}`} value={form.bcs} onChange={set("bcs")} aria-invalid={!!errors.bcs}>
            <option value="">Score 1-9</option>
            {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => <option key={n} value={n}>{n} - {BCS_TEXT[n]}</option>)}
          </select>
          {errors.bcs ? <p className="hz-field__error">{errors.bcs}</p>
            : group ? <p className="hz-field__hint"><StatusChip tone={BCS_GROUPS[group].tone} label={BCS_GROUPS[group].label} /></p>
            : <p className="hz-field__hint">Scored by eye and hand at six body sites. 4-6 is ideal.</p>}
        </div>
        <div className="hz-field">
          <label htmlFor={`weight-${c.contractId}`}>Weight on arrival (kg)</label>
          <input id={`weight-${c.contractId}`} type="number" min="30" max="1000" step="0.5"
                 value={form.weight} onChange={set("weight")} />
          {errors.weight && <p className="hz-field__error">{errors.weight}</p>}
        </div>
        <div className="hz-field">
          <label htmlFor={`height-${c.contractId}`}>Height on arrival (cm)</label>
          <input id={`height-${c.contractId}`} type="number" min="60" max="200" step="0.5"
                 value={form.heightCm} onChange={set("heightCm")} />
          {errors.heightCm && <p className="hz-field__error">{errors.heightCm}</p>}
        </div>
      </div>
      <div className="hz-field">
        <label htmlFor={`rnote-${c.contractId}`}>
          Note{overridesWish ? " (required: why not the owner's preferred trainer)" : ""}
        </label>
        <textarea id={`rnote-${c.contractId}`} className="hz-textarea" rows={2} maxLength={255}
                  value={form.note} onChange={set("note")} />
        {errors.note && <p className="hz-field__error">{errors.note}</p>}
      </div>
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      <div className="hz-form-footer">
        <button type="button" className="hz-btn" disabled={busy} onClick={submit}>
          {busy ? "Receiving…" : "Receive the horse"}
        </button>
      </div>
    </div>
  );
}

function ContractItem({ c, trainers, openId, onDone, onCancel }) {
  const [showText, setShowText] = useState(openId === c.contractId);
  const st = toContractStatus(c.status);
  const receivable = c.status === "PAID" && !c.renewsContractId;
  return (
    <li className="hz-review" id={`contract-${c.contractId}`}>
      <div className="hz-review__info">
        <div className="hz-review__head">
          <HorsePhoto horse={c} className="hz-photo--sm" />
          <div>
            <strong>{c.horseName}{c.renewsContractId ? " · renewal" : ""}</strong>
            <p>{c.ownerName} · contract #{c.contractId}</p>
            <p>{c.months} month{c.months === 1 ? "" : "s"} · {formatVnd(c.totalAmount)}
              {c.trainingStart && c.trainingEnd
                ? ` · training ${formatDate(c.trainingStart)} - ${formatDate(c.trainingEnd)}`
                : c.trainingWeeks ? ` · training ${c.trainingWeeks} weeks` : ""}</p>
            <p>{stayLine(c, formatDate) || `Sign and pay by ${formatDate(c.respondBy)}`}</p>
            {c.paidAt && <p>Paid {formatDateTime(c.paidAt)}{c.paymentRef ? ` · ref ${c.paymentRef}` : ""}</p>}
            {c.registrationCode && <p>{c.registrationCode} · stall {c.stallNo || "—"}</p>}
            {c.closedNote && <p className="hz-reject-note">{c.closedNote}</p>}
            <p><StatusChip tone={st.tone} label={st.label} /></p>
          </div>
        </div>
        <button type="button" className="hz-link" onClick={() => setShowText((v) => !v)}>
          {showText ? "Hide contract text" : "Show contract text"}
        </button>
        {showText && <pre className="hz-contract-text">{c.termsSnapshot}</pre>}
      </div>
      {receivable && <ReceiveForm c={c} trainers={trainers} onDone={onDone} />}
      {(c.status === "SENT" || c.status === "SIGNED") && (
        <div className="hz-review__form">
          <button type="button" className="hz-btn hz-btn--ghost" onClick={() => onCancel(c)}>
            Withdraw the offer
          </button>
        </div>
      )}
    </li>
  );
}

export default function ManagerContractsPage() {
  const navigate = useNavigate();
  const dialog = useAppDialog();
  const [search] = useSearchParams();
  const openId = Number(search.get("open")) || null;
  const [rows, setRows] = useState(null);
  const [trainers, setTrainers] = useState([]);
  const [error, setError] = useState("");

  const load = useCallback(() => {
    setError("");
    Promise.all([listContracts(), listAvailableTrainers().catch(() => [])])
      .then(([cs, ts]) => { setRows(cs); setTrainers(ts || []); })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
  }, [navigate]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (rows && openId) document.getElementById(`contract-${openId}`)?.scrollIntoView({ block: "start" });
  }, [rows, openId]);

  const withdraw = async (c) => {
    const reason = await dialog.prompt({
      title: `Withdraw the offer for ${c.horseName}?`,
      label: "Reason for the owner",
      defaultValue: "",
      confirmLabel: "Withdraw",
      validate: (v) => (v && v.trim() ? null : "Say why"),
      onSubmit: async (v) => { await cancelContract(c.contractId, v); },
    });
    if (reason !== null) load();
  };

  const group = (statuses, extra = () => true) => (rows || []).filter((c) => statuses.includes(c.status) && extra(c));
  const sections = [
    { title: "Paid - receive the horse", hint: "Assign code, stall, Head Trainer and BCS", items: group(["PAID"], (c) => !c.renewsContractId) },
    { title: "Ended - follow up", hint: "Not renewed: renewal or withdrawal", items: group(["EXPIRED"]) },
    { title: "Waiting for the owner", hint: "Not signed or not paid yet", items: group(["SENT", "SIGNED"]) },
    { title: "At the club", hint: "Running stays", items: group(["ACTIVE"]) },
    { title: "Closed", hint: "Ended, lapsed or withdrawn", items: group(["ENDED", "VOID"]) },
  ];

  return (
    <HorseShell title="Contracts" subtitle="Boarding contracts: signing, payment, arrival and end of stay">
      {error && <StateBlock kind="error" title={error} onRetry={load} />}
      {!rows && !error && <StateBlock title="Loading…" />}
      {rows && sections.map((s) => (
        <Card key={s.title} title={s.title} hint={`${s.items.length} · ${s.hint}`}>
          {s.items.length === 0 ? (
            <p className="hz-empty">Nothing here.</p>
          ) : (
            <ul className="hz-list">
              {s.items.map((c) => (
                <ContractItem key={c.contractId} c={c} trainers={trainers} openId={openId}
                              onDone={load} onCancel={withdraw} />
              ))}
            </ul>
          )}
        </Card>
      ))}
      {dialog.element}
    </HorseShell>
  );
}
