// src/features/care/FeedingBoard.js
// Flow 4 - approved rations for every stabled horse, one row per horse.
// With `editable` (Head Trainer, Vet) a ration can be replaced; saving makes
// the current user the approver. Grooms see the same table read-only.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { Card, StateBlock } from "../horse/HorseShell";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import { MEALS, MEAL_TEXT, byHorse, formatQty, listFeeding, saveRation } from "./careApi";
import "./care.css";

const EMPTY_ITEM = { mealTime: "MORNING", feedType: "", quantity: "", unit: "kg", note: "" };

function mealText(items, meal) {
  const rows = (items || []).filter((i) => i.mealTime === meal);
  if (!rows.length) return <span className="hz-muted">—</span>;
  return rows.map((r) => `${r.feedType} ${formatQty(r.quantity)} ${r.unit}`).join(", ");
}

function RationEditor({ horse, items, onSaved, onCancel }) {
  const navigate = useNavigate();
  const [rows, setRows] = useState(() =>
    items?.length
      ? items.map((i) => ({ mealTime: i.mealTime, feedType: i.feedType, quantity: String(formatQty(i.quantity)), unit: i.unit, note: i.note || "" }))
      : [{ ...EMPTY_ITEM }]
  );
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const set = (idx, key, value) =>
    setRows((old) => old.map((r, i) => (i === idx ? { ...r, [key]: value } : r)));

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    setErrors({});
    const local = {};
    rows.forEach((r, i) => {
      if (!r.feedType.trim()) local[`items[${i}].feedType`] = "Feed is required";
      if (!(Number(r.quantity) > 0)) local[`items[${i}].quantity`] = "Must be more than 0";
      if (!r.unit.trim()) local[`items[${i}].unit`] = "Unit is required";
    });
    if (Object.keys(local).length) {
      setErrors(local);
      return;
    }
    setSaving(true);
    try {
      const saved = await saveRation(horse.horseId, rows.map((r) => ({
        mealTime: r.mealTime,
        feedType: r.feedType.trim(),
        quantity: Number(r.quantity),
        unit: r.unit.trim(),
        note: r.note.trim() || null,
      })));
      onSaved(horse.horseId, saved);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.errors || {});
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title={`Ration for ${horse.horseName}`} hint={`Stall ${horse.stallNo}. Saving replaces the whole ration and records you as the approver.`}>
      <form onSubmit={submit} noValidate>
        {rows.map((r, i) => (
          <div className="care-ration-row" key={i}>
            <div className="hz-field">
              <label htmlFor={`meal-${i}`}>Meal</label>
              <select id={`meal-${i}`} value={r.mealTime} onChange={(e) => set(i, "mealTime", e.target.value)}>
                {MEALS.map((m) => <option key={m} value={m}>{MEAL_TEXT[m]}</option>)}
              </select>
            </div>
            <div className="hz-field">
              <label htmlFor={`feed-${i}`}>Feed</label>
              <input id={`feed-${i}`} value={r.feedType} maxLength={50} placeholder="Oats, hay, supplement…"
                aria-invalid={!!errors[`items[${i}].feedType`]} onChange={(e) => set(i, "feedType", e.target.value)} />
              {errors[`items[${i}].feedType`] && <p className="hz-field__error">{errors[`items[${i}].feedType`]}</p>}
            </div>
            <div className="hz-field">
              <label htmlFor={`qty-${i}`}>Quantity</label>
              <input id={`qty-${i}`} type="number" min="0.01" step="0.01" value={r.quantity}
                aria-invalid={!!errors[`items[${i}].quantity`]} onChange={(e) => set(i, "quantity", e.target.value)} />
              {errors[`items[${i}].quantity`] && <p className="hz-field__error">{errors[`items[${i}].quantity`]}</p>}
            </div>
            <div className="hz-field">
              <label htmlFor={`unit-${i}`}>Unit</label>
              <input id={`unit-${i}`} value={r.unit} maxLength={20}
                aria-invalid={!!errors[`items[${i}].unit`]} onChange={(e) => set(i, "unit", e.target.value)} />
            </div>
            <div className="hz-field">
              <label htmlFor={`note-${i}`}>Note</label>
              <input id={`note-${i}`} value={r.note} maxLength={255} placeholder="Optional"
                onChange={(e) => set(i, "note", e.target.value)} />
            </div>
            <button type="button" className="care-icon-btn" aria-label="Remove this item"
              disabled={rows.length === 1} onClick={() => setRows((old) => old.filter((_, j) => j !== i))}>
              ✕
            </button>
          </div>
        ))}
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="button" className="hz-btn hz-btn--ghost" disabled={rows.length >= 15}
            onClick={() => setRows((old) => [...old, { ...EMPTY_ITEM }])}>
            Add item
          </button>
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Saving…" : "Approve ration"}</button>
          <button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </Card>
  );
}

