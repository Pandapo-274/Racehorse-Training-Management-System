// src/features/race/OwnerRacesPage.js
// Flow 5 - the owner's race history: upcoming entries, results and prize
// money for their own horses (the backend filters by owner).
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { formatPrize, formatRaceDate, listRaces, toEntryChip } from "./raceApi";
import "./race.css";

export default function OwnerRacesPage() {
  const navigate = useNavigate();
  const [entries, setEntries] = useState(null);
  const [horseId, setHorseId] = useState("");
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    listRaces({}, ctrl.signal)
      .then(setEntries)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const horses = useMemo(() => {
    const m = new Map();
    for (const e of entries || []) m.set(e.horseId, e.horseName);
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [entries]);

  const mine = (entries || []).filter((e) => !horseId || String(e.horseId) === horseId);
  const upcoming = mine.filter((e) => e.state === "UPCOMING").sort((a, b) => a.raceDate.localeCompare(b.raceDate));
  const history = mine.filter((e) => e.state !== "UPCOMING");
  const ran = mine.filter((e) => e.state === "FINISHED");
  const wins = ran.filter((e) => e.result === "WON").length;
  const places = ran.filter((e) => e.result === "PLACED").length;
  const prize = mine.reduce((s, e) => s + (Number(e.prizeMoney) || 0), 0);

  return (
    <HorseShell
      title="Results & races"
      subtitle="Race entries and results for your horses"
      actions={horses.length > 1 && (
        <select className="race-year" value={horseId} aria-label="Horse" onChange={(e) => setHorseId(e.target.value)}>
          <option value="">All my horses</option>
          {horses.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
      )}
    >
      {error && (
        <StateBlock kind="error" title="Could not load races" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !entries && <StateBlock title="Loading races…" />}

      {entries && (
        <>
          <div className="race-summary">
            <div className="hz-stat"><span>Races run</span><strong>{ran.length}</strong></div>
            <div className="hz-stat"><span>Wins</span><strong>{wins}</strong></div>
            <div className="hz-stat"><span>Placed</span><strong>{places}</strong></div>
            <div className="hz-stat"><span>Upcoming</span><strong>{upcoming.length}</strong></div>
            <div className="hz-stat race-stat--prize"><span>Prize money</span><strong>{formatPrize(prize)}</strong><small>all time</small></div>
          </div>

          <Card title="Upcoming races" hint="Entered by the Head Trainer">
            {upcoming.length === 0 ? (
              <p className="hz-empty">No upcoming races.</p>
            ) : (
              <ul className="hz-list">
                {upcoming.map((e) => (
                  <li key={e.registrationId}>
                    <div>
                      <strong>{e.raceName}</strong>
                      <p>{e.horseName} · {formatRaceDate(e.raceDate)}{e.note ? ` · ${e.note}` : ""}</p>
                    </div>
                    <StatusChip tone="neutral" label="Upcoming" />
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="Race history">
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
                  </tr>
                </thead>
                <tbody>
                  {history.map((e) => {
                    const chip = toEntryChip(e);
                    return (
                      <tr key={e.registrationId}>
                        <td className="hz-muted">{formatRaceDate(e.raceDate)}</td>
                        <td className="hz-name">{e.raceName}</td>
                        <td>{e.horseName}</td>
                        <td><StatusChip tone={chip.tone} label={chip.label} /></td>
                        <td className="race-prize">{formatPrize(e.prizeMoney)}</td>
                        <td className="hz-muted">{e.note || "—"}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {history.length === 0 && <p className="hz-empty">No races yet.</p>}
            </div>
          </Card>
        </>
      )}
    </HorseShell>
  );
}
