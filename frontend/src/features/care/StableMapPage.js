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

// Danh sách món ăn từng bữa của một ngựa (khẩu phần đã duyệt).
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

// Việc chăm sóc hôm nay của Groom này cho một ngựa, mỗi việc một ô tick (khoá khi đang gửi request).
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

/*
 * Trang /groom/stable. Tải song song: danh sách ngựa, khẩu phần, việc chăm sóc HÔM NAY (backend chỉ trả việc của
 * Groom đang đăng nhập). Ngựa được gom theo dãy chuồng (stallBlock: "A-06" -> dãy "A") để vẽ như sơ đồ chuồng.
 * mineOnly: chỉ hiện ngựa có việc của mình hôm nay.
 */
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

  // Tick / bỏ tick: PUT complete hoặc undo, rồi thay đúng việc đó trong state bằng bản backend trả về.
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
      subtitle="Your Head Trainer's horses: stalls, approved rations and today's care tasks"
      actions={
        <div className="hz-segment" role="group" aria-label="Which stalls">
          <button type="button" aria-pressed={!mineOnly} onClick={() => setMineOnly(false)}>Team stalls</button>
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
          {/* Một dòng tóm tắt thay cho ba ô số lớn; số ngựa có việc đã nằm trên nút "My tasks". */}
          <div className="care-overview" role="status">
            <span>
              <strong>{(horses || []).filter((h) => !isPedigreeOnly(h) && !isWithdrawn(h) && h.stallNo).length}</strong>
              {" "}horses in your team
            </span>
            <span className="care-overview__dot" aria-hidden="true">·</span>
            {tasks.length === 0 ? (
              <span>No care tasks for you today</span>
            ) : (
              <>
                <span><strong>{done} / {tasks.length}</strong> care tasks done today</span>
                <div className="care-progress" aria-hidden="true">
                  <i style={{ width: `${(done / tasks.length) * 100}%` }} />
                </div>
              </>
            )}
          </div>

          {actionError && <p className="hz-form-error" role="alert">{actionError}</p>}

          {blocks.length === 0 && (
            <Card><p className="hz-empty">{mineOnly ? "No tasks assigned to you today."
              : "No horses in your team. If you are not in a Head Trainer's team yet, ask the Club Manager."}</p></Card>
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
                      {/* Chỉ hiện khi ngựa có việc: trước đây mọi thẻ đều lặp "No tasks for you today". */}
                      {mine.length > 0 && (
                        <div>
                          <p className="care-section-label">My tasks today</p>
                          <TaskList tasks={mine} busyId={busyId} onToggle={toggle} />
                        </div>
                      )}
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
