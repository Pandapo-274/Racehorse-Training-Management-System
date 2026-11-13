// src/features/race/OwnerRacesPage.js
// Flow 5 - the owner's race history: upcoming entries, results and prize
// money for their own horses (the backend filters by owner).
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { approveEntry, formatPrize, formatRaceDate, listRaces, rejectEntry, toEntryChip } from "./raceApi";
import "./race.css";

export default function OwnerRacesPage() {
  const navigate = useNavigate();
  const [entries, setEntries] = useState(null);
  const [horseId, setHorseId] = useState("");
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [successMsg, setSuccessMsg] = useState("");
  const [processingId, setProcessingId] = useState(null);
  const [rejectNotes, setRejectNotes] = useState({});
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
  const pending = mine.filter((e) => e.approvalStatus === "PENDING_OWNER" || e.state === "PENDING_APPROVAL");
  const upcoming = mine.filter((e) => e.state === "UPCOMING" && e.approvalStatus === "APPROVED").sort((a, b) => a.raceDate.localeCompare(b.raceDate));
  const history = mine.filter((e) => e.state !== "UPCOMING" && e.state !== "PENDING_APPROVAL" && e.approvalStatus !== "PENDING_OWNER");
  const ran = mine.filter((e) => e.state === "FINISHED");
  const wins = ran.filter((e) => e.result === "WON").length;
  const places = ran.filter((e) => e.result === "PLACED").length;
  const prize = mine.reduce((s, e) => s + (Number(e.prizeMoney) || 0), 0);

  const handleApprove = async (e) => {
    try {
      setProcessingId(e.registrationId);
      setActionError("");
      await approveEntry(e.registrationId);
      setSuccessMsg(`Approved ${e.horseName} to race in ${e.raceName}. Any entry fee now appears in the horse's costs.`);
      setReloadKey((k) => k + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setActionError(err.message || "Could not approve race entry");
    } finally {
      setProcessingId(null);
    }
  };

  const handleReject = async (e) => {
    const reason = rejectNotes[e.registrationId] || "";
    try {
      setProcessingId(e.registrationId);
      setActionError("");
      await rejectEntry(e.registrationId, reason);
      setSuccessMsg(`Declined race entry for ${e.horseName} in ${e.raceName}.`);
      setReloadKey((k) => k + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setActionError(err.message || "Could not decline race entry");
    } finally {
      setProcessingId(null);
    }
  };

  return (
    <HorseShell
      title="Results & races"
      subtitle="Race entries, pending proposals, and results for your horses"
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
          {successMsg && (
            <div className="hz-alert hz-alert--good" style={{ marginBottom: "16px" }}>
              {successMsg}
            </div>
          )}
          {actionError && (
            <div className="hz-alert hz-alert--serious" style={{ marginBottom: "16px" }}>
              {actionError}
            </div>
          )}

          <div className="race-summary">
            {pending.length > 0 && (
              <div className="hz-stat" style={{ borderColor: "var(--color-warning-border, #f59e0b)" }}>
                <span>Proposals pending</span>
                <strong style={{ color: "var(--color-warning, #d97706)" }}>{pending.length}</strong>
              </div>
            )}
            <div className="hz-stat"><span>Upcoming</span><strong>{upcoming.length}</strong></div>
            <div className="hz-stat"><span>Races run</span><strong>{ran.length}</strong></div>
            <div className="hz-stat"><span>Wins</span><strong>{wins}</strong></div>
            <div className="hz-stat"><span>Placed</span><strong>{places}</strong></div>
            <div className="hz-stat race-stat--prize"><span>Prize money</span><strong>{formatPrize(prize)}</strong><small>all time</small></div>
          </div>

          {pending.length > 0 && (
            <Card title="Race entry proposals"
              hint="Approving charges the race's entry fee to the horse's costs (see Costs & prizes). It is refunded if the horse is withdrawn by the entry deadline or the club cancels the race, but not after the deadline.">
              <div className="hz-table-wrap">
                <table className="hz-table race-table">
                  <thead>
                    <tr>
                      <th style={{ width: "105px" }}>Race day</th>
                      <th style={{ width: "22%" }}>Race</th>
                      <th style={{ width: "16%" }}>Horse</th>
                      <th style={{ width: "20%" }}>Trainer note</th>
                      <th>Owner note</th>
                      <th className="hz-sticky-end" style={{ width: "160px", textAlign: "right" }} aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {pending.map((e) => (
                      <tr key={e.registrationId}>
                        <td className="hz-muted">{formatRaceDate(e.raceDate)}</td>
                        <td className="hz-name">
                          {e.raceName}
                          {(e.distanceM || e.venue) && (
                            <p className="hz-note hz-muted">
                              {[e.distanceM ? `${e.distanceM} m` : null, e.venue].filter(Boolean).join(" · ")}
                            </p>
                          )}
                        </td>
                        <td><strong>{e.horseName}</strong></td>
                        <td className="hz-muted">{e.note || "—"}</td>
                        <td>
                          <input
                            type="text"
                            className="race-owner-note"
                            placeholder="Optional"
                            value={rejectNotes[e.registrationId] || ""}
                            onChange={(ev) => {
                              const val = ev.target.value;
                              setRejectNotes((prev) => ({ ...prev, [e.registrationId]: val }));
                            }}
                          />
                        </td>
                        <td className="hz-row-action hz-sticky-end">
                          <div style={{ display: "inline-flex", gap: "8px", alignItems: "center" }}>
                            <button
                              type="button"
                              className="hz-btn hz-btn--primary hz-btn--sm"
                              onClick={() => handleApprove(e)}
                              disabled={processingId === e.registrationId}
                            >
                              {processingId === e.registrationId ? "Saving…" : "Approve"}
                            </button>
                            <button
                              type="button"
                              className="hz-btn hz-btn--outline hz-btn--danger hz-btn--sm"
                              onClick={() => handleReject(e)}
                              disabled={processingId === e.registrationId}
                            >
                              Decline
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          <Card title="Upcoming races" hint="Confirmed entries approved to race">
            {upcoming.length === 0 ? (
              <p className="hz-empty">No upcoming races.</p>
            ) : (
              <ul className="hz-list">
                {upcoming.map((e) => (
                  <li key={e.registrationId}>
                    <div>
                      <strong>{e.raceName}</strong>
                      <p>{e.horseName} · {formatRaceDate(e.raceDate)}{(e.distanceM || e.venue) ? ` · ${[e.distanceM ? `${e.distanceM} m` : null, e.venue].filter(Boolean).join(" · ")}` : ""}{e.note ? ` · ${e.note}` : ""}</p>
                    </div>
                    <StatusChip tone="good" label="Upcoming" />
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
                    <th>Result / Status</th>
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
                        <td className="hz-muted">
                          {e.rejectReason ? `Declined: ${e.rejectReason}` : e.scratchReason ? `Scratched: ${e.scratchReason}` : e.note || "—"}
                        </td>
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
