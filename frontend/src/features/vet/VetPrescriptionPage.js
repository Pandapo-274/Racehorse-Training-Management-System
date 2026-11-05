// src/features/vet/VetPrescriptionPage.js
// Màn Prescriptions: đơn thuốc và thống kê chi phí y tế trên toàn đàn.

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
  listMedicalExpenses,
  listMedicineSupplies,
  careTypeLabel,
  formatDate,
  normalizeDate,
  todayInputValue,
  describeError,
} from "./vetApi";
import "./vet-care.css";

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
function formatExpenseNote(note) {
  if (!note) return "";
  return note.replace(/\(Qty:\s*(\d+(?:\.\d+)?)\s*@\s*\d+\)/gi, (match, q) => {
    if (note.includes("Prednisolone") || note.includes("Firocoxib")) return `(${q} viên)`;
    if (note.includes("Phenylbutazone") || note.includes("TMS") || note.includes("GastroGard") || note.includes("Omeprazole") || note.includes("Povidone") || note.includes("Ivermectin")) return `(${q} tuýp)`;
    if (note.includes("Flunixin") || note.includes("Dexamethasone") || note.includes("Amoxicillin") || note.includes("Gentamicin") || note.includes("Penicillin") || note.includes("Xylazine")) return `(${q} lọ)`;
    if (note.includes("Probiotic")) return `(${q} gói)`;
    if (note.includes("Ventipulmin") || note.includes("Clenbuterol")) return `(${q} chai)`;
    if (note.includes("Glucosamine")) return `(${q} hộp)`;
    if (note.includes("Hyaluronic")) return `(${q} ống)`;
    if (note.includes("Electrolytes") || note.includes("điện giải")) return `(${q} lít)`;
    return `(${q})`;
  });
}

const formatVND = (v) => `${Number(v || 0).toLocaleString("vi-VN")} đ`;

const treatmentTitle = (t) => t.medicineName || t.instruction || "Treatment";
const dose = (t) => [t.dosage, t.frequency].filter(Boolean).join(" · ") || "—";
const period = (t) =>
  `${formatDate(t.startDate)} → ${t.endDate ? formatDate(t.endDate) : "until review"}`;

// 17 loại thuốc phổ biến cho các bệnh lý của ngựa
const COMMON_MEDICINES = [
  { supplyName: "Phenylbutazone (Bute)", unit: "tuýp", unitPrice: 180000, category: "Kháng viêm & Giảm đau" },
  { supplyName: "Flunixin Meglumine (Banamine)", unit: "lọ", unitPrice: 250000, category: "Đau bụng Colic & Hạ sốt" },
  { supplyName: "Firocoxib (Equioxx)", unit: "viên", unitPrice: 45000, category: "Viêm khớp mãn tính" },
  { supplyName: "Dexamethasone", unit: "lọ", unitPrice: 120000, category: "Chống dị ứng & Viêm cấp" },
  { supplyName: "Amoxicillin Trihydrate", unit: "lọ", unitPrice: 210000, category: "Kháng sinh nhiễm trùng" },
  { supplyName: "Gentamicin Sulfate", unit: "lọ", unitPrice: 320000, category: "Kháng sinh hô hấp & khớp" },
  { supplyName: "Trimethoprim-Sulfadiazine (TMS)", unit: "tuýp", unitPrice: 280000, category: "Kháng sinh vết thương" },
  { supplyName: "Penicillin G Procaine", unit: "lọ", unitPrice: 195000, category: "Kháng sinh tiêm bắp" },
  { supplyName: "Omeprazole (GastroGard)", unit: "tuýp", unitPrice: 350000, category: "Viêm loét dạ dày" },
  { supplyName: "Dung dịch điện giải (Electrolytes)", unit: "lít", unitPrice: 95000, category: "Bù nước & Điện giải" },
  { supplyName: "Men vi sinh tiêu hóa (Probiotic)", unit: "gói", unitPrice: 65000, category: "Trợ lực tiêu hóa" },
  { supplyName: "Clenbuterol (Ventipulmin)", unit: "chai", unitPrice: 420000, category: "Giãn phế quản & Trị ho" },
  { supplyName: "Prednisolone", unit: "viên", unitPrice: 15000, category: "Chống viêm đường uống" },
  { supplyName: "Glucosamine + Chondroitin", unit: "hộp", unitPrice: 650000, category: "Bổ trợ sụn khớp" },
  { supplyName: "Hyaluronic Acid (Tiêm khớp)", unit: "ống", unitPrice: 550000, category: "Bôi trơn dịch khớp" },
  { supplyName: "Thuốc mỡ sát trùng Povidone", unit: "tuýp", unitPrice: 50000, category: "Sát trùng vết thương ngoài" },
  { supplyName: "Ivermectin (Tẩy giun sán)", unit: "tuýp", unitPrice: 160000, category: "Ký sinh trùng đường ruột" },
];

