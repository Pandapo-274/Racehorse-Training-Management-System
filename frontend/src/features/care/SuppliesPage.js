// src/features/care/SuppliesPage.js
// Flow 4 - feed, medicine and tools by storage area.
//   Groom (and Vet): update the count after checking the shelf. Medicine is
//   counted by a vet or the manager only.
//   Club Manager:    also add items, edit name / unit / minimum / area / expiry,
//                    record deliveries.
//   Manager and Vet: record stock thrown away, see each item's history.
// Every change to a quantity is a line in the stock ledger (V21): a count, a
// delivery, waste, or a feeding task taking the ration. Editing an item never
// changes its quantity. "Restock suggested" = below the minimum; managers also
// get a daily store check while it stays low. No purchasing or prices.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { formatDateTime } from "../horse/horseApi";
import {
  CATEGORY_TEXT, MOVEMENT_TEXT, countSupply, createSupply, formatQty, listMovements, listSupplies,
  receiveSupply, todayIso, updateSupply, wasteSupply,
} from "./careApi";
import "./care.css";

const EMPTY = { supplyName: "", category: "FEED", unit: "kg", minThreshold: "", currentQty: "", area: "", expiryDate: "" };

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
      ? { ...initial, minThreshold: formatQty(initial.minThreshold), currentQty: formatQty(initial.currentQty), expiryDate: initial.expiryDate || "" }
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

