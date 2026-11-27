// src/features/vet/VetPrescriptionPage.js
// Màn Prescriptions: đơn thuốc và thống kê chi phí y tế trên toàn đàn.

import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { healthApplies, toStatus } from "../horse/horseApi";
import HorsePhoto from "../horse/HorsePhoto";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import {
  listActiveTreatments,
  listTreatmentsForHorse,
  listMedicalRecords,
  listInjuries,
  listHorses,
  createTreatment,
  endTreatment,
  issueLock,
  listMedicalExpenses,
  listMedicineSupplies,
  careTypeLabel,
  formatDate,
  normalizeDate,
  todayInputValue,
  describeError,
} from "./vetApi";
import "./vet-care.css";

// Các hàm định dạng nhỏ: số nhiều tiếng Anh, ghi chú chi phí thuốc "(Qty: x @ giá)" -> "(x tablets)", tiền VND,
// tên / liều / khoảng ngày của một mục điều trị.
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
function formatExpenseNote(note) {
  if (!note) return "";
  return note.replace(/\(Qty:\s*(\d+(?:\.\d+)?)\s*@\s*\d+\)/gi, (match, q) => {
    if (note.includes("Prednisolone") || note.includes("Firocoxib")) return `(${q} tablets)`;
    if (note.includes("Phenylbutazone") || note.includes("TMS") || note.includes("GastroGard") || note.includes("Omeprazole") || note.includes("Povidone") || note.includes("Ivermectin")) return `(${q} tubes)`;
    if (note.includes("Flunixin") || note.includes("Dexamethasone") || note.includes("Amoxicillin") || note.includes("Gentamicin") || note.includes("Penicillin") || note.includes("Xylazine")) return `(${q} vials)`;
    if (note.includes("Probiotic")) return `(${q} sachets)`;
    if (note.includes("Ventipulmin") || note.includes("Clenbuterol")) return `(${q} bottles)`;
    if (note.includes("Glucosamine")) return `(${q} boxes)`;
    if (note.includes("Hyaluronic")) return `(${q} ampoules)`;
    if (note.includes("Electrolytes") || note.includes("Electrolytes")) return `(${q} litres)`;
    return `(${q})`;
  });
}

const formatVND = (v) => `${Number(v || 0).toLocaleString("en-US")} VND`;

const treatmentTitle = (t) => t.medicineName || t.instruction || "Treatment";
const dose = (t) => [t.dosage, t.frequency].filter(Boolean).join(" · ") || "—";
const period = (t) =>
  `${formatDate(t.startDate)} → ${t.endDate ? formatDate(t.endDate) : "until review"}`;

// 17 loại thuốc phổ biến cho các bệnh lý của ngựa
// (Danh mục viết cứng ở frontend; khi mở form kê đơn, mỗi mục được ghép với thuốc thật trong kho theo tên gần đúng
// để lấy supplyId, đơn vị, đơn giá, tồn kho, hạn dùng - xem effect ở PrescribeDialog.)
const COMMON_MEDICINES = [
  { supplyName: "Phenylbutazone (Bute)", unit: "tube", unitPrice: 180000, category: "Anti-inflammatory & Pain relief" },
  { supplyName: "Flunixin Meglumine (Banamine)", unit: "vial", unitPrice: 250000, category: "Colic & Fever" },
  { supplyName: "Firocoxib (Equioxx)", unit: "tablet", unitPrice: 45000, category: "Chronic arthritis" },
  { supplyName: "Dexamethasone", unit: "vial", unitPrice: 120000, category: "Anti-allergy & Acute inflammation" },
  { supplyName: "Amoxicillin Trihydrate", unit: "vial", unitPrice: 210000, category: "Antibiotic - infections" },
  { supplyName: "Gentamicin Sulfate", unit: "vial", unitPrice: 320000, category: "Antibiotic - respiratory & joint" },
  { supplyName: "Trimethoprim-Sulfadiazine (TMS)", unit: "tube", unitPrice: 280000, category: "Antibiotic - wounds" },
  { supplyName: "Penicillin G Procaine", unit: "vial", unitPrice: 195000, category: "Antibiotic - intramuscular" },
  { supplyName: "Omeprazole (GastroGard)", unit: "tube", unitPrice: 350000, category: "Gastric ulcers" },
  { supplyName: "Electrolyte solution (Electrolytes)", unit: "litre", unitPrice: 95000, category: "Hydration & Electrolytes" },
  { supplyName: "Digestive probiotic (Probiotic)", unit: "sachet", unitPrice: 65000, category: "Digestive support" },
  { supplyName: "Clenbuterol (Ventipulmin)", unit: "bottle", unitPrice: 420000, category: "Bronchodilator & Cough" },
  { supplyName: "Prednisolone", unit: "tablet", unitPrice: 15000, category: "Oral anti-inflammatory" },
  { supplyName: "Glucosamine + Chondroitin", unit: "box", unitPrice: 650000, category: "Joint cartilage support" },
  { supplyName: "Hyaluronic Acid (Joint injection)", unit: "ampoule", unitPrice: 550000, category: "Joint fluid lubrication" },
  { supplyName: "Povidone antiseptic ointment", unit: "tube", unitPrice: 50000, category: "External wound antiseptic" },
  { supplyName: "Ivermectin (Dewormer)", unit: "tube", unitPrice: 160000, category: "Intestinal parasites" },
];

