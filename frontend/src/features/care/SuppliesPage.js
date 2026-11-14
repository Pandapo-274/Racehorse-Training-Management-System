// src/features/care/SuppliesPage.js
// Flow 4 - feed, medicine and tools by storage area.
//   Club Manager: runs the store - add / edit items, count, record deliveries
//                 and stock thrown away, see each item's history.
//   Groom:        counts feed and tools (not medicine), reports problems.
//   Head Trainer: sees the store and its history, reports problems.
//   Veterinarian: sees the MEDICINE only (stock, minimum, expiry) to know what
//                 can be prescribed, and reports problems. The vet does not run
//                 the store: no counts, deliveries or waste (backend refuses too).
// Every change to a quantity is a line in the stock ledger (V21): a count, a
// delivery, waste, or a feeding task taking the ration. Editing an item never
// changes its quantity. "Restock suggested" = below the minimum; managers also
// get a daily store check while it stays low.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { formatDateTime } from "../horse/horseApi";
import {
  CATEGORY_TEXT, COUNT_UP_REASONS, MOVEMENT_TEXT, REPORT_REASONS, countSupply, createSupply, formatQty, listMovements, listSupplies,
  receiveSupply, reportSupply, todayIso, updateSupply, wasteSupply,
} from "./careApi";
import "./care.css";

function formatSupplyPrice(price, unit) {
  if (!price || Number(price) <= 0) return "—";
  return `${Number(price).toLocaleString("en-US")} VND / 1 ${unit || "unit"}`;
}

/** Who may count an item (mirrors SupplyService.count: a groom may not count medicine). */
function canCountItem(role, item) {
  if (role === "CLUB_MANAGER") return true;
  return role === "GROOM" && item.category !== "MEDICINE";
}

/** Area may be null in the database; sort and compare it as text. */
const areaText = (a) => a || "";

const EMPTY = {
  supplyName: "", category: "FEED", unit: "kg", minThreshold: "", currentQty: "", area: "", expiryDate: "", unitPrice: "",
};

/** Days from today to an ISO date (negative = past). */
function daysUntil(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  const [ty, tm, td] = todayIso().split("-").map(Number);
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(ty, tm - 1, td)) / 86400000);
}

