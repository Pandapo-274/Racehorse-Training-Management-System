// src/features/care/StableCarePage.js
// Flow 4 - Head Trainer's stable care page.
//   Daily tasks: assign feeding / cleaning / bathing / ice bath to a Groom for a
//                horse and a day, see what is done, delete what is not needed.
//   Rations:     approve what each horse eats (FeedingBoard).
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import FeedingBoard from "./FeedingBoard";
import {
  TASK_TEXT, TASK_TYPES, assignTasks, deleteTask, formatClock, listGrooms, listTasks, todayIso,
} from "./careApi";
import "./care.css";

const EMPTY_FORM = { horseId: "", assignedTo: "", taskTypes: [], note: "" };

function AssignForm({ date, horses, grooms, onAssigned }) {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY_FORM);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [saving, setSaving] = useState(false);

  const toggleType = (t) =>
    setForm((f) => ({
      ...f,
      taskTypes: f.taskTypes.includes(t) ? f.taskTypes.filter((x) => x !== t) : [...f.taskTypes, t],
    }));

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    setInfo("");
    const local = {};
    if (!form.horseId) local.horseId = "Choose a horse";
    if (!form.assignedTo) local.assignedTo = "Choose a groom";
    if (!form.taskTypes.length) local.taskTypes = "Choose at least one task";
    setErrors(local);
    if (Object.keys(local).length) return;

    setSaving(true);
    try {
      const res = await assignTasks({
        horseId: Number(form.horseId),
        assignedTo: Number(form.assignedTo),
        taskDate: date,
        taskTypes: TASK_TYPES.filter((t) => form.taskTypes.includes(t)),
        note: form.note.trim() || null,
      });
      const parts = [];
      if (res.created.length) parts.push(`${res.created.length} task${res.created.length > 1 ? "s" : ""} assigned`);
      if (res.skipped.length) parts.push(`already planned: ${res.skipped.map((t) => TASK_TEXT[t]).join(", ")}`);
      setInfo(parts.join("; ") + ".");
      setForm((f) => ({ ...f, taskTypes: [], note: "" }));
      onAssigned(res.created);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.errors || {});
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="Assign care tasks" hint="Each horse gets at most one task of each kind per day.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="as-horse">Horse</label>
            <select id="as-horse" value={form.horseId} aria-invalid={!!errors.horseId}
              onChange={(e) => setForm((f) => ({ ...f, horseId: e.target.value }))}>
              <option value="">Choose a horse…</option>
              {horses.map((h) => <option key={h.horseId} value={h.horseId}>{h.stallNo} · {h.horseName}</option>)}
            </select>
            {errors.horseId && <p className="hz-field__error">{errors.horseId}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="as-groom">Groom</label>
            <select id="as-groom" value={form.assignedTo} aria-invalid={!!errors.assignedTo}
              onChange={(e) => setForm((f) => ({ ...f, assignedTo: e.target.value }))}>
              <option value="">Choose a groom…</option>
              {grooms.map((g) => <option key={g.userId} value={g.userId}>{g.fullName}</option>)}
            </select>
            {errors.assignedTo && <p className="hz-field__error">{errors.assignedTo}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="as-note">Note</label>
            <input id="as-note" value={form.note} maxLength={255} placeholder="Optional, e.g. after the gallop"
              onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} />
          </div>
          <div className="hz-field care-field-wide">
            <label id="as-types">Tasks</label>
            <div className="care-checks" role="group" aria-labelledby="as-types">
              {TASK_TYPES.map((t) => (
                <label key={t} className="care-check">
                  <input type="checkbox" checked={form.taskTypes.includes(t)} onChange={() => toggleType(t)} />
                  {TASK_TEXT[t]}
                </label>
              ))}
            </div>
            {errors.taskTypes && <p className="hz-field__error">{errors.taskTypes}</p>}
          </div>
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        {info && <p className="care-ok" role="status">{info}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Assigning…" : "Assign"}</button>
        </div>
      </form>
    </Card>
  );
}