export default function VetPrescriptionPage() {
  const navigate = useNavigate();

  const [tab, setTab] = useState("prescriptions");
  const [active, setActive] = useState(null);
  const [expenses, setExpenses] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");

  const [history, setHistory] = useState(null);
  const [prescribing, setPrescribing] = useState(false);
  const [ending, setEnding] = useState(null);
  const [notice, setNotice] = useState("");

  // Load active treatments
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

  // Load medical expenses when tab is expenses
  useEffect(() => {
    if (tab !== "expenses") return;
    const ctrl = new AbortController();
    listMedicalExpenses(ctrl.signal)
      .then((data) => setExpenses(data?.items || []))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
      });
    return () => ctrl.abort();
  }, [tab, navigate, reload]);

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

  const expenseRows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (expenses || []).filter(
      (e) =>
        !q ||
        e.horseName.toLowerCase().includes(q) ||
        String(e.note || "").toLowerCase().includes(q) ||
        String(e.recordedByName || "").toLowerCase().includes(q)
    );
  }, [expenses, query]);

  const totalExpenseAmount = useMemo(
    () => (expenses || []).reduce((acc, e) => acc + (Number(e.amount) || 0), 0),
    [expenses]
  );

  const handleSaved = (message) => {
    setPrescribing(false);
    setEnding(null);
    setNotice(message);
    setReload((n) => n + 1);
  };

  return (
    <VetShell
      active="prescriptions"
      title="Prescriptions & Medical Care"
      subtitle="Treatments, prescriptions and medical expenses across the herd"
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

      {/* Tabs */}
      <div className="vx-tab-segment" role="tablist">
        <button
          type="button"
          className="vx-tab-btn"
          aria-selected={tab === "prescriptions"}
          onClick={() => setTab("prescriptions")}
        >
          Prescriptions ({active ? active.length : 0})
        </button>
        <button
          type="button"
          className="vx-tab-btn"
          aria-selected={tab === "expenses"}
          onClick={() => setTab("expenses")}
        >
          Medical Expenses ({expenses ? expenses.length : "..."})
        </button>
      </div>

      {!error && !active && <VetState title="Loading prescriptions…" />}

      {tab === "prescriptions" && active && (
        <VetCard
          title="Active prescriptions"
          hint={`${plural(rows.length, "treatment")} active or scheduled`}
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

      {tab === "expenses" && (
        <VetCard
          title="Medical Expenses & Supply Billing"
          hint={`Total ${formatVND(totalExpenseAmount)} recorded and automatically dispatched to Manager & Owners`}
          tone="gold"
          actions={
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
          }
        >
          {!expenses ? (
            <VetState title="Loading medical expenses…" />
          ) : expenseRows.length === 0 ? (
            <div className="vet-inline-state">No medical expenses recorded yet.</div>
          ) : (
            <div className="vc-table-wrap">
              <table className="vc-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Horse</th>
                    <th>Treatment & Medicine Detail</th>
                    <th>Amount</th>
                    <th>Status</th>
                    <th>Recorded by</th>
                  </tr>
                </thead>
                <tbody>
                  {expenseRows.map((e) => (
                    <tr key={e.expenseId}>
                      <td className="vc-muted">{formatDate(e.expenseDate)}</td>
                      <td><strong>{e.horseName}</strong></td>
                      <td>{formatExpenseNote(e.note) || "Medical prescription"}</td>
                      <td style={{ color: "#2e7d32", fontWeight: 700 }}>
                        {formatVND(e.amount)}
                      </td>
                      <td>
                        <span className="vc-pill vc-pill--green" title="Billed to horse owner & club manager">
                          Sent to Owner & Manager
                        </span>
                      </td>
                      <td className="vc-muted">{e.recordedByName || "Veterinarian"}</td>
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
  const today = todayInputValue();

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
              {items.map((t) => {
                const endIso = normalizeDate(t.endDate);
                const isEnded = Boolean(
                  t.isActive === false ||
                  t.active === false ||
                  (endIso && endIso < today)
                );
                return (
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
                      <span className={`vc-pill vc-pill--${isEnded ? "muted" : "green"}`}>
                        {isEnded ? "Ended" : "Active"}
                      </span>
                    </td>
                  </tr>
                );
              })}
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
        <h2 id="vc-end-title">End treatment today?</h2>
        <p className="vx-lead">
          Set today as the end date for <strong>{treatmentTitle(treatment)}</strong> on{" "}
          <strong>{treatment.horseName}</strong>.
        </p>

        <VetError error={error} />

        <p className="vc-hint">
          The treatment stays active until the end of today. The prescription stays in the horse’s history; it is
          never deleted.
        </p>

        <div className="vx-actions">
          <button type="button" className="vx-btn vx-btn--red" disabled={busy} onClick={submit}>
            {busy ? "Ending…" : "End treatment today"}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Keep running
          </button>
        </div>
      </div>
    </div>
  );
}

/* ---------- hộp thoại kê đơn mới ---------- */

function PrescribeDialog({ onClose, onDone }) {
  const navigate = useNavigate();

  const [horses, setHorses] = useState(null);
  const [horseId, setHorseId] = useState("");
  const [records, setRecords] = useState(null);
  const [recordId, setRecordId] = useState("");

  // Catalog: kết hợp thuốc từ DB và 17 loại thuốc chuẩn
  const [medCatalog, setMedCatalog] = useState(COMMON_MEDICINES);

  // Form input để chọn từng thuốc
  const [chosenMedKey, setChosenMedKey] = useState("");
  const [customMed, setCustomMed] = useState("");
  const [doseQty, setDoseQty] = useState("");
  const [doseUnit, setDoseUnit] = useState("");
  const [frequency, setFrequency] = useState("Twice daily");
  const [instruction, setInstruction] = useState("");

  // Danh sách thuốc đã thêm vào đơn (Selected medicines)
  const [selectedMeds, setSelectedMeds] = useState([]);

  // Thời gian áp dụng
  const [startDate, setStartDate] = useState(todayInputValue());
  const [endDate, setEndDate] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [fieldNotice, setFieldNotice] = useState("");

  // Load horses
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

  // Load medicines catalog từ backend và merge với 17 loại chuẩn
  useEffect(() => {
    const ctrl = new AbortController();
    listMedicineSupplies(ctrl.signal)
      .then((data) => {
        const fromDb = data || [];
        // Map kết hợp DB và COMMON_MEDICINES
        const merged = COMMON_MEDICINES.map((cm) => {
          const found = fromDb.find(
            (s) =>
              s.supplyName.toLowerCase().includes(cm.supplyName.split(" ")[0].toLowerCase()) ||
              cm.supplyName.toLowerCase().includes(s.supplyName.toLowerCase())
          );
          if (found) {
            return {
              ...cm,
              supplyId: found.supplyId,
              unit: found.unit || cm.unit,
              unitPrice: found.unitPrice || cm.unitPrice,
              currentQty: found.currentQty != null ? Number(found.currentQty) : 0,
            };
          }
          return { ...cm, currentQty: 0 };
        });

        // Bổ sung các thuốc từ DB chưa có trong COMMON_MEDICINES
        for (const s of fromDb) {
          if (!merged.some((m) => m.supplyName.toLowerCase() === s.supplyName.toLowerCase())) {
            merged.push({
              supplyId: s.supplyId,
              supplyName: s.supplyName,
              unit: s.unit || "unit",
              unitPrice: s.unitPrice || 0,
              currentQty: s.currentQty != null ? Number(s.currentQty) : 0,
              category: "Other",
            });
          }
        }
        setMedCatalog(merged);
      })
      .catch(() => {});
    return () => ctrl.abort();
  }, []);

  // Load medical records khi chọn ngựa
  useEffect(() => {
    setRecords(null);
    setRecordId("");
    if (!horseId) return undefined;
    const ctrl = new AbortController();
    listMedicalRecords(horseId, ctrl.signal)
      .then((list) => {
        setRecords(list);
        if (list.length > 0) setRecordId(String(list[0].recordId));
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError({ message: err.message });
      });
    return () => ctrl.abort();
  }, [horseId, navigate]);

  const horseName = horses?.find((h) => String(h.horseId) === horseId)?.horseName;

  // Khi chọn 1 thuốc trong dropdown: tự động điền đơn vị của thuốc đó
  const handleDropdownChange = (key) => {
    setChosenMedKey(key);
    setFieldNotice("");
    if (!key) {
      setDoseUnit("");
      return;
    }
    const item = medCatalog.find((m) => m.supplyName === key);
    if (item) {
      if (item.unit) setDoseUnit(item.unit);
      if (!doseQty) setDoseQty("1");
      if (item.currentQty <= 0) {
        setFieldNotice(`"${item.supplyName}" is currently out of stock (0 ${item.unit}). Cannot prescribe.`);
      }
    }
  };

  // Nút "+ Thêm vào đơn thuốc"
  const handleAddMedicine = () => {
    setFieldNotice("");
    const medName = chosenMedKey || customMed.trim();
    if (!medName) {
      setFieldNotice("Please choose or enter a medicine before adding.");
      return;
    }
    const qty = Number(doseQty) > 0 ? Number(doseQty) : 1;
    const catalogItem = medCatalog.find((m) => m.supplyName === chosenMedKey);
    const unit = doseUnit.trim() || catalogItem?.unit || "liều";
    const unitPrice = catalogItem?.unitPrice || 0;

    // Ràng buộc tồn kho: Thuốc phải còn hàng và đủ số lượng trong kho
    const targetItem = catalogItem || medCatalog.find((m) =>
      m.supplyName.toLowerCase() === medName.toLowerCase() ||
      m.supplyName.toLowerCase().startsWith(medName.toLowerCase())
    );

    if (targetItem) {
      if (targetItem.currentQty <= 0) {
        setFieldNotice(`"${targetItem.supplyName}" is out of stock (0 ${targetItem.unit}). Cannot add to prescription.`);
        return;
      }
      if (qty > targetItem.currentQty) {
        setFieldNotice(`Prescribed quantity (${qty} ${targetItem.unit}) exceeds available stock (${targetItem.currentQty} ${targetItem.unit}).`);
        return;
      }
    }

    const newItem = {
      supplyId: targetItem?.supplyId || null,
      name: targetItem?.supplyName || medName,
      unit,
      unitPrice,
      quantity: qty,
      dosage: `${qty} ${unit}`,
      frequency: frequency.trim() || "Twice daily",
      instruction: instruction.trim() || null,
    };

    setSelectedMeds((prev) => [...prev, newItem]);

    // Reset lại ô chọn để bác sĩ có thể thêm thuốc tiếp theo
    setChosenMedKey("");
    setCustomMed("");
    setDoseQty("");
    setDoseUnit("");
    setInstruction("");
  };

  const removeMed = (index) => {
    setSelectedMeds((prev) => prev.filter((_, i) => i !== index));
  };

  // Submit toàn bộ đơn thuốc đã chọn
  const submit = async () => {
    setError(null);

    const problems = [];
    if (!horseId) problems.push("Choose a horse.");
    else if (!recordId) problems.push("Choose the examination this prescription belongs to.");

    if (selectedMeds.length === 0 && !chosenMedKey && !customMed.trim()) {
      problems.push("Add at least 1 medicine to the prescription.");
    }
    if (!startDate) problems.push("Enter the start date.");
    if (startDate && endDate && endDate < startDate) {
      problems.push("The end date must be on or after the start date.");
    }
    if (problems.length) {
      setError({ message: problems[0], details: problems.slice(1) });
      return;
    }

    // Nếu có thuốc đang nhập dở chưa bấm Thêm mà bấm Save luôn, tự động thêm vào
    let finalMeds = [...selectedMeds];
    const pendingName = chosenMedKey || customMed.trim();
    if (finalMeds.length === 0 && pendingName) {
      const catalogItem = medCatalog.find((m) => m.supplyName === chosenMedKey);
      const qty = Number(doseQty) > 0 ? Number(doseQty) : 1;
      const unit = doseUnit.trim() || catalogItem?.unit || "liều";
      finalMeds.push({
        supplyId: catalogItem?.supplyId || null,
        name: pendingName,
        unit,
        unitPrice: catalogItem?.unitPrice || 0,
        quantity: qty,
        dosage: `${qty} ${unit}`,
        frequency: frequency.trim() || "Twice daily",
        instruction: instruction.trim() || null,
      });
    }

    // Ràng buộc tồn kho: Kiểm tra tất cả thuốc trước khi gửi yêu cầu lên server
    for (const item of finalMeds) {
      const targetItem = medCatalog.find((m) =>
        (item.supplyId && m.supplyId === item.supplyId) ||
        m.supplyName.toLowerCase() === item.name.toLowerCase() ||
        m.supplyName.toLowerCase().startsWith(item.name.toLowerCase())
      );
      if (targetItem) {
        if (targetItem.currentQty <= 0) {
          setError({
            message: `"${targetItem.supplyName}" is out of stock (0 ${targetItem.unit}). Please restock or select another medicine.`,
          });
          return;
        }
        if (item.quantity > targetItem.currentQty) {
          setError({
            message: `Prescribed quantity of "${targetItem.supplyName}" (${item.quantity} ${targetItem.unit}) exceeds available stock (${targetItem.currentQty} ${targetItem.unit}).`,
          });
          return;
        }
      }
    }

    setBusy(true);
    try {
      // Tạo từng mục điều trị theo đơn thuốc
      for (const item of finalMeds) {
        await createTreatment({
          recordId: Number(recordId),
          supplyId: item.supplyId,
          medicineName: item.name,
          dosage: item.dosage,
          frequency: item.frequency,
          instruction: item.instruction,
          startDate,
          endDate: endDate || null,
          quantity: item.quantity,
          unitPrice: item.unitPrice,
        });
      }

      const summaryStr = finalMeds.map((m) => m.name).join(", ");
      onDone(`Prescription (${summaryStr}) has been saved successfully for ${horseName}. Owner and Manager have been notified.`);
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

        {/* 1. Chọn Ngựa & Ca khám */}
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

        {/* 2. CHỌN THUỐC, LIỀU LƯỢNG, FREQUENCY, INSTRUCTION */}
        <div className="vc-form-grid" style={{ marginTop: "14px" }}>
          {/* Dropdown 17 loại thuốc */}
          <label className="vx-field vx-field--full">
            <span>Medicine (choose from catalog)</span>
            <select
              className="vx-input vc-select"
              value={chosenMedKey}
              onChange={(e) => handleDropdownChange(e.target.value)}
            >
              <option value="">Choose medicine ({medCatalog.length} medicines available)…</option>
              {medCatalog.map((m) => {
                const isOutOfStock = m.currentQty <= 0;
                return (
                  <option
                    key={m.supplyName}
                    value={m.supplyName}
                    disabled={isOutOfStock}
                    style={isOutOfStock ? { color: "#9ca3af" } : {}}
                  >
                    {m.supplyName} · {isOutOfStock ? `[Out of stock - 0 ${m.unit}]` : `${m.currentQty} ${m.unit} in stock`}
                  </option>
                );
              })}
            </select>
          </label>

          {/* Ô nhập tùy chọn nếu không dùng thuốc trong danh mục */}
          {!chosenMedKey && (
            <label className="vx-field vx-field--full" style={{ marginTop: "8px" }}>
              <span>Or custom treatment / non-drug care</span>
              <input
                className="vx-input"
                value={customMed}
                maxLength={150}
                onChange={(e) => setCustomMed(e.target.value)}
                placeholder="e.g. Cold compress, Epsom salt soak (or choose medicine above)"
              />
            </label>
          )}

          {/* Dosage & Đơn vị theo loại thuốc */}
          <label className="vx-field">
            <span>Dosage {doseUnit ? `(${doseUnit})` : ""}</span>
            <div className="vx-dosage-row">
              <input
                type="number"
                min="0.5"
                step="0.5"
                className="vx-input"
                value={doseQty}
                onChange={(e) => setDoseQty(e.target.value)}
                placeholder={doseUnit ? `Quantity (${doseUnit})` : "e.g. 2"}
              />
              {doseUnit ? (
                <span className="vx-dosage-badge">{doseUnit}</span>
              ) : (
                <input
                  type="text"
                  className="vx-input"
                  style={{ maxWidth: "80px" }}
                  value={doseUnit}
                  onChange={(e) => setDoseUnit(e.target.value)}
                  placeholder="unit"
                />
              )}
            </div>
          </label>

          {/* Frequency */}
          <label className="vx-field">
            <span>Frequency</span>
            <input
              className="vx-input"
              value={frequency}
              maxLength={50}
              onChange={(e) => setFrequency(e.target.value)}
              placeholder="e.g. Twice daily, Once daily with morning feed"
            />
          </label>

          {/* Instruction */}
          <label className="vx-field vx-field--full">
            <span>Instruction (optional)</span>
            <textarea
              className="vx-input vx-textarea"
              rows={2}
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="e.g. Mix with morning and evening feed. Administer after light work."
            />
          </label>
        </div>

        {/* Nút "+ Thêm vào đơn thuốc" */}
        <div className="vx-add-med-row">
          <button
            type="button"
            className="vx-add-med-btn"
            disabled={
              (!chosenMedKey && !customMed.trim()) ||
              (chosenMedKey && (medCatalog.find((m) => m.supplyName === chosenMedKey)?.currentQty ?? 1) <= 0)
            }
            onClick={handleAddMedicine}
          >
            + Add medicine to prescription
          </button>
        </div>

        {fieldNotice && (
          <div style={{
            background: "#fff1f2",
            border: "1px solid #fecdd3",
            color: "#e11d48",
            borderRadius: "8px",
            padding: "8px 12px",
            fontSize: "13px",
            margin: "6px 0 12px",
            fontWeight: 600
          }}>
            ⚠️ {fieldNotice}
          </div>
        )}

        {/* 3. SELECTED MEDICINES LIST (NẰM DƯỚI PHẦN INSTRUCTION THEO YÊU CẦU) */}
        {selectedMeds.length > 0 && (
          <div className="vx-selected-meds-box">
            <span className="vx-selected-meds-title">
              Selected medicines ({selectedMeds.length}):
            </span>
            <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
              {selectedMeds.map((med, idx) => (
                <li key={idx} className="vx-selected-med-item-rich">
                  <div className="vx-selected-med-details">
                    • <strong>{med.name}</strong> — {med.quantity} {med.unit} · {med.frequency}
                    {med.instruction && (
                      <div className="vx-selected-med-sub">Instruction: {med.instruction}</div>
                    )}

                  </div>
                  <button
                    type="button"
                    className="vx-med-delete-btn"
                    onClick={() => removeMed(idx)}
                  >
                    ✕ Remove
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* 4. Start date & End date */}
        <div className="vc-form-grid" style={{ marginTop: "10px" }}>
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

        <p className="vc-hint">
          {endDate
            ? `Treatment runs from ${formatDate(startDate)} to ${formatDate(endDate)}.`
            : "Leave the end date empty if the treatment runs until the next review."}
        </p>

        {/* Actions */}
        <div className="vx-actions" style={{ marginTop: "20px" }}>
          <button
            type="button"
            className="vx-btn vx-btn--green"
            disabled={busy || !recordId || (selectedMeds.length === 0 && !chosenMedKey && !customMed.trim())}
            onClick={submit}
          >
            {busy ? "Saving…" : `Save prescription (${selectedMeds.length || (chosenMedKey || customMed ? 1 : 0)} medicines)`}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
