// src/features/race/RacesPage.js
// Flow 5 - race entries, results and performance, for the Head Trainer and
// the Club Manager.
//   Head Trainer: create races, enter horses (only those that pass the checks),
//                 scratch an entry before the race, record results.
//   Club Manager: create and cancel races, record or correct results (also after
//                 the edit window, with a reason), see performance and owners' prize money.
// Prize money is paid to the owners. It is not club revenue.
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  RESULTS, RESULT_TEXT, cancelRaceEvent, createRaceEvent, formatPrize, formatRaceDate, formatRaceTime,
  getSummary, listCandidates, listRaceEvents, listRaces, listYears, ordinal, parseRaceTime, recordResult,
  registerRace, scratchEntry, toEntryChip, todayIso, tomorrowIso,
} from "./raceApi";
import "./race.css";

/** Create a race (Head Trainer, Club Manager). Name and date cannot be changed afterwards. */
function RaceForm({ onCreated, onCancel }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ raceName: "", raceDate: "", distanceM: "", venue: "", entryDeadline: "", entryFee: "", maxEntries: "" });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const num = (v) => (v === "" ? null : Number(v));

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    const local = {};
    if (!form.raceName.trim()) local.raceName = "Race name is required";
    if (!form.raceDate) local.raceDate = "Choose the race day";
    else if (form.raceDate <= todayIso()) local.raceDate = "Choose a day after today";
    if (form.entryDeadline && form.raceDate && form.entryDeadline > form.raceDate) local.entryDeadline = "On or before the race day";
    setErrors(local);
    if (Object.keys(local).length) return;
    setSaving(true);
    try {
      const race = await createRaceEvent({
        raceName: form.raceName.trim(),
        raceDate: form.raceDate,
        distanceM: num(form.distanceM),
        venue: form.venue.trim() || null,
        entryDeadline: form.entryDeadline || null,
        entryFee: num(form.entryFee),
        maxEntries: num(form.maxEntries),
      });
      onCreated(race);
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
    <Card title="Create a race" hint="Name and date cannot be changed later; cancel the race and create a new one instead.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          {field("rc-name", "Race name", <input id="rc-name" value={form.raceName} maxLength={150} placeholder="e.g. Tracen Cup 2026" onChange={set("raceName")} aria-invalid={!!errors.raceName} />, "raceName")}
          {field("rc-date", "Race day", <input id="rc-date" type="date" min={tomorrowIso()} value={form.raceDate} onChange={set("raceDate")} aria-invalid={!!errors.raceDate} />, "raceDate")}
          {field("rc-dist", "Distance (m)", <input id="rc-dist" type="number" min="100" max="10000" step="100" value={form.distanceM} onChange={set("distanceM")} />, "distanceM")}
          {field("rc-venue", "Venue", <input id="rc-venue" value={form.venue} maxLength={150} onChange={set("venue")} />, "venue")}
          {field("rc-deadline", "Entries close", <input id="rc-deadline" type="date" min={todayIso()} max={form.raceDate || undefined} value={form.entryDeadline} onChange={set("entryDeadline")} aria-invalid={!!errors.entryDeadline} />, "entryDeadline")}
          {field("rc-fee", "Entry fee (VND)", <input id="rc-fee" type="number" min="0" step="1000" value={form.entryFee} onChange={set("entryFee")} />, "entryFee")}
          {field("rc-max", "Max horses", <input id="rc-max" type="number" min="1" max="30" value={form.maxEntries} onChange={set("maxEntries")} />, "maxEntries")}
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Creating…" : "Create race"}</button>
          <button type="button" className="hz-btn hz-btn--ghost" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </Card>
  );
}

function raceLabel(r) {
  return [
    r.raceName,
    formatRaceDate(r.raceDate),
    r.distanceM ? `${r.distanceM} m` : null,
    r.maxEntries ? `${r.entries}/${r.maxEntries} horses` : `${r.entries} horse${r.entries === 1 ? "" : "s"}`,
  ].filter(Boolean).join(" · ");
}