function DailyTasks() {
  const navigate = useNavigate();
  const today = todayIso();
  const [date, setDate] = useState(today);
  const [tasks, setTasks] = useState(null);
  const [horses, setHorses] = useState([]);
  const [grooms, setGrooms] = useState([]);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [busyId, setBusyId] = useState(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    Promise.all([listHorses(ctrl.signal), listGrooms(ctrl.signal)])
      .then(([h, g]) => {
        setHorses(h.filter((x) => !isPedigreeOnly(x) && !isWithdrawn(x) && x.stallNo)
          .sort((a, b) => a.stallNo.localeCompare(b.stallNo, "en", { numeric: true })));
        setGrooms(g);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (!handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate]);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    setTasks(null);
    listTasks({ date }, ctrl.signal)
      .then(setTasks)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (!handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, date, reloadKey]);

  const done = useMemo(() => (tasks || []).filter((t) => t.completed).length, [tasks]);

  const remove = async (t) => {
    if (!window.confirm(`Delete "${TASK_TEXT[t.taskType]}" for ${t.horseName}?`)) return;
    setBusyId(t.taskId);
    setActionError("");
    try {
      await deleteTask(t.taskId);
      setTasks((old) => old.filter((x) => x.taskId !== t.taskId));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      {date >= today && (
        <AssignForm
          date={date}
          horses={horses}
          grooms={grooms}
          onAssigned={() => setReloadKey((k) => k + 1)}
        />
      )}

      <Card
        title="Care tasks"
        hint={tasks ? `${done} of ${tasks.length} done` : undefined}
        actions={
          <input type="date" className="hz-search" value={date} aria-label="Day"
            onChange={(e) => e.target.value && setDate(e.target.value)} />
        }
      >
        {error && (
          <StateBlock kind="error" title="Could not load tasks" onRetry={() => setReloadKey((k) => k + 1)}>
            {error}
          </StateBlock>
        )}
        {!error && !tasks && <p className="hz-empty">Loading…</p>}
        {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}
        {tasks && (
          <div className="hz-table-wrap">
            <table className="hz-table">
              <thead>
                <tr>
                  <th>Stall</th>
                  <th>Horse</th>
                  <th>Task</th>
                  <th>Groom</th>
                  <th>Status</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {tasks.map((t) => (
                  <tr key={t.taskId}>
                    <td className="hz-mono">{t.stallNo || "—"}</td>
                    <td className="hz-name">{t.horseName}</td>
                    <td>
                      {TASK_TEXT[t.taskType] || t.taskType}
                      {t.note && <p className="hz-note hz-muted">{t.note}</p>}
                    </td>
                    <td>{t.assignedToName}</td>
                    <td>
                      {t.completed
                        ? <StatusChip tone="good" label={`Done ${formatClock(t.completedAt)}`} />
                        : <StatusChip tone={date < today ? "serious" : "neutral"} label={date < today ? "Not done" : "To do"} />}
                    </td>
                    <td className="hz-row-action">
                      {!t.completed && (
                        <button type="button" className="care-link-btn care-link-btn--danger"
                          disabled={busyId === t.taskId} onClick={() => remove(t)}>
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {tasks.length === 0 && <p className="hz-empty">No care tasks for this day.</p>}
          </div>
        )}
      </Card>
    </>
  );
}

export default function StableCarePage() {
  const [tab, setTab] = useState("tasks");
  return (
    <HorseShell title="Stable care" subtitle="Daily care tasks for the grooms and approved rations">
      <div className="hz-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === "tasks"} className={tab === "tasks" ? "active" : ""}
          onClick={() => setTab("tasks")}>
          Daily tasks
        </button>
        <button type="button" role="tab" aria-selected={tab === "rations"} className={tab === "rations" ? "active" : ""}
          onClick={() => setTab("rations")}>
          Rations
        </button>
      </div>
      {tab === "tasks" ? <DailyTasks /> : <FeedingBoard editable />}
    </HorseShell>
  );
}