export default function FeedingBoard({ editable = false }) {
  const navigate = useNavigate();
  const [horses, setHorses] = useState(null);
  const [feeding, setFeeding] = useState([]);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [editing, setEditing] = useState(null);
  const [saved, setSaved] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([listHorses(ctrl.signal), listFeeding(null, ctrl.signal)])
      .then(([h, f]) => {
        setHorses(h);
        setFeeding(f);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const rationBy = useMemo(() => byHorse(feeding), [feeding]);
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (horses || [])
      .filter((h) => !isPedigreeOnly(h) && !isWithdrawn(h) && h.stallNo)
      .filter((h) => !q || h.horseName.toLowerCase().includes(q) || h.stallNo.toLowerCase().includes(q))
      .sort((a, b) => a.stallNo.localeCompare(b.stallNo, "en", { numeric: true }));
  }, [horses, query]);

  const onSaved = (horseId, items) => {
    setFeeding((old) => [...old.filter((f) => f.horseId !== horseId), ...items]);
    setSaved(`Ration for ${editing.horseName} approved.`);
    setEditing(null);
  };

  if (error) {
    return (
      <StateBlock kind="error" title="Could not load rations" onRetry={() => setReloadKey((k) => k + 1)}>
        {error}
      </StateBlock>
    );
  }
  if (!horses) return <StateBlock title="Loading rations…" />;

  return (
    <>
      {editing && (
        <RationEditor
          key={editing.horseId}
          horse={editing}
          items={rationBy.get(editing.horseId)}
          onSaved={onSaved}
          onCancel={() => setEditing(null)}
        />
      )}
      {saved && !editing && <p className="care-ok" role="status">{saved}</p>}

      <Card
        title="Approved rations"
        hint={editable ? "Rations approved by a Head Trainer or Vet. Grooms feed exactly this." : "Feed exactly this. Ask the Head Trainer or Vet to change a ration."}
        actions={
          <input className="hz-search" type="search" placeholder="Search horse or stall"
            value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search horse or stall" />
        }
      >
        <div className="hz-table-wrap">
          <table className="hz-table">
            <thead>
              <tr>
                <th>Stall</th>
                <th>Horse</th>
                {MEALS.map((m) => <th key={m}>{MEAL_TEXT[m]}</th>)}
                <th>Approved by</th>
                {editable && <th aria-label="Actions" />}
              </tr>
            </thead>
            <tbody>
              {rows.map((h) => {
                const items = rationBy.get(h.horseId);
                return (
                  <tr key={h.horseId}>
                    <td className="hz-mono">{h.stallNo}</td>
                    <td className="hz-name">{h.horseName}</td>
                    {MEALS.map((m) => <td key={m}>{mealText(items, m)}</td>)}
                    <td className="hz-muted">{items?.[0]?.approvedByName || "—"}</td>
                    {editable && (
                      <td className="hz-row-action">
                        <button type="button" className="hz-btn" onClick={() => { setSaved(""); setEditing(h); }}>
                          {items?.length ? "Edit" : "Set ration"}
                        </button>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
          {rows.length === 0 && <p className="hz-empty">No horses match.</p>}
        </div>
      </Card>
    </>
  );
}
