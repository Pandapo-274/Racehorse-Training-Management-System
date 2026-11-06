// src/features/care/StableCarePage.js
// Flow 4 - Head Trainer's stable care page.
//   Daily tasks: assign feeding / cleaning / bathing / ice bath to a Groom for
//                several horses over up to 14 days at once, see what is done,
//                delete what is not needed (today or later only).
//   Rations:     approve what each horse eats (FeedingBoard).
//   Missed:      tasks of earlier days that were never ticked (#14).
// A task can be handed to another groom while it is open (#15). Assigning
// more than the daily limit to one groom shows a warning but is not blocked.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import FeedingBoard from "./FeedingBoard";
import {
  TASK_STATE, TASK_TEXT, TASK_TYPES, assignTasksBulk, deleteTask, formatClock, listGrooms, listMissedTasks,
  listTasks, reassignTask, todayIso,
} from "./careApi";
import "./care.css";

const EMPTY_FORM = { horseIds: [], assignedTo: "", taskTypes: [], note: "" };
/** Same limit as the backend (CareService.MAX_BULK_DAYS). */
const MAX_DAYS = 14;

function addDays(iso, n) {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + n);
  const p = (x) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * Assign one groom several horses for a period (e.g. stall cleaning for the
 * whole row every day this week) in one go. The day picked above the task list
 * is the first day; "Until" is the last (at most 14 days). A horse that already
 * has that kind of task on a day is skipped, not handed to the new groom.
 */