function SupplyForm({ initial, areas, onSaved, onCancel }) {
  const navigate = useNavigate();
  const [form, setForm] = useState(() =>
    initial
      ? {
          ...initial,
          area: initial.area || "",
          minThreshold: formatQty(initial.minThreshold),
          currentQty: formatQty(initial.currentQty),
          expiryDate: initial.expiryDate || "",
          unitPrice: initial.unitPrice == null ? "" : formatQty(initial.unitPrice),
        }
      : EMPTY
  );
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    const local = {};
    if (!form.supplyName.trim()) local.supplyName = "Name is required";
    if (!form.unit.trim()) local.unit = "Unit is required";
    if (!form.area.trim()) local.area = "Area is required";
    if (form.minThreshold === "" || Number(form.minThreshold) < 0) local.minThreshold = "0 or more";
    if (!initial && (form.currentQty === "" || Number(form.currentQty) < 0)) local.currentQty = "0 or more";
    if (form.unitPrice !== "" && !(Number(form.unitPrice) >= 0)) local.unitPrice = "0 or more";
    setErrors(local);
    if (Object.keys(local).length) return;

    const body = {
      supplyName: form.supplyName.trim(),
      category: form.category,
      unit: form.unit.trim(),
      minThreshold: Number(form.minThreshold),
      // Only a new item takes a quantity; an edit leaves it to counts and deliveries.
      currentQty: initial ? null : Number(form.currentQty),
      area: form.area.trim(),
      expiryDate: form.category === "MEDICINE" && form.expiryDate ? form.expiryDate : null,
      // PUT /api/supplies/{id} writes unit_price as sent: leaving it out would wipe the price.
      unitPrice: form.unitPrice === "" ? null : Number(form.unitPrice),
    };
    setSaving(true);
    try {
      const saved = initial ? await updateSupply(initial.supplyId, body) : await createSupply(body);
      onSaved(saved, !initial);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.errors || {});
    } finally {
      setSaving(false);
    }
  };

  const field = (id, label, input, key) => (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      {input}
      {errors[key] && <p className="hz-field__error">{errors[key]}</p>}
    </div>
  );

  return (
    <Card title={initial ? `Edit ${initial.supplyName}` : "Add a supply item"}>
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          {field("sp-name", "Name", <input id="sp-name" value={form.supplyName} maxLength={100} onChange={set("supplyName")} aria-invalid={!!errors.supplyName} />, "supplyName")}
          {field("sp-cat", "Category", (
            <select id="sp-cat" value={form.category} onChange={set("category")}>
              {Object.entries(CATEGORY_TEXT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          ), "category")}
          {field("sp-area", "Storage area", (
            <>
              <input id="sp-area" list="sp-areas" value={form.area} maxLength={100} onChange={set("area")} aria-invalid={!!errors.area} />
              <datalist id="sp-areas">{areas.map((a) => <option key={a} value={a} />)}</datalist>
            </>
          ), "area")}
          {field("sp-unit", "Unit", <input id="sp-unit" value={form.unit} maxLength={20} onChange={set("unit")} aria-invalid={!!errors.unit} />, "unit")}
          {field("sp-min", "Minimum to keep", <input id="sp-min" type="number" min="0" step="0.01" value={form.minThreshold} onChange={set("minThreshold")} aria-invalid={!!errors.minThreshold} />, "minThreshold")}
          {!initial && field("sp-qty", "In stock now", <input id="sp-qty" type="number" min="0" step="0.01" value={form.currentQty} onChange={set("currentQty")} aria-invalid={!!errors.currentQty} />, "currentQty")}
          {field("sp-price", `Unit price (VND / 1 ${form.unit.trim() || "unit"})`, <input id="sp-price" type="number" min="0" step="1" value={form.unitPrice} onChange={set("unitPrice")} aria-invalid={!!errors.unitPrice} placeholder="Optional" />, "unitPrice")}
          {form.category === "MEDICINE" && field("sp-exp", "Expiry date", <input id="sp-exp" type="date" value={form.expiryDate} onChange={set("expiryDate")} aria-invalid={!!errors.expiryDate} />, "expiryDate")}
        </div>
        {initial && (
          <p className="hz-note hz-muted">
            In stock: {formatQty(initial.currentQty)} {initial.unit}. The quantity changes only through a count, a delivery or waste.
          </p>
        )}
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Saving…" : initial ? "Save" : "Add item"}</button>
          <button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </Card>
  );
}

/**
 * Groom (or head trainer, vet) flags an item to the club manager: running low,
 * damaged, expired. Medicine also goes to the vets. The quantity does not change;
 * the manager decides to restock, count or throw away. This is the spec's
 * "propose a restock" - the automatic "Restock suggested" only sees the numbers,
 * not a wet sack of feed or a cracked vial.
 */
function ReportForm({ item, onDone, onCancel }) {
  const navigate = useNavigate();
  const [reason, setReason] = useState(item.needsRestock ? "LOW" : item.expired ? "EXPIRED" : "");
  const [qty, setQty] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const medicine = item.category === "MEDICINE";

  const submit = async (e) => {
    e.preventDefault();
    if (!reason) {
      setError("Choose what is wrong");
      return;
    }
    if (reason === "OTHER" && !note.trim()) {
      setError("Describe the problem");
      return;
    }
    if (qty !== "" && !(Number(qty) > 0)) {
      setError("Quantity must be greater than 0");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await reportSupply(item.supplyId, {
        reason,
        qty: qty === "" ? null : Number(qty),
        note: note.trim() || null,
      });
      onDone(`${item.supplyName}: ${res.message}`);
    } catch (err) {
      if (err.status === 401) {
        handleAuthError(err, navigate);
        return;
      }
      setError(err.message || "Could not send the report");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title={`Report ${item.supplyName}`}
      hint={`On record: ${formatQty(item.currentQty)} ${item.unit}${item.area ? ` in ${item.area}` : ""}. `
        + `The club manager${medicine ? " and the vets" : ""} get a notification at once.`}
      actions={<button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>}
    >
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="sr-reason">What is wrong? *</label>
            <select id="sr-reason" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus>
              <option value="">Choose…</option>
              {Object.entries(REPORT_REASONS).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          {reason === "LOW" && (
            <div className="hz-field">
              <label htmlFor="sr-qty">About how much is needed ({item.unit})</label>
              <input id="sr-qty" type="number" min="0.01" step="0.01" placeholder="Optional"
                value={qty} onChange={(e) => setQty(e.target.value)} />
            </div>
          )}
          <div className="hz-field care-field-wide">
            <label htmlFor="sr-note">{reason === "OTHER" ? "What happened? *" : "Note (optional)"}</label>
            <input id="sr-note" value={note} maxLength={255} onChange={(e) => setNote(e.target.value)}
              placeholder="e.g. Two vials cracked on the shelf, the bottom sack is wet" />
          </div>
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy}>{busy ? "Sending…" : "Send report"}</button>
          <button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </Card>
  );
}

/** Count the shelf, receive a delivery, record waste, or show the ledger of one item. */
function StockAction({ kind, item, onDone, onCancel }) {
  const navigate = useNavigate();
  // A count starts from the number on record: the groom only changes it if the shelf differs.
  const [qty, setQty] = useState(kind === "count" ? formatQty(item.currentQty) : "");
  const [text, setText] = useState("");
  const [upReason, setUpReason] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [moves, setMoves] = useState(null);

  useEffect(() => {
    if (kind !== "history") return undefined;
    const ctrl = new AbortController();
    listMovements(item.supplyId, ctrl.signal)
      .then(setMoves)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (!handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [kind, item.supplyId, navigate]);

  if (kind === "history") {
    return (
      <Card title={`History of ${item.supplyName}`} hint="Last 100 changes, newest first."
        actions={<button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Close</button>}>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        {!moves && !error && <p className="hz-muted">Loading…</p>}
        {moves && (
          <div className="hz-table-wrap">
            <table className="hz-table">
              <thead><tr><th>When</th><th>What</th><th>Change</th><th>After</th><th>By</th><th>Reason</th></tr></thead>
              <tbody>
                {moves.map((m) => (
                  <tr key={m.movementId}>
                    <td className="hz-muted">{formatDateTime(m.createdAt)}</td>
                    <td>{MOVEMENT_TEXT[m.movementType] || m.movementType}</td>
                    <td className={Number(m.qtyDelta) < 0 ? "care-neg" : "care-pos"}>
                      {Number(m.qtyDelta) > 0 ? "+" : ""}{formatQty(m.qtyDelta)} {item.unit}
                    </td>
                    <td>{formatQty(m.qtyAfter)}</td>
                    <td>{m.createdByName}</td>
                    <td className="hz-muted">{m.reason ? m.reason.replace(" @ ", " - ") : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {moves.length === 0 && <p className="hz-empty">No changes recorded yet.</p>}
          </div>
        )}
      </Card>
    );
  }

  const isRestock = kind === "restock" || kind === "receive";
  const isCount = kind === "count";
  const qtyNum = Number(qty);
  const qtyOk = qty !== "" && Number.isFinite(qtyNum) && (isCount ? qtyNum >= 0 : qtyNum > 0);
  /* More than on record: stock does not appear by itself, so say why (backend requires it). */
  const countUp = isCount && qtyOk && qtyNum > Number(item.currentQty);
  const fixedPrice = Number(item.unitPrice) || 0;
  const totalCost = qtyOk ? qtyNum * fixedPrice : 0;

  const submit = async (e) => {
    e.preventDefault();
    if (!qtyOk) {
      setError(isCount ? "Enter the quantity on the shelf (0 or more)" : "Quantity must be greater than 0");
      return;
    }
    if (kind === "waste" && qtyNum > Number(item.currentQty)) {
      setError(`Only ${formatQty(item.currentQty)} ${item.unit} in stock`);
      return;
    }
    if (kind === "waste" && !text.trim()) {
      setError("Please specify reason for disposal");
      return;
    }
    if (countUp && !upReason) {
      setError("The count is higher than the record. Choose why there is more");
      return;
    }
    if (countUp && upReason === "OTHER" && !text.trim()) {
      setError("Describe why there is more than on record");
      return;
    }

    setBusy(true);
    setError("");
    try {
      if (isCount) {
        const saved = await countSupply(item.supplyId, qtyNum,
          countUp ? upReason : null, countUp && text.trim() ? text.trim() : null);
        const diff = Number(saved.currentQty) - Number(item.currentQty);
        onDone(saved, diff === 0
          ? `${saved.supplyName}: count matches the record (${formatQty(saved.currentQty)} ${saved.unit}).`
          : `${saved.supplyName} counted: ${formatQty(item.currentQty)} -> ${formatQty(saved.currentQty)} ${saved.unit}.`);
      } else if (isRestock) {
        // Quantity is always in the item's own unit and the item keeps its area:
        // the backend adds qty to current_qty as it is.
        const saved = await receiveSupply(item.supplyId, {
          qty: qtyNum,
          unitPrice: fixedPrice > 0 ? fixedPrice : null,
          note: text.trim() || null,
        });
        const costStr = totalCost > 0 ? ` (Total: ${totalCost.toLocaleString("en-US")} VND)` : "";
        onDone(saved, `Restocked +${formatQty(qtyNum)} ${saved.unit} of ${saved.supplyName}${costStr}.`);
      } else {
        const saved = await wasteSupply(item.supplyId, qtyNum, text.trim());
        onDone(saved, `${formatQty(qtyNum)} ${item.unit} of ${saved.supplyName} recorded as thrown away.`);
      }
    } catch (err) {
      if (err.status === 401) {
        handleAuthError(err, navigate);
        return;
      }
      setError(err.message || "Could not save");
    } finally {
      setBusy(false);
    }
  };

  const title = isCount ? `Count ${item.supplyName}` : isRestock ? `Restock ${item.supplyName}` : `Throw away ${item.supplyName}`;
  const hint = isCount
    ? `On record: ${formatQty(item.currentQty)} ${item.unit}. Enter what is on the shelf now; a difference is written to the history.`
    : `In stock: ${formatQty(item.currentQty)} ${item.unit}${item.area ? ` in ${item.area}` : ""}.`;

  return (
    <Card
      title={title}
      hint={hint}
      actions={<button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>}
    >
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="sa-qty">
              {isCount ? `On the shelf (${item.unit}) *` : isRestock ? `Quantity received (${item.unit}) *` : `Quantity (${item.unit}) *`}
            </label>
            <input
              id="sa-qty"
              type="number"
              min={isCount ? "0" : "0.01"}
              step="0.01"
              placeholder={isRestock ? "e.g. 50" : "Quantity"}
              value={qty}
              onChange={(e) => setQty(e.target.value)}
              autoFocus
            />
          </div>

          {isRestock && (
            <div className="hz-field">
              <label htmlFor="sa-price">Unit price</label>
              <input
                id="sa-price"
                type="text"
                readOnly
                disabled
                value={fixedPrice > 0 ? formatSupplyPrice(fixedPrice, item.unit) : "Not set"}
              />
            </div>
          )}

          {countUp && (
            <>
              <div className="hz-field">
                <label htmlFor="sa-up">Why is there more than on record? *</label>
                <select id="sa-up" value={upReason} onChange={(e) => setUpReason(e.target.value)}>
                  <option value="">Choose…</option>
                  {Object.entries(COUNT_UP_REASONS).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </div>
              <div className="hz-field care-field-wide">
                <label htmlFor="sa-up-note">{upReason === "OTHER" ? "What happened? *" : "Note (optional)"}</label>
                <input id="sa-up-note" value={text} maxLength={255} onChange={(e) => setText(e.target.value)}
                  placeholder="e.g. Two sacks were in Store B" />
              </div>
              <p className="hz-note care-warn care-field-wide">
                Stock does not appear by itself. If a delivery arrived, use Restock instead so its cost is recorded.
              </p>
            </>
          )}

          {!isCount && (
            <div className="hz-field care-field-wide">
              <label htmlFor="sa-text">{isRestock ? "Note / Supplier / Invoice (optional)" : "Reason *"}</label>
              <input
                id="sa-text"
                value={text}
                maxLength={255}
                onChange={(e) => setText(e.target.value)}
                placeholder={isRestock ? "e.g. Supplier VetPharma, Invoice #4892" : "Spoiled, expired, broken…"}
              />
            </div>
          )}
        </div>

        {isRestock && (
          <div className="care-restock-summary">
            <span>Total restock expense:</span>
            <strong>{totalCost.toLocaleString("en-US")} VND</strong>
            <small className="hz-muted">(Report will be sent to Club Manager)</small>
          </div>
        )}

        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy || !qtyOk}>
            {busy ? "Saving…" : isCount ? "Save count" : isRestock ? "Record delivery" : "Record waste"}
          </button>
          <button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </Card>
  );
}

export default function SuppliesPage() {
  const navigate = useNavigate();
  const role = getUser()?.role;
  const isManager = role === "CLUB_MANAGER";
  /* The vet only sees medicine: what is in the cabinet, what runs low, what expires. */
  const isVet = role === "VETERINARIAN";
  const canRestock = isManager;
  const canWaste = isManager;
  const canSeeHistory = ["CLUB_MANAGER", "HEAD_TRAINER"].includes(role);
  const canCountAny = ["CLUB_MANAGER", "GROOM"].includes(role);
  /* POST /api/supplies/{id}/report: everyone who is not the manager can flag an item to the manager. */
  const canReport = ["GROOM", "HEAD_TRAINER", "VETERINARIAN"].includes(role);
  const hasActions = canRestock || canWaste || canSeeHistory || isManager || canCountAny || canReport;
  const open = (kind, item) => { setInfo(""); setEditing(null); setAction({ kind, item }); };
  const [action, setAction] = useState(null); // null | { kind, item }

  const [items, setItems] = useState(null);
  const [error, setError] = useState("");
  const [area, setArea] = useState("");
  const [category, setCategory] = useState("");
  const [lowOnly, setLowOnly] = useState(false);
  const [editing, setEditing] = useState(null); // null | "new" | item
  const [info, setInfo] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    listSupplies(isVet ? { category: "MEDICINE" } : {}, ctrl.signal)
      .then((list) => setItems(isVet ? list.filter((i) => i.category === "MEDICINE") : list))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey, isVet]);

  const areas = useMemo(() => [...new Set((items || []).map((i) => i.area).filter(Boolean))].sort(), [items]);
  const lowCount = (items || []).filter((i) => i.needsRestock).length;
  const shown = useMemo(() => (items || [])
    .filter((i) => !area || i.area === area)
    .filter((i) => !category || i.category === category)
    .filter((i) => !lowOnly || i.needsRestock), [items, area, category, lowOnly]);

  const replace = (saved) =>
    setItems((old) => {
      const exists = old.some((i) => i.supplyId === saved.supplyId);
      const next = exists ? old.map((i) => (i.supplyId === saved.supplyId ? saved : i)) : [saved, ...old];
      return [...next].sort((a, b) => Number(b.needsRestock) - Number(a.needsRestock)
        || areaText(a.area).localeCompare(areaText(b.area)) || a.supplyName.localeCompare(b.supplyName));
    });

  return (
    <HorseShell
      title={isManager ? "Supplies & feed" : isVet ? "Medicine" : "Supplies"}
      subtitle={isManager ? "Stock by storage area, deliveries, and what needs restocking"
        : isVet ? "Medicine in the club's store: stock, minimum and expiry. The club manager restocks; report what runs low, is damaged or expired"
          : canCountAny ? "Check the shelves, update the counts and report what is missing or damaged"
            : "Stock by storage area; report what is missing or damaged"}
      actions={isManager && editing !== "new" && (
        <button type="button" className="hz-btn" onClick={() => { setInfo(""); setEditing("new"); }}>Add item</button>
      )}
    >
      {error && (
        <StateBlock kind="error" title="Could not load supplies" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !items && <StateBlock title="Loading supplies…" />}

      {items && (
        <>
          <div className="care-summary">
            <div className="hz-stat"><span>{isVet ? "Medicines" : "Items"}</span><strong>{items.length}</strong></div>
            <div className="hz-stat"><span>Restock suggested</span><strong>{lowCount}</strong><small>below the minimum</small></div>
            <div className="hz-stat"><span>Storage areas</span><strong>{areas.length}</strong></div>
          </div>

          {editing && (
            <SupplyForm
              key={editing === "new" ? "new" : editing.supplyId}
              initial={editing === "new" ? null : editing}
              areas={areas}
              onCancel={() => setEditing(null)}
              onSaved={(saved, created) => {
                replace(saved);
                setEditing(null);
                setInfo(created ? `${saved.supplyName} added.` : `${saved.supplyName} saved.`);
              }}
            />
          )}
          {action && action.kind === "report" && (
            <ReportForm
              key={`report-${action.item.supplyId}`}
              item={action.item}
              onCancel={() => setAction(null)}
              onDone={(message) => { setAction(null); setInfo(message); }}
            />
          )}
          {action && action.kind !== "report" && (
            <StockAction
              key={`${action.kind}-${action.item.supplyId}`}
              kind={action.kind}
              item={action.item}
              onCancel={() => setAction(null)}
              onDone={(saved, message) => { replace(saved); setAction(null); setInfo(message); }}
            />
          )}
          {info && <p className="care-ok" role="status">{info}</p>}

          <Card
            title="Stock"
            actions={
              <div className="hz-filters">
                <select className="hz-search" value={area} onChange={(e) => setArea(e.target.value)} aria-label="Storage area">
                  <option value="">All areas</option>
                  {areas.map((a) => <option key={a} value={a}>{a}</option>)}
                </select>
                {!isVet && (
                  <select className="hz-search" value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category">
                    <option value="">All categories</option>
                    {Object.entries(CATEGORY_TEXT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                )}
                <div className="hz-segment" role="group" aria-label="Which items">
                  <button type="button" aria-pressed={!lowOnly} onClick={() => setLowOnly(false)}>All</button>
                  <button type="button" aria-pressed={lowOnly} onClick={() => setLowOnly(true)}>Restock <b>{lowCount}</b></button>
                </div>
              </div>
            }
          >
            <div className="hz-table-wrap">
              <table className="hz-table care-stock-table">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Area</th>
                    <th>Price</th>
                    <th>In stock</th>
                    <th>Status</th>
                    <th>Last change</th>
                    {hasActions && <th className="care-actions-head">Actions</th>}
                  </tr>
                </thead>
                <tbody>
                  {shown.map((i) => (
                    <tr key={i.supplyId} className={i.needsRestock ? "care-row--low" : ""}>
                      <td>
                        <span className="hz-name">{i.supplyName}</span>
                        <p className="hz-note hz-muted">{CATEGORY_TEXT[i.category] || i.category}</p>
                      </td>
                      <td>{i.area || "—"}</td>
                      <td className="care-price-cell">
                        <strong>{formatSupplyPrice(i.unitPrice, i.unit)}</strong>
                      </td>
                      <td className="care-stock-cell">
                        <strong>{formatQty(i.currentQty)}</strong> {i.unit}
                        <p className="hz-note hz-muted">min {formatQty(i.minThreshold)}</p>
                      </td>
                      <td>
                        {i.needsRestock
                          ? <StatusChip tone="warning" label="Restock suggested" />
                          : <StatusChip tone="good" label="OK" />}
                        {i.expiryDate && (i.expired
                          ? <p className="hz-note care-neg">Expired {i.expiryDate}</p>
                          : daysUntil(i.expiryDate) <= 30
                            ? <p className="hz-note care-warn">Expires {i.expiryDate}</p>
                            : <p className="hz-note hz-muted">Expires {i.expiryDate}</p>)}
                      </td>
                      <td className="hz-muted">
                        {i.updatedAt ? formatDateTime(i.updatedAt) : "—"}
                        {i.updatedByName && <p className="hz-note">{i.updatedByName}</p>}
                      </td>
                      {hasActions && (
                        <td className="hz-row-action">
                          <div className="care-btn-group care-stock-actions">
                            {canCountItem(role, i) && (
                              <button type="button" className="hz-btn hz-btn--ghost care-btn-pill" onClick={() => open("count", i)}>
                                Count
                              </button>
                            )}
                            {canRestock && (
                              <button type="button" className="hz-btn care-btn-pill" onClick={() => open("restock", i)}>
                                Restock
                              </button>
                            )}
                            {canWaste && Number(i.currentQty) > 0 && (
                              <button type="button" className="hz-btn hz-btn--ghost care-btn-pill care-btn-pill--waste"
                                onClick={() => open("waste", i)}>
                                Throw away
                              </button>
                            )}
                            {canReport && (
                              <button type="button" className="hz-btn hz-btn--ghost care-btn-pill" onClick={() => open("report", i)}>
                                Report
                              </button>
                            )}
                            {canSeeHistory && (
                              <button type="button" className="hz-btn hz-btn--ghost care-btn-pill" onClick={() => open("history", i)}>
                                History
                              </button>
                            )}
                            {isManager && (
                              <button type="button" className="hz-btn hz-btn--ghost care-btn-pill"
                                onClick={() => { setInfo(""); setAction(null); setEditing(i); }}>
                                Edit
                              </button>
                            )}
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
              {shown.length === 0 && <p className="hz-empty">No items match.</p>}
            </div>
          </Card>
        </>
      )}
    </HorseShell>
  );
}