/** 5 standard clinical treatment protocols for common racehorse conditions */
export const CLINICAL_PROTOCOLS = [
  {
    id: "SDFT",
    name: "1. Tendonitis (SDFT)",
    shortName: "SDFT Tendonitis",
    days: 7,
    lockLevel: "FULL",
    note: "[SDFT Protocol] Acute tendonitis. Full training lock 7-14 days. Avoid sprinting.",
    medicines: [
      { medicineName: "Phenylbutazone (Bute)", dosage: 2, frequency: "Once daily", instruction: "Mix with morning feed." },
      { medicineName: "Povidone antiseptic ointment", dosage: 1, frequency: "Twice daily", instruction: "Apply topically after cold hosing." },
      { medicineName: "Glucosamine + Chondroitin", dosage: 1, frequency: "Once daily", instruction: "Tendon & cartilage support." },
    ],
  },
  {
    id: "EGUS",
    name: "2. Gastric Ulcer (EGUS)",
    shortName: "Gastric Ulcer (EGUS)",
    days: 14,
    lockLevel: "HEAVY_WORK_ONLY",
    note: "[EGUS Protocol] Stress-induced gastric ulcer. Light walk/trot allowed, no galloping.",
    medicines: [
      { medicineName: "Omeprazole (GastroGard)", dosage: 1, frequency: "Once daily", instruction: "Administer orally 30 min before morning feed." },
      { medicineName: "Digestive probiotic (Probiotic)", dosage: 2, frequency: "Twice daily", instruction: "Mix with feed to support gut flora." },
    ],
  },
  {
    id: "FETLOCK",
    name: "3. Fetlock Synovitis",
    shortName: "Fetlock Synovitis",
    days: 5,
    lockLevel: "FULL",
    note: "[Fetlock Protocol] Joint effusion & swelling. Strict rest for the first 5 days.",
    medicines: [
      { medicineName: "Flunixin Meglumine (Banamine)", dosage: 1, frequency: "Once daily", instruction: "Administer IV or orally per dose." },
      { medicineName: "Hyaluronic Acid (Joint injection)", dosage: 1, frequency: "Once daily", instruction: "Joint lubrication support." },
      { medicineName: "Glucosamine + Chondroitin", dosage: 1, frequency: "Once daily", instruction: "Daily joint maintenance." },
    ],
  },
  {
    id: "BRONCHITIS",
    name: "4. Equine Bronchitis",
    shortName: "Equine Bronchitis",
    days: 7,
    lockLevel: "FULL",
    note: "[Bronchitis Protocol] Bronchodilator & cough relief. Isolate in medical stall, no heavy work.",
    medicines: [
      { medicineName: "Clenbuterol (Ventipulmin)", dosage: 1, frequency: "Twice daily", instruction: "Mix with feed every 12 hours." },
      { medicineName: "Amoxicillin Trihydrate", dosage: 1, frequency: "Twice daily", instruction: "Broad-spectrum antibiotic course." },
      { medicineName: "Prednisolone", dosage: 2, frequency: "Once daily", instruction: "Airway anti-inflammatory." },
    ],
  },
  {
    id: "HEAT_EXHAUSTION",
    name: "5. Heat Exhaustion",
    shortName: "Heat Exhaustion",
    days: 3,
    lockLevel: "FULL",
    note: "[Heat Exhaustion Protocol] Hyperthermia & dehydration. Rest in cool, ventilated stall.",
    medicines: [
      { medicineName: "Electrolyte solution (Electrolytes)", dosage: 2, frequency: "Twice daily", instruction: "Dilute in clean drinking water or oral drench." },
      { medicineName: "Flunixin Meglumine (Banamine)", dosage: 1, frequency: "Once daily", instruction: "Reduce fever & muscle soreness." },
    ],
  },
];