function CountCell({ item, onCounted }) {
  const navigate = useNavigate();
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const save = async () => {
    if (value === "" || Number(value) < 0) {
      setError("0 or more");
      return;
    }
    setBusy(true);
    setError("");
    try {
      onCounted(await countSupply(item.supplyId, Number(value)));
      setValue("");
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="care-inline">
        <input className="care-qty-input" type="number" min="0" step="0.01" value={value}
          placeholder={formatQty(item.currentQty)} aria-label={`New count for ${item.supplyName}`}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") save(); }} />
        <button type="button" className="hz-btn" disabled={busy || value === ""} onClick={save}>Update</button>
      </div>
      {error && <p className="hz-field__error">{error}</p>}
    </div>
  );
}

/** Receive a delivery, record waste, or show the ledger of one item. */
function StockAction({ kind, item, onDone, onCancel }) {
  const navigate = useNavigate();
  const [qty, setQty] = useState("");
  const [text, setText] = useState("");
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
                    <td className="hz-muted">{m.reason || "—"}</td>
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

  const receiving = kind === "receive";
  const submit = async (e) => {
    e.preventDefault();
    if (!(Number(qty) > 0)) { setError("Quantity must be more than 0"); return; }
    if (!receiving && !text.trim()) { setError("Say why it is thrown away"); return; }
    setBusy(true);
    setError("");
    try {
      const saved = receiving
        ? await receiveSupply(item.supplyId, Number(qty), text.trim())
        : await wasteSupply(item.supplyId, Number(qty), text.trim());
      onDone(saved, receiving
        ? `${formatQty(qty)} ${item.unit} of ${saved.supplyName} received.`
        : `${formatQty(qty)} ${item.unit} of ${saved.supplyName} recorded as thrown away.`);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title={receiving ? `Receive ${item.supplyName}` : `Throw away ${item.supplyName}`}
      hint={`In stock: ${formatQty(item.currentQty)} ${item.unit}.`}>
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="sa-qty">Quantity ({item.unit})</label>
            <input id="sa-qty" type="number" min="0.01" step="0.01" value={qty} onChange={(e) => setQty(e.target.value)} />
          </div>
          <div className="hz-field">
            <label htmlFor="sa-text">{receiving ? "Note (supplier, invoice no.)" : "Reason"}</label>
            <input id="sa-text" value={text} maxLength={255} onChange={(e) => setText(e.target.value)}
              placeholder={receiving ? "Optional" : "Spoiled, expired, broken…"} />
          </div>
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy}>{busy ? "Saving…" : receiving ? "Receive" : "Record waste"}</button>
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
  const canCount = ["CLUB_MANAGER", "VETERINARIAN", "GROOM"].includes(role);
  const canWaste = ["CLUB_MANAGER", "VETERINARIAN"].includes(role);
  const canSeeHistory = ["CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN"].includes(role);
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
    listSupplies({}, ctrl.signal)
      .then(setItems)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const areas = useMemo(() => [...new Set((items || []).map((i) => i.area))].sort(), [items]);
  const lowCount = (items || []).filter((i) => i.needsRestock).length;
  const shown = useMemo(() => (items || [])
    .filter((i) => !area || i.area === area)
    .filter((i) => !category || i.category === category)
    .filter((i) => !lowOnly || i.needsRestock), [items, area, category, lowOnly]);

  const replace = (saved) =>
    setItems((old) => {
      const exists = old.some((i) => i.supplyId === saved.supplyId);
      const next = exists ? old.map((i) => (i.supplyId === saved.supplyId ? saved : i)) : [saved, ...old];
      return [...next].sort((a, b) => Number(b.needsRestock) - Number(a.needsRestock) || a.area.localeCompare(b.area) || a.supplyName.localeCompare(b.supplyName));
    });

  return (
    <HorseShell
      title={isManager ? "Supplies & feed" : "Supplies"}
      subtitle={isManager ? "Stock by storage area, deliveries, and what needs restocking" : "Check the shelves and update the counts"}
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
            <div className="hz-stat"><span>Items</span><strong>{items.length}</strong></div>
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
          {action && (
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
                <select className="hz-search" value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category">
                  <option value="">All categories</option>
                  {Object.entries(CATEGORY_TEXT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
                <div className="hz-segment" role="group" aria-label="Which items">
                  <button type="button" aria-pressed={!lowOnly} onClick={() => setLowOnly(false)}>All</button>
                  <button type="button" aria-pressed={lowOnly} onClick={() => setLowOnly(true)}>Restock <b>{lowCount}</b></button>
                </div>
              </div>
            }
          >
            <div className="hz-table-wrap">
              <table className="hz-table">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Area</th>
                    <th>In stock</th>
                    <th>Minimum</th>
                    <th>Status</th>
                    <th>Last change</th>
                    {(canCount || isManager || canSeeHistory) && <th aria-label="Actions" />}
                  </tr>
                </thead>
                <tbody>
                  {shown.map((i) => (
                    <tr key={i.supplyId} className={i.needsRestock ? "care-row--low" : ""}>
                      <td>
                        <span className="hz-name">{i.supplyName}</span>
                        <p className="hz-note hz-muted">{CATEGORY_TEXT[i.category] || i.category}</p>
                      </td>
                      <td>{i.area}</td>
                      <td className="hz-name">{formatQty(i.currentQty)} {i.unit}</td>
                      <td className="hz-muted">{formatQty(i.minThreshold)} {i.unit}</td>
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
                      {(canCount || isManager || canSeeHistory) && (
                        <td className="hz-row-action">
                          {canCount && (role !== "GROOM" || i.category !== "MEDICINE")
                            ? <CountCell item={i} onCounted={replace} />
                            : canCount && <small className="care-muted">Counted by vet</small>}
                          {isManager && (
                            <button type="button" className="care-link-btn" onClick={() => { setInfo(""); setAction(null); setEditing(i); }}>
                              Edit item
                            </button>
                          )}
                          {isManager && (
                            <button type="button" className="care-link-btn" onClick={() => { setInfo(""); setEditing(null); setAction({ kind: "receive", item: i }); }}>
                              Receive
                            </button>
                          )}
                          {canWaste && Number(i.currentQty) > 0 && (
                            <button type="button" className="care-link-btn" onClick={() => { setInfo(""); setEditing(null); setAction({ kind: "waste", item: i }); }}>
                              Throw away
                            </button>
                          )}
                          {canSeeHistory && (
                            <button type="button" className="care-link-btn" onClick={() => { setInfo(""); setEditing(null); setAction({ kind: "history", item: i }); }}>
                              History
                            </button>
                          )}
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