function EntryForm({ races, onRegistered }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ horseId: "", raceId: "", note: "" });
  const [candidates, setCandidates] = useState([]);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const open = races.filter((r) => r.status === "OPEN" && r.raceDate > todayIso()
    && (!r.entryDeadline || r.entryDeadline >= todayIso()));
  const race = open.find((r) => String(r.raceId) === form.raceId);

  // Candidates depend on the race (rest days between races), so reload when it changes.
  useEffect(() => {
    const ctrl = new AbortController();
    listCandidates(form.raceId || null, ctrl.signal)
      .then(setCandidates)
      .catch((err) => {
        if (err.name !== "AbortError" && !handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [form.raceId, navigate]);

  const eligible = candidates.filter((c) => !c.problem);
  const blocked = candidates.filter((c) => c.problem);
  const chosen = candidates.find((c) => String(c.horseId) === form.horseId);
  const full = race && race.maxEntries && race.entries >= race.maxEntries;

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    const local = {};
    if (!form.raceId) local.raceId = "Choose a race";
    if (!form.horseId) local.horseId = "Choose a horse";
    setErrors(local);
    if (Object.keys(local).length) return;

    setSaving(true);
    try {
      const entry = await registerRace({
        horseId: Number(form.horseId),
        raceId: Number(form.raceId),
        note: form.note.trim() || null,
      });
      setForm((f) => ({ ...f, horseId: "", note: "" }));
      onRegistered(entry);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.errors || {});
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card
      title="Enter a horse"
      hint="Club horses aged 2–10, cleared as healthy, no training lock, no open incident report, vaccination not overdue, and races at least two weeks apart."
    >
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="re-race">Race</label>
            <select id="re-race" value={form.raceId} onChange={(e) => setForm((f) => ({ ...f, raceId: e.target.value, horseId: "" }))} aria-invalid={!!errors.raceId}>
              <option value="">{open.length ? "Choose a race…" : "No race open for entries — create one first"}</option>
              {open.map((r) => <option key={r.raceId} value={r.raceId}>{raceLabel(r)}</option>)}
            </select>
            {errors.raceId && <p className="hz-field__error">{errors.raceId}</p>}
            {race && (
              <p className="hz-note hz-muted">
                {race.venue || "Venue not set"}
                {race.entryDeadline ? ` · entries close ${formatRaceDate(race.entryDeadline)}` : ""}
                {race.entryFee ? ` · fee ${formatPrize(race.entryFee)}` : ""}
              </p>
            )}
            {full && <p className="hz-field__error">This race is full.</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="re-horse">Horse</label>
            <select id="re-horse" value={form.horseId} onChange={set("horseId")} aria-invalid={!!errors.horseId}>
              <option value="">Choose a horse… ({eligible.length} can race)</option>
              {eligible.map((c) => (
                <option key={c.horseId} value={c.horseId}>
                  {c.horseName}{c.stallNo ? ` · ${c.stallNo}` : ""} · {c.age} yrs{c.warnings?.length ? " ⚠" : ""}
                </option>
              ))}
            </select>
            {errors.horseId && <p className="hz-field__error">{errors.horseId}</p>}
            {chosen?.warnings?.length > 0 && (
              <ul className="race-warn">{chosen.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
            )}
            {blocked.length > 0 && (
              <details className="race-blocked">
                <summary>{blocked.length} horses cannot race {race ? "in this race" : "now"}</summary>
                <ul>
                  {blocked.map((c) => (
                    <li key={c.horseId}>{(c.problems && c.problems.length ? c.problems : [c.problem]).join("; ")}</li>
                  ))}
                </ul>
              </details>
            )}
          </div>
          <div className="hz-field" style={{ gridColumn: "1 / -1" }}>
            <label htmlFor="re-note">Note</label>
            <input id="re-note" value={form.note} maxLength={255} placeholder="Optional, e.g. papers submitted"
              onChange={set("note")} />
          </div>
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving || full}>{saving ? "Entering…" : "Enter horse"}</button>
        </div>
      </form>
    </Card>
  );
}

/** Inline editor in the row: result + position + time + prize, or a scratch reason. */
function RowActions({ entry, isTrainer, isManager, onSaved }) {
  const navigate = useNavigate();
  const [mode, setMode] = useState(null); // null | "result" | "scratch"
  const [result, setResult] = useState(entry.result && entry.result !== "SCRATCHED" ? entry.result : "WON");
  const [position, setPosition] = useState(entry.finishPosition ? String(entry.finishPosition) : "");
  const [time, setTime] = useState(formatRaceTime(entry.finishTimeMs));
  const [prize, setPrize] = useState(entry.prizeMoney ? String(entry.prizeMoney) : "");
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const run = async (fn) => {
    setBusy(true);
    setError("");
    try {
      onSaved(await fn());
      setMode(null);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  // Picking a position sets the matching result: 1st = Won, 2nd-3rd = Placed, 4th+ = Unplaced.
  const pickPosition = (v) => {
    setPosition(v);
    const n = Number(v);
    if (n >= 1) {
      const r = n === 1 ? "WON" : n <= 3 ? "PLACED" : "UNPLACED";
      setResult(r);
      if (r === "UNPLACED") setPrize("");
    }
  };

  const raceDone = entry.state === "AWAITING_RESULT" || entry.state === "FINISHED";
  const canScratch = isTrainer && entry.state === "UPCOMING";
  if (!raceDone && !canScratch) return null;

  if (mode === "result") {
    const save = () => {
      const ms = parseRaceTime(time);
      if (Number.isNaN(ms)) { setError("Time as 1:58.4 or 118.4"); return; }
      run(() => recordResult(entry.registrationId, {
        result,
        prizeMoney: result === "UNPLACED" || prize === "" ? 0 : Number(prize),
        finishPosition: position === "" ? null : Number(position),
        finishTimeMs: ms,
        reason: reason.trim() || null,
      }));
    };
    return (
      <div>
        <div className="race-edit">
          <input type="number" min="1" max="30" placeholder="Pos." aria-label="Finishing position" className="race-pos"
            value={position} onChange={(e) => pickPosition(e.target.value)} />
          <select value={result} aria-label="Result" onChange={(e) => {
            setResult(e.target.value);
            if (e.target.value === "UNPLACED") setPrize("");
          }}>
            {RESULTS.map((r) => <option key={r} value={r}>{RESULT_TEXT[r].label}</option>)}
          </select>
          <input type="text" placeholder="Time 1:58.4" aria-label="Finishing time" className="race-time"
            value={time} onChange={(e) => setTime(e.target.value)} />
          <input type="number" min="0" step="1000" placeholder="Prize (VND)" aria-label="Prize money"
            value={prize} disabled={result === "UNPLACED"} onChange={(e) => setPrize(e.target.value)} />
          {isManager && entry.result && (
            <input type="text" maxLength={255} placeholder="Reason (needed after the lock)" aria-label="Reason for change"
              value={reason} onChange={(e) => setReason(e.target.value)} />
          )}
          <button type="button" className="hz-btn" disabled={busy} onClick={save}>Save</button>
          <button type="button" className="race-link" onClick={() => setMode(null)}>Cancel</button>
        </div>
        {error && <p className="race-error">{error}</p>}
      </div>
    );
  }

  if (mode === "scratch") {
    return (
      <div>
        <div className="race-edit">
          <input type="text" maxLength={500} placeholder="Reason, e.g. sore hoof" aria-label="Reason"
            value={reason} onChange={(e) => setReason(e.target.value)} />
          <button type="button" className="hz-btn" disabled={busy || !reason.trim()}
            onClick={() => run(() => scratchEntry(entry.registrationId, reason.trim()))}>
            Scratch
          </button>
          <button type="button" className="race-link" onClick={() => setMode(null)}>Cancel</button>
        </div>
        {error && <p className="race-error">{error}</p>}
      </div>
    );
  }

  return (
    <div className="race-actions">
      {raceDone && (
        <button type="button" className="race-link" onClick={() => setMode("result")}>
          {entry.result ? "Edit result" : "Record result"}
        </button>
      )}
      {canScratch && (
        <button type="button" className="race-link race-link--danger" onClick={() => setMode("scratch")}>
          Scratch
        </button>
      )}
    </div>
  );
}

/** The race calendar (V22). */
function RaceCalendar({ races, isManager, canCreate, onCreated, onCancelled }) {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const today = todayIso();

  const cancel = async (r) => {
    const reason = window.prompt(`Cancel ${r.raceName} on ${formatRaceDate(r.raceDate)}? Every horse entered will be scratched and its owner told.\n\nReason:`);
    if (reason === null) return;
    if (!reason.trim()) { setError("A reason is required to cancel a race."); return; }
    setError("");
    try {
      onCancelled(await cancelRaceEvent(r.raceId, reason.trim()));
    } catch (err) {
      if (!handleAuthError(err, navigate)) setError(err.message);
    }
  };

  return (
    <>
      {creating && <RaceForm onCancel={() => setCreating(false)} onCreated={(r) => { setCreating(false); onCreated(r); }} />}
      <Card
        title="Race calendar"
        hint="Each race has its own distance, venue, entry deadline, fee and field size."
        actions={canCreate && !creating && (
          <button type="button" className="hz-btn" onClick={() => setCreating(true)}>Create race</button>
        )}
      >
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-table-wrap">
          <table className="hz-table">
            <thead>
              <tr><th>Race day</th><th>Race</th><th>Distance</th><th>Venue</th><th>Entries close</th><th>Horses</th><th>Fee</th><th>Status</th>{isManager && <th aria-label="Actions" />}</tr>
            </thead>
            <tbody>
              {races.map((r) => (
                <tr key={r.raceId}>
                  <td className="hz-muted">{formatRaceDate(r.raceDate)}</td>
                  <td className="hz-name">{r.raceName}</td>
                  <td>{r.distanceM ? `${r.distanceM} m` : "—"}</td>
                  <td>{r.venue || "—"}</td>
                  <td className="hz-muted">{r.entryDeadline ? formatRaceDate(r.entryDeadline) : "—"}</td>
                  <td>{r.entries}{r.maxEntries ? ` / ${r.maxEntries}` : ""}</td>
                  <td className="race-prize">{formatPrize(r.entryFee)}</td>
                  <td>
                    {r.status === "CANCELLED"
                      ? <StatusChip tone="serious" label="Cancelled" />
                      : r.raceDate > today ? <StatusChip tone="neutral" label="Open" /> : <StatusChip tone="good" label="Run" />}
                    {r.cancelReason && <p className="hz-note hz-muted">{r.cancelReason}</p>}
                  </td>
                  {isManager && (
                    <td className="hz-row-action">
                      {r.status === "OPEN" && r.raceDate > today && (
                        <button type="button" className="race-link race-link--danger" onClick={() => cancel(r)}>Cancel race</button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          {races.length === 0 && <p className="hz-empty">No races yet.</p>}
        </div>
      </Card>
    </>
  );
}

export default function RacesPage() {
  const navigate = useNavigate();
  const role = getUser()?.role;
  const isTrainer = role === "HEAD_TRAINER";
  const isManager = role === "CLUB_MANAGER";
  const thisYear = new Date().getFullYear();

  const [year, setYear] = useState(thisYear);
  const [years, setYears] = useState([thisYear]);
  const [entries, setEntries] = useState(null);
  const [summary, setSummary] = useState(null);
  const [raceEvents, setRaceEvents] = useState([]);
  const [tab, setTab] = useState("entries");
  const [filter, setFilter] = useState("ALL");
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    listYears(ctrl.signal)
      .then((ys) => setYears([...new Set([thisYear, ...ys])].sort((a, b) => b - a)))
      .catch(() => {}); // only feeds the year picker
    return () => ctrl.abort();
  }, [thisYear]);

  const loadRaceEvents = (signal) => listRaceEvents({}, signal).then(setRaceEvents);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    setEntries(null);
    Promise.all([listRaces({ year }, ctrl.signal), getSummary(year, ctrl.signal), loadRaceEvents(ctrl.signal)])
      .then(([e, s]) => {
        setEntries(e);
        setSummary(s);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, year, reloadKey]);

  const refreshSummary = () => getSummary(year).then(setSummary).catch(() => {});

  const onSaved = (saved) => {
    setEntries((old) => old.map((e) => (e.registrationId === saved.registrationId ? saved : e)));
    setInfo(`${saved.horseName} · ${saved.raceName}: ${toEntryChip(saved).label.toLowerCase()}.`);
    refreshSummary();
  };

  const counts = useMemo(() => {
    const c = { ALL: 0, UPCOMING: 0, AWAITING_RESULT: 0, FINISHED: 0, SCRATCHED: 0 };
    for (const e of entries || []) {
      c.ALL++;
      c[e.state] = (c[e.state] || 0) + 1;
    }
    return c;
  }, [entries]);
  const shown = (entries || []).filter((e) => filter === "ALL" || e.state === filter);

  return (
    <HorseShell
      title="Races"
      subtitle={isTrainer ? "Enter horses, record results and follow performance" : "Results, performance and owners\u2019 prize money"}
      actions={
        <select className="race-year" value={year} aria-label="Year" onChange={(e) => setYear(Number(e.target.value))}>
          {years.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      }
    >
      {error && (
        <StateBlock kind="error" title="Could not load races" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !entries && <StateBlock title="Loading races…" />}

      {entries && summary && (
        <>
          <div className="race-summary">
            <div className="hz-stat"><span>Entries</span><strong>{summary.entries}</strong><small>
                {summary.upcoming} upcoming
                {summary.awaitingResult > 0 && ` · ${summary.awaitingResult} awaiting result`}
              </small></div>
            <div className="hz-stat"><span>Wins</span><strong>{summary.wins}</strong><small>of {summary.finished} finished</small></div>
            <div className="hz-stat"><span>Placed</span><strong>{summary.places}</strong></div>
            <div className="hz-stat"><span>Scratched</span><strong>{summary.scratched}</strong></div>
            <div className="hz-stat race-stat--prize"><span>Prize money {year}</span><strong>{formatPrize(summary.prizeMoney)}</strong><small>paid to owners</small></div>
          </div>

          {isTrainer && (
            <EntryForm
              races={raceEvents}
              onRegistered={(entry) => {
                setInfo(`${entry.horseName} entered in ${entry.raceName} on ${formatRaceDate(entry.raceDate)}. The owner has been notified.`);
                if (Number(entry.raceDate.slice(0, 4)) === year) setEntries((old) => [entry, ...old]);
                refreshSummary();
                loadRaceEvents().catch(() => {});
              }}
            />
          )}
          {info && <p className="race-ok" role="status">{info}</p>}

          <div className="hz-tabs" role="tablist">
            <button type="button" role="tab" aria-selected={tab === "entries"} className={tab === "entries" ? "active" : ""}
              onClick={() => setTab("entries")}>
              Entries
            </button>
            <button type="button" role="tab" aria-selected={tab === "calendar"} className={tab === "calendar" ? "active" : ""}
              onClick={() => setTab("calendar")}>
              Race calendar
            </button>
            <button type="button" role="tab" aria-selected={tab === "performance"} className={tab === "performance" ? "active" : ""}
              onClick={() => setTab("performance")}>
              Performance by horse
            </button>
          </div>

          {tab === "calendar" && (
            <RaceCalendar
              races={raceEvents}
              isManager={isManager}
              canCreate={isTrainer || isManager}
              onCreated={(r) => {
                setInfo(`${r.raceName} on ${formatRaceDate(r.raceDate)} created.`);
                loadRaceEvents().catch(() => {});
              }}
              onCancelled={(r) => {
                setInfo(`${r.raceName} cancelled. Its entries were scratched and the owners told.`);
                loadRaceEvents().catch(() => {});
                setReloadKey((k) => k + 1);
              }}
            />
          )}

          {tab === "entries" && (
            <Card
              title={`Entries ${year}`}
              actions={
                <div className="hz-segment" role="group" aria-label="Filter entries">
                  {[["ALL", "All"], ["UPCOMING", "Upcoming"], ["AWAITING_RESULT", "Result needed"], ["FINISHED", "Finished"], ["SCRATCHED", "Scratched"]].map(([k, l]) => (
                    <button key={k} type="button" aria-pressed={filter === k} onClick={() => setFilter(k)}>
                      {l} <b>{counts[k] || 0}</b>
                    </button>
                  ))}
                </div>
              }
            >
              <div className="hz-table-wrap">
                <table className="hz-table">
                  <thead>
                    <tr>
                      <th>Race day</th>
                      <th>Race</th>
                      <th>Horse</th>
                      <th>Result</th>
                      <th>Prize</th>
                      <th>Note</th>
                      <th aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((e) => {
                      const chip = toEntryChip(e);
                      return (
                        <tr key={e.registrationId}>
                          <td className="hz-muted">{formatRaceDate(e.raceDate)}</td>
                          <td className="hz-name">
                            {e.raceName}
                            {(e.distanceM || e.venue) && <p className="hz-note hz-muted">{[e.distanceM ? `${e.distanceM} m` : null, e.venue].filter(Boolean).join(" · ")}</p>}
                          </td>
                          <td>
                            {e.horseName}
                            <p className="hz-note hz-muted">{e.ownerName}</p>
                          </td>
                          <td>
                            <StatusChip tone={chip.tone} label={chip.label} />
                            {(e.finishPosition || e.finishTimeMs) && (
                              <p className="hz-note hz-muted">
                                {[e.finishPosition ? ordinal(e.finishPosition) : null, formatRaceTime(e.finishTimeMs) || null].filter(Boolean).join(" · ")}
                              </p>
                            )}
                          </td>
                          <td className="race-prize">{formatPrize(e.prizeMoney)}</td>
                          <td className="hz-muted">
                            {e.scratchReason && <p className="hz-note">Scratched: {e.scratchReason}</p>}
                            {e.note || (e.scratchReason ? null : "—")}
                          </td>
                          <td className="hz-row-action">
                            <RowActions key={`${e.registrationId}-${e.result}`} entry={e} isTrainer={isTrainer} isManager={isManager} onSaved={onSaved} />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
                {shown.length === 0 && <p className="hz-empty">No entries.</p>}
              </div>
            </Card>
          )}

          {tab === "performance" && (
            <Card title={`Performance ${year}`} hint="Scratched entries are not counted. Sorted by prize money.">
              <div className="hz-table-wrap">
                <table className="hz-table">
                  <thead>
                    <tr>
                      <th>Horse</th>
                      <th>Owner</th>
                      <th>Entries</th>
                      <th>Wins</th>
                      <th>Placed</th>
                      <th>Win rate</th>
                      <th>Prize money</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.horses.map((h) => (
                      <tr key={h.horseId}>
                        <td className="hz-name">{h.horseName}</td>
                        <td className="hz-muted">{h.ownerName}</td>
                        <td>{h.entries}</td>
                        <td>{h.wins}</td>
                        <td>{h.places}</td>
                        <td>{h.entries ? `${Math.round((h.wins / h.entries) * 100)}%` : "—"}</td>
                        <td className="race-prize">{formatPrize(h.prizeMoney)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {summary.horses.length === 0 && <p className="hz-empty">No races this year.</p>}
              </div>
            </Card>
          )}
        </>
      )}
    </HorseShell>
  );
}