function AssignForm({ date, horses, grooms, onAssigned }) {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY_FORM);
  const [until, setUntil] = useState(date);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [saving, setSaving] = useState(false);

  // A new first day moves the period with it.
  useEffect(() => setUntil(date), [date]);

  const toggleType = (t) =>
    setForm((f) => ({
      ...f,
      taskTypes: f.taskTypes.includes(t) ? f.taskTypes.filter((x) => x !== t) : [...f.taskTypes, t],
    }));
  const toggleHorse = (id) =>
    setForm((f) => ({
      ...f,
      horseIds: f.horseIds.includes(id) ? f.horseIds.filter((x) => x !== id) : [...f.horseIds, id],
    }));
  const allChosen = horses.length > 0 && form.horseIds.length === horses.length;
  const toggleAll = () =>
    setForm((f) => ({ ...f, horseIds: allChosen ? [] : horses.map((h) => h.horseId) }));

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    setInfo("");
    const local = {};
    if (!form.horseIds.length) local.horseIds = "Choose at least one horse";
    if (!form.assignedTo) local.assignedTo = "Choose a groom";
    if (!form.taskTypes.length) local.taskTypes = "Choose at least one task";
    if (!until || until < date) local.toDate = "Must be on or after the first day";
    else if (until > addDays(date, MAX_DAYS - 1)) local.toDate = `At most ${MAX_DAYS} days at once`;
    setErrors(local);
    if (Object.keys(local).length) return;

    setSaving(true);
    try {
      const res = await assignTasksBulk({
        horseIds: form.horseIds,
        assignedTo: Number(form.assignedTo),
        fromDate: date,
        toDate: until,
        taskTypes: TASK_TYPES.filter((t) => form.taskTypes.includes(t)),
        note: form.note.trim() || null,
      });
      const parts = [`${res.created} task${res.created === 1 ? "" : "s"} assigned`];
      if (res.skipped) parts.push(`${res.skipped} already planned and left as they were (use Hand over to change the groom)`);
      if (res.notes?.length) parts.push(res.notes.join("; "));
      setInfo(parts.join("; ") + ".");
      setForm((f) => ({ ...f, taskTypes: [], note: "" }));
      onAssigned();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.fieldErrors || {});
    } finally {
      setSaving(false);
    }
  };

  const days = until >= date ? Math.round((new Date(`${until}T00:00:00`) - new Date(`${date}T00:00:00`)) / 86400000) + 1 : 0;

  return (
    <Card title="Assign care tasks"
      hint="Pick horses, a groom and the days. Each horse gets at most one task of each kind per day.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field care-field-wide">
            <label id="as-horses">
              Horses ({form.horseIds.length} of {horses.length})
            </label>
            <div className="care-checks" role="group" aria-labelledby="as-horses">
              <label className="care-check">
                <input type="checkbox" checked={allChosen} onChange={toggleAll} />
                All stabled horses
              </label>
              {horses.map((h) => (
                <label key={h.horseId} className="care-check">
                  <input type="checkbox" checked={form.horseIds.includes(h.horseId)}
                    onChange={() => toggleHorse(h.horseId)} />
                  {h.stallNo} · {h.horseName}
                </label>
              ))}
            </div>
            {errors.horseIds && <p className="hz-field__error">{errors.horseIds}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="as-until">Until (first day: {date})</label>
            <input id="as-until" type="date" value={until} min={date} max={addDays(date, MAX_DAYS - 1)}
              aria-invalid={!!errors.toDate} onChange={(e) => setUntil(e.target.value)} />
            {errors.toDate && <p className="hz-field__error">{errors.toDate}</p>}
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
          <button type="submit" className="hz-btn" disabled={saving}>
            {saving ? "Assigning…"
              : `Assign${form.horseIds.length && form.taskTypes.length && days
                ? ` ${form.horseIds.length * form.taskTypes.length * days} task(s)` : ""}`}
          </button>
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
  const [handing, setHanding] = useState(null); // taskId being handed over
  const [handTo, setHandTo] = useState("");
  const [actionInfo, setActionInfo] = useState("");

  const handOver = async (t) => {
    if (!handTo) return;
    setBusyId(t.taskId);
    setActionError("");
    setActionInfo("");
    try {
      const res = await reassignTask(t.taskId, Number(handTo));
      const after = res.created[0];
      setTasks((old) => old.map((x) => (x.taskId === t.taskId ? after : x)));
      setActionInfo(`${TASK_TEXT[t.taskType]} for ${t.horseName} handed to ${after.assignedToName}.`
        + (res.warnings?.length ? ` Warning: ${res.warnings.join("; ")}.` : ""));
      setHanding(null);
      setHandTo("");
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message);
    } finally {
      setBusyId(null);
    }
  };

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
        {actionInfo && <p className="care-ok" role="status">{actionInfo}</p>}
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
                    <td>
                      {handing === t.taskId ? (
                        <div className="care-inline">
                          <select value={handTo} aria-label={`New groom for ${t.horseName}`} onChange={(e) => setHandTo(e.target.value)}>
                            <option value="">Choose…</option>
                            {grooms.filter((g) => g.userId !== t.assignedTo).map((g) => (
                              <option key={g.userId} value={g.userId}>{g.fullName}</option>
                            ))}
                          </select>
                          <button type="button" className="hz-btn" disabled={!handTo || busyId === t.taskId} onClick={() => handOver(t)}>OK</button>
                          <button type="button" className="care-link-btn" onClick={() => { setHanding(null); setHandTo(""); }}>Cancel</button>
                        </div>
                      ) : t.assignedToName}
                    </td>
                    <td>
                      {t.completed
                        ? <StatusChip tone={t.state === "DONE_LATE" ? "warning" : "good"}
                            label={`${t.state === "DONE_LATE" ? "Done late" : "Done"} ${formatClock(t.completedAt)}`} />
                        : <StatusChip tone={TASK_STATE[t.state]?.tone || "neutral"} label={t.state === "MISSED" ? "Missed" : "To do"} />}
                      {t.completed && t.completedByName && t.completedBy !== t.assignedTo && (
                        <p className="hz-note hz-muted">ticked by {t.completedByName}</p>
                      )}
                    </td>
                    <td className="hz-row-action">
                      {!t.completed && date >= today && handing !== t.taskId && (
                        <button type="button" className="care-link-btn"
                          disabled={busyId === t.taskId} onClick={() => { setHanding(t.taskId); setHandTo(""); }}>
                          Hand over
                        </button>
                      )}
                      {/* A missed task of an earlier day stays on record (backend refuses the delete). */}
                      {!t.completed && date >= today && (
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

function MissedTasks() {
  const navigate = useNavigate();
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const ctrl = new AbortController();
    listMissedTasks({}, ctrl.signal)
      .then(setRows)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (!handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate]);

  const byGroom = useMemo(() => {
    const m = new Map();
    (rows || []).forEach((t) => m.set(t.assignedToName, (m.get(t.assignedToName) || 0) + 1));
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [rows]);

  return (
    <Card title="Missed tasks" hint="Tasks of the last 7 days that were never ticked. A task can still be ticked the day after; after that it stays missed.">
      {error && <p className="hz-form-error" role="alert">{error}</p>}
      {!rows && !error && <p className="hz-empty">Loading…</p>}
      {rows && rows.length === 0 && <p className="hz-empty">Nothing missed in the last 7 days.</p>}
      {rows && rows.length > 0 && (
        <>
          <p className="hz-note">{byGroom.map(([name, n]) => `${name}: ${n}`).join(" · ")}</p>
          <div className="hz-table-wrap">
            <table className="hz-table">
              <thead><tr><th>Day</th><th>Stall</th><th>Horse</th><th>Task</th><th>Groom</th></tr></thead>
              <tbody>
                {rows.map((t) => (
                  <tr key={t.taskId}>
                    <td className="hz-mono">{t.taskDate}</td>
                    <td className="hz-mono">{t.stallNo || "—"}</td>
                    <td className="hz-name">{t.horseName}</td>
                    <td>{TASK_TEXT[t.taskType] || t.taskType}</td>
                    <td>{t.assignedToName}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Card>
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
        <button type="button" role="tab" aria-selected={tab === "missed"} className={tab === "missed" ? "active" : ""}
          onClick={() => setTab("missed")}>
          Missed
        </button>
      </div>
      {tab === "tasks" && <DailyTasks />}
      {tab === "rations" && <FeedingBoard editable />}
      {tab === "missed" && <MissedTasks />}
    </HorseShell>
  );
}
