// src/features/care/SuppliesPage.js
// Flow 4 - feed, medicine and tools by storage area.
//   Groom (and Vet): update the count after checking the shelf.
//   Club Manager:    also add items and edit name / unit / minimum / area.
// "Restock suggested" = below the minimum. A count that drops an item below
// its minimum notifies every Club Manager (backend). No purchasing or prices.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { formatDateTime } from "../horse/horseApi";
import { CATEGORY_TEXT, countSupply, createSupply, formatQty, listSupplies, updateSupply } from "./careApi";
import "./care.css";

const EMPTY = { supplyName: "", category: "FEED", unit: "kg", minThreshold: "", currentQty: "", area: "" };

function SupplyForm({ initial, areas, onSaved, onCancel }) {
  const navigate = useNavigate();
  const [form, setForm] = useState(() =>
    initial
      ? { ...initial, minThreshold: formatQty(initial.minThreshold), currentQty: formatQty(initial.currentQty) }
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
    if (form.currentQty === "" || Number(form.currentQty) < 0) local.currentQty = "0 or more";
    setErrors(local);
    if (Object.keys(local).length) return;

    const body = {
      supplyName: form.supplyName.trim(),
      category: form.category,
      unit: form.unit.trim(),
      minThreshold: Number(form.minThreshold),
      currentQty: Number(form.currentQty),
      area: form.area.trim(),
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
          {field("sp-qty", "In stock now", <input id="sp-qty" type="number" min="0" step="0.01" value={form.currentQty} onChange={set("currentQty")} aria-invalid={!!errors.currentQty} />, "currentQty")}
        </div>
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

export default function SuppliesPage() {
  const navigate = useNavigate();
  const role = getUser()?.role;
  const isManager = role === "CLUB_MANAGER";
  const canCount = ["CLUB_MANAGER", "VETERINARIAN", "GROOM"].includes(role);

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
      subtitle={isManager ? "Stock by storage area, and what needs restocking" : "Check the shelves and update the counts"}
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
                    <th>Last counted</th>
                    {(canCount || isManager) && <th aria-label="Actions" />}
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
                      </td>
                      <td className="hz-muted">
                        {i.updatedAt ? formatDateTime(i.updatedAt) : "—"}
                        {i.updatedByName && <p className="hz-note">{i.updatedByName}</p>}
                      </td>
                      {(canCount || isManager) && (
                        <td className="hz-row-action">
                          {canCount && <CountCell item={i} onCounted={replace} />}
                          {isManager && (
                            <button type="button" className="care-link-btn" onClick={() => { setInfo(""); setEditing(i); }}>
                              Edit item
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
