// src/features/race/RacesPage.js
// Flow 5 - race entries, results and performance, for the Head Trainer and
// the Club Manager.
//   Head Trainer: enter horses (only those that pass the checks), scratch an
//                 entry before the race, record results.
//   Club Manager: record or correct results, see performance and race revenue.
// Race revenue = prize money won. There are no invoices or payments.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  RESULTS, RESULT_TEXT, formatPrize, formatRaceDate, getSummary, listCandidates, listRaces, listYears,
  recordResult, registerRace, scratchEntry, toEntryChip, todayIso, tomorrowIso,
} from "./raceApi";
import "./race.css";

function EntryForm({ candidates, onRegistered }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ horseId: "", raceName: "", raceDate: "", note: "" });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const eligible = candidates.filter((c) => !c.problem);
  const blocked = candidates.filter((c) => c.problem);

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    const local = {};
    if (!form.horseId) local.horseId = "Choose a horse";
    if (!form.raceName.trim()) local.raceName = "Race name is required";
    if (!form.raceDate) local.raceDate = "Choose the race day";
    else if (form.raceDate <= todayIso()) local.raceDate = "Choose a day after today";
    setErrors(local);
    if (Object.keys(local).length) return;

    setSaving(true);
    try {
      const entry = await registerRace({
        horseId: Number(form.horseId),
        raceName: form.raceName.trim(),
        raceDate: form.raceDate,
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
      hint="Only club horses aged 2–10, cleared as healthy, with no training lock and no open incident report."
    >
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="re-horse">Horse</label>
            <select id="re-horse" value={form.horseId} onChange={set("horseId")} aria-invalid={!!errors.horseId}>
              <option value="">Choose a horse… ({eligible.length} can race)</option>
              {eligible.map((c) => (
                <option key={c.horseId} value={c.horseId}>
                  {c.horseName}{c.stallNo ? ` · ${c.stallNo}` : ""} · {c.age} yrs
                </option>
              ))}
            </select>
            {errors.horseId && <p className="hz-field__error">{errors.horseId}</p>}
            {blocked.length > 0 && (
              <details className="race-blocked">
                <summary>{blocked.length} horses cannot race now</summary>
                <ul>
                  {blocked.map((c) => <li key={c.horseId}>{c.problem}</li>)}
                </ul>
              </details>
            )}
          </div>
          <div className="hz-field">
            <label htmlFor="re-name">Race</label>
            <input id="re-name" value={form.raceName} maxLength={150} placeholder="e.g. Tracen Cup 2026"
              onChange={set("raceName")} aria-invalid={!!errors.raceName} />
            {errors.raceName && <p className="hz-field__error">{errors.raceName}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="re-date">Race day</label>
            <input id="re-date" type="date" min={tomorrowIso()} value={form.raceDate}
              onChange={set("raceDate")} aria-invalid={!!errors.raceDate} />
            {errors.raceDate && <p className="hz-field__error">{errors.raceDate}</p>}
          </div>
          <div className="hz-field" style={{ gridColumn: "1 / -1" }}>
            <label htmlFor="re-note">Note</label>
            <input id="re-note" value={form.note} maxLength={255} placeholder="Optional, e.g. papers submitted"
              onChange={set("note")} />
          </div>
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Entering…" : "Enter horse"}</button>
        </div>
      </form>
    </Card>
  );
}

/** Inline editor in the row: result + prize, or a scratch reason. */
function RowActions({ entry, isTrainer, onSaved }) {
  const navigate = useNavigate();
  const [mode, setMode] = useState(null); // null | "result" | "scratch"
  const [result, setResult] = useState(entry.result && entry.result !== "SCRATCHED" ? entry.result : "WON");
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

  const raceDone = entry.state === "AWAITING_RESULT" || entry.state === "FINISHED";
  const canScratch = isTrainer && entry.state === "UPCOMING";
  if (!raceDone && !canScratch) return null;

  if (mode === "result") {
    return (
      <div>
        <div className="race-edit">
          <select value={result} aria-label="Result" onChange={(e) => {
            setResult(e.target.value);
            if (e.target.value === "UNPLACED") setPrize("");
          }}>
            {RESULTS.map((r) => <option key={r} value={r}>{RESULT_TEXT[r].label}</option>)}
          </select>
          <input type="number" min="0" step="1000" placeholder="Prize (VND)" aria-label="Prize money"
            value={prize} disabled={result === "UNPLACED"} onChange={(e) => setPrize(e.target.value)} />
          <button type="button" className="hz-btn" disabled={busy}
            onClick={() => run(() => recordResult(entry.registrationId, {
              result, prizeMoney: result === "UNPLACED" || prize === "" ? 0 : Number(prize),
            }))}>
            Save
          </button>
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
          <input type="text" maxLength={200} placeholder="Reason, e.g. sore hoof" aria-label="Reason"
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

export default function RacesPage() {
  const navigate = useNavigate();
  const isTrainer = getUser()?.role === "HEAD_TRAINER";
  const thisYear = new Date().getFullYear();

  const [year, setYear] = useState(thisYear);
  const [years, setYears] = useState([thisYear]);
  const [entries, setEntries] = useState(null);
  const [summary, setSummary] = useState(null);
  const [candidates, setCandidates] = useState([]);
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

  const loadCandidates = useCallback((signal) => {
    if (!isTrainer) return Promise.resolve();
    return listCandidates(signal).then(setCandidates);
  }, [isTrainer]);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    setEntries(null);
    Promise.all([listRaces({ year }, ctrl.signal), getSummary(year, ctrl.signal), loadCandidates(ctrl.signal)])
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
  }, [navigate, year, reloadKey, loadCandidates]);

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
      subtitle={isTrainer ? "Enter horses, record results and follow performance" : "Results, performance and race revenue"}
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
            <div className="hz-stat"><span>Entries</span><strong>{summary.entries}</strong><small>{summary.upcoming} not run yet</small></div>
            <div className="hz-stat"><span>Wins</span><strong>{summary.wins}</strong><small>of {summary.finished} finished</small></div>
            <div className="hz-stat"><span>Placed</span><strong>{summary.places}</strong></div>
            <div className="hz-stat"><span>Scratched</span><strong>{summary.scratched}</strong></div>
            <div className="hz-stat race-stat--prize"><span>Prize money {year}</span><strong>{formatPrize(summary.prizeMoney)}</strong><small>race revenue</small></div>
          </div>

          {isTrainer && (
            <EntryForm
              candidates={candidates}
              onRegistered={(entry) => {
                setInfo(`${entry.horseName} entered in ${entry.raceName} on ${formatRaceDate(entry.raceDate)}. The owner has been notified.`);
                if (Number(entry.raceDate.slice(0, 4)) === year) setEntries((old) => [entry, ...old]);
                refreshSummary();
                loadCandidates().catch(() => {});
              }}
            />
          )}
          {info && <p className="race-ok" role="status">{info}</p>}

          <div className="hz-tabs" role="tablist">
            <button type="button" role="tab" aria-selected={tab === "entries"} className={tab === "entries" ? "active" : ""}
              onClick={() => setTab("entries")}>
              Entries
            </button>
            <button type="button" role="tab" aria-selected={tab === "performance"} className={tab === "performance" ? "active" : ""}
              onClick={() => setTab("performance")}>
              Performance by horse
            </button>
          </div>

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
                          <td className="hz-name">{e.raceName}</td>
                          <td>
                            {e.horseName}
                            <p className="hz-note hz-muted">{e.ownerName}</p>
                          </td>
                          <td><StatusChip tone={chip.tone} label={chip.label} /></td>
                          <td className="race-prize">{formatPrize(e.prizeMoney)}</td>
                          <td className="hz-muted">{e.note || "—"}</td>
                          <td className="hz-row-action">
                            <RowActions key={`${e.registrationId}-${e.result}`} entry={e} isTrainer={isTrainer} onSaved={onSaved} />
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