const CUSTOM_PROTOCOLS_KEY = "rtms_custom_clinical_protocols";
function loadCustomProtocols() {
  try {
    const raw = localStorage.getItem(CUSTOM_PROTOCOLS_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/*
 * Trang /veterinarian/prescriptions. Ba tab (đồng bộ với ?tab= trên URL, replace: true để không làm dày lịch sử):
 *   prescriptions - các mục điều trị đang hiệu lực toàn đàn (GET /api/treatments/active), gom thành "đơn";
 *   directory     - danh sách ngựa để mở lịch sử đơn thuốc từng con (/veterinarian/prescriptions/history/:horseId);
 *   expenses      - chi phí MEDICAL (GET /api/expenses?category=MEDICAL, bác sĩ được đọc).
 * Nút "Prescribe" mở PrescribeDialog; EndDialog kết thúc một mục điều trị.
 */
export default function VetPrescriptionPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [tab, setTab] = useState(() => {
    const t = searchParams.get("tab");
    if (t === "directory" || t === "medical" || t === "horse-medical") return "directory";
    if (t === "expenses") return "expenses";
    return "prescriptions";
  });

  useEffect(() => {
    const t = searchParams.get("tab");
    if (t === "directory" || t === "medical" || t === "horse-medical") {
      setTab("directory");
    } else if (t === "expenses") {
      setTab("expenses");
    } else if (t === "prescriptions") {
      setTab("prescriptions");
    }
  }, [searchParams]);

  // Đổi tab và ghi lại vào URL (tab mặc định thì bỏ tham số).
  const handleTabChange = (nextTab) => {
    setTab(nextTab);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (nextTab === "prescriptions") {
          next.delete("tab");
        } else {
          next.set("tab", nextTab);
        }
        return next;
      },
      { replace: true }
    );
  };

  const [active, setActive] = useState(null);
  const [horses, setHorses] = useState(null);
  const [expenses, setExpenses] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");

  const [history, setHistory] = useState(null);
  const [prescribing, setPrescribing] = useState(false);
  const [ending, setEnding] = useState(null);
  const [selectedPrescription, setSelectedPrescription] = useState(null);
  const [notice, setNotice] = useState("");

  // Load horses directory
  useEffect(() => {
    const ctrl = new AbortController();
    listHorses(ctrl.signal)
      .then((list) =>
        setHorses(
          (list || [])
            .filter(healthApplies)
            .sort((a, b) => a.horseName.localeCompare(b.horseName))
        )
      )
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
      });
    return () => ctrl.abort();
  }, [navigate]);

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
  // Chi phí y tế chỉ tải khi đang ở tab expenses.
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

  // Gom các mục điều trị thành đơn theo (ngựa, lần khám, ngày bắt đầu); lọc theo tên ngựa / tên thuốc / liều.
  const prescriptionGroups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const map = new Map();
    (active || []).forEach((t) => {
      const key = `${t.horseId}_${t.recordId || "norec"}_${t.startDate}`;
      if (!map.has(key)) {
        map.set(key, {
          prescriptionKey: key,
          horseId: t.horseId,
          horseName: t.horseName,
          recordId: t.recordId,
          startDate: t.startDate,
          endDate: t.endDate,
          items: [],
        });
      }
      const grp = map.get(key);
      grp.items.push(t);
      if (t.endDate && (!grp.endDate || t.endDate > grp.endDate)) {
        grp.endDate = t.endDate;
      }
    });

    let groups = Array.from(map.values());

    if (q) {
      groups = groups.filter((g) => {
        if (g.horseName.toLowerCase().includes(q)) return true;
        return g.items.some(
          (t) =>
            String(t.medicineName || "").toLowerCase().includes(q) ||
            String(t.instruction || "").toLowerCase().includes(q) ||
            String(t.dosage || "").toLowerCase().includes(q)
        );
      });
    }

    return groups;
  }, [active, query]);

  const horseRows = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!horses) return [];
    if (!q) return horses;
    return horses.filter(
      (h) =>
        (h.horseName && h.horseName.toLowerCase().includes(q)) ||
        (h.registrationCode && h.registrationCode.toLowerCase().includes(q)) ||
        (h.ownerName && h.ownerName.toLowerCase().includes(q)) ||
        (h.ownerFullName && h.ownerFullName.toLowerCase().includes(q)) ||
        (h.stallNo && String(h.stallNo).toLowerCase().includes(q))
    );
  }, [horses, query]);

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

  // Sau khi kê đơn / kết thúc: đóng hộp thoại, hiện xác nhận, tăng reload để tải lại danh sách.
  const handleSaved = (message) => {
    setPrescribing(false);
    setEnding(null);
    setNotice(message);
    setReload((n) => n + 1);
  };

  return (
    <VetShell
      active="prescriptions"
      title="Treatment Plans & Medical Care"
      subtitle="Treatments, care plans and medical expenses across the herd"
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
          role="tab"
          className="vx-tab-btn"
          aria-selected={tab === "prescriptions"}
          onClick={() => handleTabChange("prescriptions")}
        >
          Treatment plans ({prescriptionGroups ? prescriptionGroups.length : 0})
        </button>
        <button
          type="button"
          role="tab"
          className="vx-tab-btn"
          aria-selected={tab === "directory"}
          onClick={() => handleTabChange("directory")}
        >
          Horse Treatment Directory ({horses ? horses.length : "..."})
        </button>
        <button
          type="button"
          role="tab"
          className="vx-tab-btn"
          aria-selected={tab === "expenses"}
          onClick={() => handleTabChange("expenses")}
        >
          Medical Expenses ({expenses ? expenses.length : "..."})
        </button>
      </div>

      {!error && !active && <VetState title="Loading treatment plans…" />}

      {tab === "prescriptions" && active && (
        <VetCard
          title="Active treatment plans"
          hint={`${plural(prescriptionGroups.length, "treatment plan")} active or scheduled`}
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
                New treatment plan
              </button>
            </div>
          }
        >
          {prescriptionGroups.length === 0 ? (
            <div className="vet-inline-state">
              {query
                ? "No treatment plans match your search."
                : "No horse is on a treatment plan today. Use New treatment plan to add one."}
            </div>
          ) : (
            <div className="vc-table-wrap">
              <table className="vc-table">
                <thead>
                  <tr>
                    <th>Horse</th>
                    <th>Start date</th>
                    <th>End date</th>
                    <th aria-label="Actions" style={{ textAlign: "right" }} />
                  </tr>
                </thead>
                <tbody>
                  {prescriptionGroups.map((p) => (
                    <tr key={p.prescriptionKey}>
                      <td>
                        <span className="vc-name">{p.horseName}</span>
                        <span
                          className="vx-pill"
                          style={{
                            marginLeft: "10px",
                            fontSize: "12px",
                            padding: "2px 8px",
                            background: "#f1f5f9",
                            color: "#475569",
                            fontWeight: 600,
                          }}
                        >
                          {p.items.length} {p.items.length === 1 ? "medicine" : "medicines"}
                        </span>
                      </td>
                      <td>{formatDate(p.startDate)}</td>
                      <td>{p.endDate ? formatDate(p.endDate) : "until review"}</td>
                      <td style={{ textAlign: "right" }}>
                        <div className="vc-cell-actions" style={{ justifyContent: "flex-end", gap: "8px" }}>
                          <button
                            type="button"
                            className="vc-btn"
                            onClick={() => setSelectedPrescription(p)}
                          >
                            Details
                          </button>
                          <button
                            type="button"
                            className="vc-btn"
                            style={{ color: "#b32569", borderColor: "#f3c2db" }}
                            onClick={() => navigate(`/veterinarian/prescriptions/history/${p.horseId}`)}
                            title="View complete prescription history"
                          >
                            History →
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
              New treatment plan
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
                      <td>{formatExpenseNote(e.note) || "Medical treatment"}</td>
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

      {tab === "directory" && (
        <VetCard
          title="Horse Treatment Directory"
          hint={`Select any horse to view its complete lifetime treatment history and medical care (${horseRows.length} horses)`}
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
              New treatment plan
            </button>
          }
        >
          {!horses ? (
            <VetState title="Loading horses directory…" />
          ) : horseRows.length === 0 ? (
            <div className="vet-inline-state">No horses found matching "{query}".</div>
          ) : (
            <div className="vc-horse-grid">
              {horseRows.map((h) => {
                const hStatus = h.status ? toStatus(h.status) : null;
                return (
                  <div
                    key={h.horseId}
                    className="vc-horse-card"
                    onClick={() => navigate(`/veterinarian/prescriptions/history/${h.horseId}`)}
                  >
                    <div className="vc-horse-card-header">
                      <HorsePhoto horse={h} className="vc-horse-card-avatar" />
                      <div className="vc-horse-card-info">
                        <div className="vc-horse-card-title">{h.horseName}</div>
                        <div className="vc-horse-card-code">
                          {h.registrationCode} · Stall {h.stallNo || "—"}
                        </div>
                      </div>
                    </div>

                    <div className="vc-horse-card-owner">
                      👤 Owner: <strong>{h.ownerName || h.ownerFullName || "Tracen Bloodstock"}</strong>
                    </div>

                    <div className="vc-horse-card-footer">
                      {hStatus ? (
                        <span className={`vc-pill vc-pill--${hStatus.tone || "green"}`}>
                          {hStatus.label}
                        </span>
                      ) : (
                        <span className="vc-muted" style={{ fontSize: "12px" }}>
                          {h.breed || "Thoroughbred"}
                        </span>
                      )}
                      <span className="vc-card-action-btn">
                        View History →
                      </span>
                    </div>
                  </div>
                );
              })}
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

      {selectedPrescription && (
        <PrescriptionDetailModal
          prescription={selectedPrescription}
          onClose={() => setSelectedPrescription(null)}
          onViewHistory={(id) => navigate(`/veterinarian/prescriptions/history/${id}`)}
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

/* ---------- chi tiết đơn thuốc ---------- */

// Hộp thoại xem chi tiết một đơn (các thuốc trong đơn, liều, khoảng ngày).
function PrescriptionDetailModal({ prescription, onClose, onViewHistory }) {
  if (!prescription) return null;
  return (
    <div className="vet-modal-backdrop" onClick={onClose}>
      <div
        className="vet-modal-card vc-dialog-wide"
        style={{ maxWidth: "780px" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="vet-modal-header">
          <div>
            <h3 style={{ margin: 0 }}>Treatment Plan Details · {prescription.horseName}</h3>
            <small style={{ color: "#64748b" }}>
              {prescription.recordId ? `Examination #${prescription.recordId}` : "Medical Record"} ·{" "}
              {formatDate(prescription.startDate)} →{" "}
              {prescription.endDate ? formatDate(prescription.endDate) : "until review"}
            </small>
          </div>
          <button type="button" className="vet-modal-close" onClick={onClose}>
            &times;
          </button>
        </div>
        <div className="vet-modal-body" style={{ maxHeight: "70vh", overflowY: "auto" }}>
          <div className="vc-table-wrap">
            <table className="vc-table">
              <thead>
                <tr>
                  <th>Medicine</th>
                  <th>Dosage</th>
                  <th>Period</th>
                  <th>Instruction</th>
                </tr>
              </thead>
              <tbody>
                {prescription.items.map((m, idx) => (
                  <tr key={m.treatmentId || idx}>
                    <td>
                      <strong>{m.medicineName || "Treatment item"}</strong>
                    </td>
                    <td>{dose(m)}</td>
                    <td>{period(m)}</td>
                    <td>{m.instruction || "As directed"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <div className="vet-modal-footer" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          {onViewHistory && (
            <button
              type="button"
              className="vc-btn"
              style={{ color: "#b32569", borderColor: "#f3c2db" }}
              onClick={() => {
                onClose();
                onViewHistory(prescription.horseId);
              }}
            >
              📋 View Lifetime Treatments for {prescription.horseName} →
            </button>
          )}
          <button type="button" className="vx-btn vx-btn--ghost" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/* ---------- lịch sử đơn thuốc của một con ngựa ---------- */

// Thẻ lịch sử điều trị của một ngựa (GET /api/treatments?horseId=).
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

// Kết thúc một mục điều trị hôm nay (PUT /api/treatments/{id}/end); backend từ chối đơn chưa tới ngày bắt đầu.
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

/*
 * Hộp thoại kê đơn. Luồng: chọn ngựa -> chọn lần khám (chỉ lần khám EXAM chưa được kê đơn, chưa hồi phục) -> thêm
 * từng thuốc (từ danh mục hoặc gõ tên) với liều, đơn vị, tần suất, hướng dẫn -> ngày bắt đầu / kết thúc -> tuỳ chọn
 * khoá tập FULL -> Save.
 */
function PrescribeDialog({ onClose, onDone }) {
  const navigate = useNavigate();

  const [horses, setHorses] = useState(null);
  const [horseId, setHorseId] = useState("");
  const [records, setRecords] = useState(null);
  const [treatments, setTreatments] = useState([]);
  const [injuries, setInjuries] = useState([]);
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

  // Ghi chú đơn thuốc (tuỳ chọn - ghi chú hoặc thuốc ngoài kho)
  const [prescriptionNote, setPrescriptionNote] = useState("");

  // Thời gian áp dụng
  const [startDate, setStartDate] = useState(todayInputValue());
  const [endDate, setEndDate] = useState("");
  const [trainingLock, setTrainingLock] = useState("FULL");

  // Clinical Treatment Protocol Templates (built-in + custom saved in localStorage)
  const [customProtocols, setCustomProtocols] = useState(() => loadCustomProtocols());
  const allProtocols = [...CLINICAL_PROTOCOLS, ...customProtocols];
  const [selectedProtocolId, setSelectedProtocolId] = useState("");
  const [showNewProtocol, setShowNewProtocol] = useState(false);
  const [newProtoName, setNewProtoName] = useState("");
  const [newProtoDays, setNewProtoDays] = useState("7");
  const [newProtoLock, setNewProtoLock] = useState("FULL");
  const [newProtoNote, setNewProtoNote] = useState("");
  const [newProtoMeds, setNewProtoMeds] = useState([]);
  const [newProtoMedKey, setNewProtoMedKey] = useState("");
  const [newProtoMedQty, setNewProtoMedQty] = useState("1");
  const [newProtoMedFreq, setNewProtoMedFreq] = useState("Twice daily");
  const [newProtoError, setNewProtoError] = useState("");

  const handleProtocolChange = (protocolId, listOverride) => {
    setSelectedProtocolId(protocolId);
    if (!protocolId) return;
    const pool = listOverride || allProtocols;
    const proto = pool.find((p) => p.id === protocolId);
    if (!proto) return;

    const autoMeds = (proto.medicines || []).map((pm) => {
      const matched = medCatalog.find((m) =>
        m.supplyName.toLowerCase() === pm.medicineName.toLowerCase() ||
        m.supplyName.toLowerCase().includes(pm.medicineName.toLowerCase().split(" ")[0])
      );
      const unit = matched?.unit || "unit";
      const unitPrice = matched?.unitPrice || 0;
      const qty = pm.dosage || 1;
      return {
        supplyId: matched?.supplyId || null,
        name: matched?.supplyName || pm.medicineName,
        unit,
        unitPrice,
        quantity: qty,
        dosage: `${qty} ${unit}`,
        frequency: pm.frequency || "Twice daily",
        instruction: pm.instruction || null,
      };
    });

    setSelectedMeds(autoMeds);
    setChosenMedKey("");
    setCustomMed("");
    setDoseQty("");
    setDoseUnit("");
    setInstruction("");
    setTrainingLock(proto.lockLevel);
    setPrescriptionNote(proto.note);

    // Compute end date: startDate + days
    try {
      const base = startDate ? new Date(startDate) : new Date();
      base.setDate(base.getDate() + proto.days);
      const yyyy = base.getFullYear();
      const mm = String(base.getMonth() + 1).padStart(2, "0");
      const dd = String(base.getDate()).padStart(2, "0");
      setEndDate(`${yyyy}-${mm}-${dd}`);
    } catch (_) {}
  };

  const openNewProtocolForm = () => {
    setNewProtoError("");
    setNewProtoName("");
    setNewProtoDays("7");
    setNewProtoLock("FULL");
    setNewProtoNote("");
    setNewProtoMedKey("");
    setNewProtoMedQty("1");
    setNewProtoMedFreq("Twice daily");
    // Pre-fill from selectedMeds if vet already added medicines below
    setNewProtoMeds(
      selectedMeds.map((m) => ({
        medicineName: m.name,
        dosage: m.quantity || 1,
        frequency: m.frequency || "Twice daily",
        instruction: m.instruction || "",
      }))
    );
    setShowNewProtocol(true);
  };

  const addMedToNewProtocol = () => {
    setNewProtoError("");
    if (!newProtoMedKey) {
      setNewProtoError("Choose a medicine first.");
      return;
    }
    const qty = Number(newProtoMedQty);
    if (!Number.isFinite(qty) || qty <= 0) {
      setNewProtoError("Quantity must be greater than 0.");
      return;
    }
    setNewProtoMeds((prev) => [
      ...prev,
      {
        medicineName: newProtoMedKey,
        dosage: qty,
        frequency: newProtoMedFreq.trim() || "Twice daily",
        instruction: "",
      },
    ]);
    setNewProtoMedKey("");
    setNewProtoMedQty("1");
  };

  const saveCustomProtocol = () => {
    setNewProtoError("");
    const name = newProtoName.trim();
    const days = Number(newProtoDays);
    if (!name) {
      setNewProtoError("Enter a protocol name.");
      return;
    }
    if (!Number.isFinite(days) || days < 1) {
      setNewProtoError("Duration must be at least 1 day.");
      return;
    }
    if (newProtoMeds.length === 0) {
      setNewProtoError("Add at least 1 medicine to the protocol.");
      return;
    }
    const newProto = {
      id: `CUSTOM_${Date.now()}`,
      isCustom: true,
      name: `${allProtocols.length + 1}. ${name}`,
      shortName: name,
      days,
      lockLevel: newProtoLock,
      note: newProtoNote.trim() || `[${name}]`,
      medicines: newProtoMeds,
    };
    const nextCustom = [...customProtocols, newProto];
    setCustomProtocols(nextCustom);
    try {
      localStorage.setItem(CUSTOM_PROTOCOLS_KEY, JSON.stringify(nextCustom));
    } catch (_) {}
    setShowNewProtocol(false);
    handleProtocolChange(newProto.id, [...CLINICAL_PROTOCOLS, ...nextCustom]);
  };

  const deleteCustomProtocol = (id) => {
    const nextCustom = customProtocols.filter((p) => p.id !== id);
    setCustomProtocols(nextCustom);
    try {
      localStorage.setItem(CUSTOM_PROTOCOLS_KEY, JSON.stringify(nextCustom));
    } catch (_) {}
    if (selectedProtocolId === id) {
      setSelectedProtocolId("");
    }
  };

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
  // Ghép COMMON_MEDICINES với thuốc MEDICINE trong kho: khớp tên gần đúng; tìm thấy thì lấy số liệu của kho,
  // không thấy thì tồn kho = 0.
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
              expiryDate: found.expiryDate || null,
              expired: !!found.expired,
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
              expiryDate: s.expiryDate || null,
              expired: !!s.expired,
              category: "Other",
            });
          }
        }
        setMedCatalog(merged);
      })
      .catch(() => {});
    return () => ctrl.abort();
  }, []);

  // Load medical records, treatments & injuries khi chọn ngựa
  // Chọn ngựa thì tải hồ sơ khám, các mục điều trị và chấn thương của nó để biết lần khám nào còn cần kê đơn.
  useEffect(() => {
    setRecords(null);
    setTreatments([]);
    setInjuries([]);
    setRecordId("");
    if (!horseId) return undefined;
    const ctrl = new AbortController();

    Promise.all([
      listMedicalRecords(horseId, ctrl.signal),
      listTreatmentsForHorse(horseId, ctrl.signal).catch(() => []),
      listInjuries(horseId, ctrl.signal).catch(() => []),
    ])
      .then(([recList, trtList, injList]) => {
        setRecords(recList);
        setTreatments(trtList || []);
        setInjuries(Array.isArray(injList) ? injList : injList?.content || []);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError({ message: err.message });
      });
    return () => ctrl.abort();
  }, [horseId, navigate]);

  const treatedRecordIds = useMemo(() => {
    return new Set((treatments || []).map((t) => t.recordId).filter(Boolean));
  }, [treatments]);

  const recoveredRecordIds = useMemo(() => {
    const recordInjuriesMap = new Map();
    for (const inj of injuries || []) {
      if (!inj.recordId) continue;
      if (!recordInjuriesMap.has(inj.recordId)) recordInjuriesMap.set(inj.recordId, []);
      recordInjuriesMap.get(inj.recordId).push(inj);
    }
    const recovered = new Set();
    for (const [rId, injs] of recordInjuriesMap.entries()) {
      if (injs.length > 0 && injs.every((i) => i.status === "RECOVERED" || i.recoveredAt != null)) {
        recovered.add(rId);
      }
    }
    return recovered;
  }, [injuries]);

  // Một lần khám "còn cần điều trị": loại EXAM, trạng thái không phải ELIGIBLE, không phải chấm thể lực / cho tập nhẹ,
  // chấn thương chưa lành hết, và chưa có mục điều trị nào gắn với nó. (Lọc bằng chữ trong diagnosis.)
  const isExamUntreated = useCallback((r) => {
    // 1. Chỉ nhận bản ghi EXAM (loại bỏ FARRIER, VACCINE, DEWORM, CLEARANCE)
    if (r.recordType && r.recordType !== "EXAM") return false;

    // 2. Loại bỏ các ca đã bình phục / khỏi bệnh / clearance
    if (r.healthStatus === "ELIGIBLE") return false;
    const diag = (r.diagnosis || "").toLowerCase();
    if (diag.startsWith("fitness rating") || diag.startsWith("light work clearance")) return false;

    // 3. Loại bỏ ca đã khỏi (mọi injury đã recovered)
    if (recoveredRecordIds.has(r.recordId)) return false;

    // 4. Loại bỏ ca đã được kê đơn / điều trị
    if (treatedRecordIds.has(r.recordId)) return false;

    return true;
  }, [recoveredRecordIds, treatedRecordIds]);

  // Chỉ giữ một lần khám "relapse" (tái phát) mới nhất để danh sách không bị lặp.
  const eligibleRecords = useMemo(() => {
    if (!records) return [];
    const sourceList = records.filter(isExamUntreated);

    // Không cho lặp lại nhiều examination Relapse: chỉ giữ tối đa 1 bản ghi Relapse mới nhất
    const result = [];
    let seenRelapse = false;
    for (const r of sourceList) {
      const isRelapse = (r.diagnosis || "").toLowerCase().includes("relapse");
      if (isRelapse) {
        if (!seenRelapse) {
          seenRelapse = true;
          result.push(r);
        }
        // Bỏ qua các lần lặp lại relapse cũ
      } else {
        result.push(r);
      }
    }
    return result;
  }, [records, isExamUntreated]);

  useEffect(() => {
    if (eligibleRecords.length > 0) {
      if (!eligibleRecords.some((r) => String(r.recordId) === String(recordId))) {
        setRecordId(String(eligibleRecords[0].recordId));
      }
    } else {
      setRecordId("");
    }
  }, [eligibleRecords, recordId]);

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
      if (item.expired) {
        setFieldNotice(`"${item.supplyName}" expired on ${item.expiryDate}. Report it from the Medicine page so the club manager replaces it.`);
      } else if (item.currentQty <= 0) {
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
    const unit = doseUnit.trim() || catalogItem?.unit || "dose";
    const unitPrice = catalogItem?.unitPrice || 0;

    // Ràng buộc tồn kho: Thuốc phải còn hàng và đủ số lượng trong kho
    const targetItem = catalogItem || medCatalog.find((m) =>
      m.supplyName.toLowerCase() === medName.toLowerCase() ||
      m.supplyName.toLowerCase().startsWith(medName.toLowerCase())
    );

    if (targetItem) {
      // Thuốc hết hạn: backend cũng chặn (TreatmentService), chặn sớm ở đây cho rõ.
      if (targetItem.expired) {
        setFieldNotice(`"${targetItem.supplyName}" expired on ${targetItem.expiryDate}. Cannot add to prescription.`);
        return;
      }
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
  // Kiểm form, rồi gọi createTreatment cho TỪNG thuốc (vòng lặp, không gộp một giao dịch: lỗi ở thuốc thứ n thì các
  // thuốc trước đã lưu). Mỗi mục backend trừ kho và ghi chi phí MEDICAL. Chọn khoá FULL thì gọi thêm issueLock
  // (lỗi vì ngựa đã có khoá ACTIVE thì bỏ qua).
  const submit = async () => {
    setError(null);

    const problems = [];
    if (!horseId) problems.push("Choose a horse.");
    else if (!recordId || eligibleRecords.length === 0) problems.push("Cannot save prescription: An active, untreated examination is required.");

    if (selectedMeds.length === 0 && !chosenMedKey && !customMed.trim() && !prescriptionNote.trim()) {
      problems.push("Add at least 1 medicine or enter a prescription note.");
    }
    if (!startDate) problems.push("Enter the start date.");
    else if (startDate < todayInputValue()) problems.push("The start date cannot be in the past.");
    if (!endDate) {
      problems.push("Enter the end date. An end date is required so the recovery protocol can proceed.");
    } else if (startDate && endDate < startDate) {
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
      const unit = doseUnit.trim() || catalogItem?.unit || "dose";
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

    if (finalMeds.length === 0 && prescriptionNote.trim()) {
      finalMeds.push({
        supplyId: null,
        name: "Special Prescription Note / Non-inventory",
        unit: "unit",
        unitPrice: 0,
        quantity: 1,
        dosage: "As noted",
        frequency: frequency.trim() || "Once daily",
        instruction: prescriptionNote.trim(),
      });
    } else if (finalMeds.length > 0 && prescriptionNote.trim()) {
      finalMeds = finalMeds.map((m) => ({
        ...m,
        instruction: m.instruction
          ? `${m.instruction} (Note: ${prescriptionNote.trim()})`
          : `Note: ${prescriptionNote.trim()}`,
      }));
    }

    // Ràng buộc tồn kho: Kiểm tra tất cả thuốc trước khi gửi yêu cầu lên server
    for (const item of finalMeds) {
      const targetItem = medCatalog.find((m) =>
        (item.supplyId && m.supplyId === item.supplyId) ||
        m.supplyName.toLowerCase() === item.name.toLowerCase() ||
        m.supplyName.toLowerCase().startsWith(item.name.toLowerCase())
      );
      if (targetItem) {
        if (targetItem.expired) {
          setError({
            message: `"${targetItem.supplyName}" expired on ${targetItem.expiryDate}. Select another medicine, and report it from the Medicine page so the club manager replaces it.`,
          });
          return;
        }
        if (targetItem.currentQty <= 0) {
          setError({
            message: `"${targetItem.supplyName}" is out of stock (0 ${targetItem.unit}). Select another medicine, or ask the club manager to restock (Report on the Medicine page).`,
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

      if (trainingLock === "FULL") {
        try {
          await issueLock({
            horseId: Number(horseId),
            injuryId: null,
            reason: `Treatment plan: ${summaryStr}`.slice(0, 255),
            lockLevel: "FULL",
          });
        } catch {
          // If already under active lock, keep existing lock
        }
      }

      onDone(`Treatment plan (${summaryStr}) has been saved successfully for ${horseName}${trainingLock === "FULL" ? " with Full training lock" : ""}. Owner and Manager have been notified.`);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
      setBusy(false);
    }
  };

  const noRecords = records && eligibleRecords.length === 0;

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vc-rx-title">
      <div className="vx-dialog vc-dialog-wide">
        <h2 id="vc-rx-title">New treatment plan</h2>
        <p className="vx-lead">A treatment plan is written against one examination of the horse.</p>

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
                {noRecords && <option value="">No untreated examination on record</option>}
                {eligibleRecords.map((r) => (
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
            <strong>
              {records.length === 0
                ? `${horseName} has no examination yet.`
                : `No untreated examinations on record for ${horseName}.`}
            </strong>
            <p style={{ margin: "4px 0 0" }}>
              {records.length === 0
                ? "An active examination is required before creating a treatment plan."
                : "All previous examinations have already been treated or marked as recovered. You cannot create a treatment plan without an active, untreated examination."}
            </p>
          </div>
        )}

        {/* Clinical Treatment Protocols */}
        <div style={{ marginTop: "14px", padding: "12px 14px", background: "#f8fafc", border: "1px solid #cbd5e1", borderRadius: "10px" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "6px", gap: "8px", flexWrap: "wrap" }}>
            <span style={{ fontSize: "13px", fontWeight: 700, color: "#1e293b" }}>
              📋 Clinical Protocol
            </span>
            <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
              <span style={{ fontSize: "11px", color: "#64748b" }}>Auto-fills medication, duration &amp; lock</span>
              <button
                type="button"
                className="vx-link"
                style={{ fontSize: "11.5px", fontWeight: 600, padding: "2px 8px", borderRadius: "6px", border: "1px solid #cbd5e1", background: "#fff" }}
                onClick={() => (showNewProtocol ? setShowNewProtocol(false) : openNewProtocolForm())}
              >
                {showNewProtocol ? "Cancel" : "+ New protocol"}
              </button>
            </div>
          </div>
          <select
            className="vx-input vc-select"
            value={selectedProtocolId}
            onChange={(e) => handleProtocolChange(e.target.value)}
            style={{ fontWeight: selectedProtocolId ? 600 : 400, borderColor: selectedProtocolId ? "#3b82f6" : "#cbd5e1" }}
          >
            <option value="">-- Select a protocol (or enter manually) --</option>
            {allProtocols.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} · {p.days}d · {p.lockLevel === "FULL" ? "Full Lock" : "Heavy Work Only"}
              </option>
            ))}
          </select>
          {selectedProtocolId && (() => {
            const activeProto = allProtocols.find((p) => p.id === selectedProtocolId);
            if (!activeProto) return null;
            return (
              <div style={{ marginTop: "8px", fontSize: "12px", color: "#0369a1", display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px" }}>
                <span>✓ Applied: <strong>{activeProto.shortName}</strong> ({activeProto.days}d, {activeProto.lockLevel}).</span>
                {activeProto.isCustom && (
                  <button
                    type="button"
                    className="vx-link"
                    style={{ fontSize: "11px", color: "#b91c1c" }}
                    onClick={() => deleteCustomProtocol(activeProto.id)}
                  >
                    Delete custom
                  </button>
                )}
              </div>
            );
          })()}

          {showNewProtocol && (
            <div style={{ marginTop: "10px", padding: "12px", background: "#fff", border: "1px solid #cbd5e1", borderRadius: "8px", display: "flex", flexDirection: "column", gap: "8px" }}>
              <div style={{ fontSize: "12px", fontWeight: 700, color: "#1e293b" }}>Create Custom Clinical Protocol</div>
              <input
                className="vx-input"
                style={{ width: "100%", minWidth: 0 }}
                placeholder="Protocol name (e.g. Hoof Abscess)"
                value={newProtoName}
                onChange={(e) => setNewProtoName(e.target.value)}
              />
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1.5fr", gap: "8px" }}>
                <input
                  className="vx-input"
                  style={{ minWidth: 0 }}
                  type="number"
                  min={1}
                  placeholder="Days (e.g. 7)"
                  value={newProtoDays}
                  onChange={(e) => setNewProtoDays(e.target.value)}
                />
                <select
                  className="vx-input"
                  style={{ minWidth: 0 }}
                  value={newProtoLock}
                  onChange={(e) => setNewProtoLock(e.target.value)}
                >
                  <option value="FULL">Full Lock</option>
                  <option value="HEAVY_WORK_ONLY">Heavy Work Only</option>
                </select>
              </div>
              <input
                className="vx-input"
                style={{ width: "100%", minWidth: 0 }}
                placeholder="Protocol note (optional)"
                value={newProtoNote}
                onChange={(e) => setNewProtoNote(e.target.value)}
              />
              <select
                className="vx-input"
                style={{ width: "100%", minWidth: 0 }}
                value={newProtoMedKey}
                onChange={(e) => setNewProtoMedKey(e.target.value)}
              >
                <option value="">-- Choose medicine to add --</option>
                {medCatalog.map((m) => (
                  <option key={m.supplyName} value={m.supplyName}>
                    {m.supplyName} ({m.unit})
                  </option>
                ))}
              </select>
              <div style={{ display: "grid", gridTemplateColumns: "90px 1fr auto", gap: "8px", alignItems: "center" }}>
                <input
                  className="vx-input"
                  style={{ minWidth: 0 }}
                  type="number"
                  min={0.5}
                  step="any"
                  value={newProtoMedQty}
                  onChange={(e) => setNewProtoMedQty(e.target.value)}
                  placeholder="Qty"
                />
                <input
                  className="vx-input"
                  style={{ minWidth: 0 }}
                  value={newProtoMedFreq}
                  onChange={(e) => setNewProtoMedFreq(e.target.value)}
                  placeholder="Frequency"
                />
                <button
                  type="button"
                  className="vx-btn"
                  style={{ padding: "8px 16px", whiteSpace: "nowrap" }}
                  onClick={addMedToNewProtocol}
                >
                  + Add
                </button>
              </div>
              {newProtoMeds.length > 0 && (
                <div style={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
                  {newProtoMeds.map((m, idx) => (
                    <span key={`${m.medicineName}-${idx}`} style={{ fontSize: "11.5px", background: "#f1f5f9", border: "1px solid #cbd5e1", borderRadius: "14px", padding: "3px 10px", display: "inline-flex", alignItems: "center", gap: "6px" }}>
                      {m.medicineName} × {m.dosage} ({m.frequency})
                      <button
                        type="button"
                        onClick={() => setNewProtoMeds((prev) => prev.filter((_, i) => i !== idx))}
                        style={{ border: "none", background: "transparent", cursor: "pointer", color: "#b91c1c", fontWeight: 700, padding: 0 }}
                        aria-label="Remove"
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>
              )}
              {newProtoError && (
                <div style={{ fontSize: "11.5px", color: "#b91c1c" }}>{newProtoError}</div>
              )}
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
                <button type="button" className="vx-btn" onClick={() => setShowNewProtocol(false)}>
                  Cancel
                </button>
                <button type="button" className="vx-btn vx-btn--primary" onClick={saveCustomProtocol}>
                  Save protocol
                </button>
              </div>
            </div>
          )}
        </div>

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
                const unusable = isOutOfStock || m.expired;
                return (
                  <option
                    key={m.supplyName}
                    value={m.supplyName}
                    disabled={unusable}
                    style={unusable ? { color: "#9ca3af" } : {}}
                  >
                    {m.supplyName} · {m.expired ? `[Expired ${m.expiryDate}]`
                      : isOutOfStock ? `[Out of stock - 0 ${m.unit}]` : `${m.currentQty} ${m.unit} in stock`}
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
              (chosenMedKey && (medCatalog.find((m) => m.supplyName === chosenMedKey)?.currentQty ?? 1) <= 0) ||
              (chosenMedKey && medCatalog.find((m) => m.supplyName === chosenMedKey)?.expired)
            }
            onClick={handleAddMedicine}
          >
            + Add medicine to treatment plan
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
              min={todayInputValue()}
              onChange={(e) => setStartDate(e.target.value)}
            />
          </label>

          <label className="vx-field">
            <span>End date <b style={{ color: "var(--v-pink)" }}>*</b></span>
            <input
              type="date"
              className="vx-input"
              required
              value={endDate}
              min={startDate || todayInputValue()}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </label>
        </div>

        <p className="vc-hint">
          {endDate
            ? `Treatment runs from ${formatDate(startDate)} to ${formatDate(endDate)}.`
            : "Required: Set an end date so the horse completes treatment and does not get stuck in recovery."}
        </p>

        {/* 5. Ghi chú đơn thuốc (tuỳ chọn - dành cho thuốc chưa có trong kho) */}
        <div className="vx-form-field" style={{ marginTop: "14px" }}>
          <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
            Note (optional)
          </label>
          <textarea
            className="vx-input vx-textarea"
            rows={3}
            style={{ width: "100%", boxSizing: "border-box", padding: "8px 12px" }}
            value={prescriptionNote}
            onChange={(e) => setPrescriptionNote(e.target.value)}
            placeholder="Special instructions or medications requested that are currently not in stock..."
          />
        </div>

        {/* 6. Training Lock Option */}
        <div className="vx-form-field" style={{ marginTop: "14px" }}>
          <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
            Training lock
          </label>
          <select
            className="vx-input"
            style={{ width: "100%", boxSizing: "border-box", padding: "8px 12px" }}
            value={trainingLock}
            onChange={(e) => setTrainingLock(e.target.value)}
          >
            <option value="FULL">Full lock — No training at all</option>
            <option value="NONE">No lock — Normal training permitted</option>
          </select>
          <p className="vx-meta" style={{ marginTop: "4px" }}>
            {trainingLock === "FULL"
              ? "The horse will be placed under Full training lock until treatment finishes or a vet releases it."
              : "No training lock will be issued. Normal workouts remain permitted."}
          </p>
        </div>

        {/* Actions */}
        <div className="vx-actions" style={{ marginTop: "20px" }}>
          <button
            type="button"
            className="vx-btn vx-btn--green"
            disabled={
              busy ||
              !recordId ||
              noRecords ||
              !startDate ||
              !endDate ||
              (selectedMeds.length === 0 && !chosenMedKey && !customMed.trim() && !prescriptionNote.trim())
            }
            onClick={submit}
          >
            {busy
              ? "Saving…"
              : `Save treatment plan (${selectedMeds.length || (chosenMedKey || customMed || prescriptionNote.trim() ? 1 : 0)} items)`}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
