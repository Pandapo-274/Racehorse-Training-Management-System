// src/features/care/StableMapPage.js
// Flow 4 - Groom's stable map: every stall with its horse, the approved ration
// for each meal and today's care tasks. The Groom ticks off their own tasks here.
//
// Only club horses that are still at the club have a stall; pedigree-only
// records and withdrawn horses are left out (same rule as the backend).
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { isPedigreeOnly, isWithdrawn, listHorses, toStatus } from "../horse/horseApi";
import {
  MEALS, MEAL_TEXT, TASK_TEXT, byHorse, completeTask, formatClock, formatQty,
  listFeeding, listTasks, stallBlock, todayIso, undoTask,
} from "./careApi";
import "./care.css";

function RationList({ items }) {
  if (!items?.length) return <p className="hz-muted hz-note">No approved ration yet.</p>;
  return (
    <ul className="care-meals">
      {MEALS.map((meal) => {
        const rows = items.filter((i) => i.mealTime === meal);
        if (!rows.length) return null;
        return (
          <li key={meal}>
            <b>{MEAL_TEXT[meal]}</b>
            <span>{rows.map((r) => `${r.feedType} ${formatQty(r.quantity)} ${r.unit}`).join(", ")}</span>
          </li>
        );
      })}
    </ul>
  );
}

function TaskList({ tasks, busyId, onToggle }) {
  if (!tasks?.length) return <p className="hz-muted hz-note">No tasks for you today.</p>;
  return (
    <ul className="care-tasks">
      {tasks.map((t) => (
        <li key={t.taskId} className={`care-task${t.completed ? " care-task--done" : ""}`}>
          <input
            type="checkbox"
            id={`task-${t.taskId}`}
            checked={t.completed}
            disabled={busyId === t.taskId}
            onChange={() => onToggle(t)}
          />
          <label htmlFor={`task-${t.taskId}`}>
            <span>{TASK_TEXT[t.taskType] || t.taskType}</span>
            {t.note && <span className="hz-muted"> · {t.note}</span>}
          </label>
          {t.completed && <small>Done {formatClock(t.completedAt)}</small>}
        </li>
      ))}
    </ul>
  );
}

export default function StableMapPage() {
  const navigate = useNavigate();
  const [horses, setHorses] = useState(null);
  const [feeding, setFeeding] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [busyId, setBusyId] = useState(null);
  const [mineOnly, setMineOnly] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([
      listHorses(ctrl.signal),
      listFeeding(null, ctrl.signal),
      listTasks({ date: todayIso() }, ctrl.signal),
    ])
      .then(([h, f, t]) => {
        setHorses(h);
        setFeeding(f);
        setTasks(t);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const rationBy = useMemo(() => byHorse(feeding), [feeding]);
  const tasksBy = useMemo(() => byHorse(tasks), [tasks]);

  const blocks = useMemo(() => {
    const stabled = (horses || [])
      .filter((h) => !isPedigreeOnly(h) && !isWithdrawn(h) && h.stallNo)
      .filter((h) => !mineOnly || tasksBy.has(h.horseId))
      .sort((a, b) => a.stallNo.localeCompare(b.stallNo, "en", { numeric: true }));
    const map = new Map();
    for (const h of stabled) {
      const b = stallBlock(h.stallNo);
      if (!map.has(b)) map.set(b, []);
      map.get(b).push(h);
    }
    return [...map.entries()];
  }, [horses, mineOnly, tasksBy]);

  const done = tasks.filter((t) => t.completed).length;

  const toggle = useCallback(async (task) => {
    setBusyId(task.taskId);
    setActionError("");
    try {
      const updated = task.completed ? await undoTask(task.taskId) : await completeTask(task.taskId);
      setTasks((old) => old.map((t) => (t.taskId === updated.taskId ? updated : t)));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setActionError(err.message);
    } finally {
      setBusyId(null);
    }
  }, [navigate]);

  return (
    <HorseShell
      title="Stable map"
      subtitle="Stalls, approved rations and today's care tasks"
      actions={
        <div className="hz-segment" role="group" aria-label="Which stalls">
          <button type="button" aria-pressed={!mineOnly} onClick={() => setMineOnly(false)}>All stalls</button>
          <button type="button" aria-pressed={mineOnly} onClick={() => setMineOnly(true)}>
            My tasks <b>{tasksBy.size}</b>
          </button>
        </div>
      }
    >
      {error && (
        <StateBlock kind="error" title="Could not load the stable" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !horses && <StateBlock title="Loading the stable…" />}

      {horses && (
        <>
          <div className="care-summary">
            <div className="hz-stat">
              <span>Today's tasks</span>
              <strong>{done} / {tasks.length}</strong>
              <div className="care-progress" aria-hidden="true">
                <i style={{ width: `${tasks.length ? (done / tasks.length) * 100 : 0}%` }} />
              </div>
            </div>
            <div className="hz-stat">
              <span>Stalls with my tasks</span>
              <strong>{tasksBy.size}</strong>
            </div>
            <div className="hz-stat">
              <span>Horses in the stable</span>
              <strong>{(horses || []).filter((h) => !isPedigreeOnly(h) && !isWithdrawn(h) && h.stallNo).length}</strong>
            </div>
          </div>

          {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}

          {blocks.length === 0 && (
            <Card><p className="hz-empty">{mineOnly ? "No tasks assigned to you today." : "No horses in the stable."}</p></Card>
          )}

          {blocks.map(([block, list]) => (
            <section key={block} className="care-block" aria-label={`Block ${block}`}>
              <h3>BLOCK {block}</h3>
              <div className="care-stalls">
                {list.map((h) => {
                  const status = toStatus(h.status);
                  const mine = tasksBy.get(h.horseId) || [];
                  const alert = h.status === "INJURED" || h.status === "QUARANTINE";
                  return (
                    <article
                      key={h.horseId}
                      className={`care-stall${alert ? " care-stall--alert" : mine.length ? " care-stall--mine" : ""}`}
                    >
                      <div className="care-stall__head">
                        <div>
                          <span className="care-stall__no">{h.stallNo}</span>
                          <Link className="care-stall__name" to={`/groom/horses/${h.horseId}`}>{h.horseName}</Link>
                        </div>
                        <StatusChip tone={status.tone} label={status.label} title={status.hint} />
                      </div>
                      <div>
                        <p className="care-section-label">Ration</p>
                        <RationList items={rationBy.get(h.horseId)} />
                      </div>
                      <div>
                        <p className="care-section-label">My tasks today</p>
                        <TaskList tasks={mine} busyId={busyId} onToggle={toggle} />
                      </div>
                      <div className="care-stall__foot">
                        <Link to={`/groom/incidents?horseId=${h.horseId}`}>Report a problem</Link>
                      </div>
                    </article>
                  );
                })}
              </div>
            </section>
          ))}
        </>
      )}
    </HorseShell>
  );
}
