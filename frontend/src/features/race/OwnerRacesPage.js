// src/features/race/OwnerRacesPage.js
// Flow 5 - the owner's race history: upcoming entries, results and prize
// money for their own horses (the backend filters by owner).
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { useAppDialog } from "../common/AppDialog";
import {
  approveEntry, errorText, formatPrize, formatRaceDate, listCandidates, listRaceEvents, listRaces,
  ownerRegisterRace, rejectEntry, scratchEntry, submissionText, toEntryChip, todayIso,
} from "./raceApi";
import "./race.css";

/*
 * Trang /horse-owner/races. Danh sách suất đua của ngựa mình (GET /api/races, backend lọc theo chủ), lọc theo ngựa.
 * Góp ý hội đồng: chủ ngựa là người đăng ký giải (RegisterCard), Head Trainer chỉ tư vấn.
 * Lời khuyên của HLV (PENDING_OWNER) có hai nút: Register (PUT /api/races/{id}/approve - backend kiểm lại điều kiện
 * và ghi lệ phí giải vào chi phí; quá hạn đăng ký thì không đăng ký được) và Not now (lý do tuỳ chọn, rejectNotes giữ
 * lý do theo từng suất).
 */

// Chọn giải rồi chọn ngựa của mình. Backend trả mọi ngựa của chủ kèm lý do nếu chưa đủ điều kiện.
function RegisterCard({ onRegistered, navigate }) {
  const [races, setRaces] = useState([]);
  const [candidates, setCandidates] = useState([]);
  const [form, setForm] = useState({ raceId: "", horseId: "", note: "" });
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    listRaceEvents({ upcoming: true }, ctrl.signal)
      .then((rows) => setRaces((rows || []).filter((r) => r.status === "OPEN" && r.raceDate > todayIso()
        && (!r.entryDeadline || r.entryDeadline >= todayIso()))))
      .catch((err) => {
        if (err.name !== "AbortError" && !handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  useEffect(() => {
    if (!form.raceId) { setCandidates([]); return undefined; }
    const ctrl = new AbortController();
    listCandidates(form.raceId, ctrl.signal)
      .then(setCandidates)
      .catch((err) => {
        if (err.name !== "AbortError" && !handleAuthError(err, navigate)) setError(err.message);
      });
    return () => ctrl.abort();
  }, [form.raceId, navigate, reload]);

  const race = races.find((r) => String(r.raceId) === form.raceId);
  const full = race && race.maxEntries && race.entries >= race.maxEntries;
  const eligible = candidates.filter((c) => !c.problem);
  const blocked = candidates.filter((c) => c.problem);

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    setSaving(true);
    try {
      const entry = await ownerRegisterRace({
        horseId: Number(form.horseId),
        raceId: Number(form.raceId),
        note: form.note.trim() || null,
      });
      setForm({ raceId: "", horseId: "", note: "" });
      setReload((k) => k + 1);
      onRegistered(entry);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="Register a horse for a race"
      hint="You make the entry. The entry fee is added to the horse's costs, and it is refunded if you withdraw by the entry deadline or the club cancels the race.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="own-race">Race</label>
            <select id="own-race" value={form.raceId}
                    onChange={(e) => setForm((f) => ({ ...f, raceId: e.target.value, horseId: "" }))}>
              <option value="">{races.length ? "Choose a race…" : "No race is open for entries"}</option>
              {races.map((r) => (
                <option key={r.raceId} value={r.raceId}>
                  {[r.raceName, r.organizer, formatRaceDate(r.raceDate), r.distanceM ? `${r.distanceM} m` : null].filter(Boolean).join(" · ")}
                </option>
              ))}
            </select>
            {race && (
              <p className="hz-field__hint">
                {[race.venue,
                  race.entryDeadline ? `entries close ${formatRaceDate(race.entryDeadline)}` : null,
                  race.entryFee ? `fee ${formatPrize(race.entryFee)}` : null,
                  race.maxEntries ? `${race.entries}/${race.maxEntries} horses` : null].filter(Boolean).join(" · ")}
              </p>
            )}
          </div>
          <div className="hz-field">
            <label htmlFor="own-horse">Horse</label>
            <select id="own-horse" value={form.horseId} disabled={!form.raceId || full}
                    onChange={(e) => setForm((f) => ({ ...f, horseId: e.target.value }))}>
              <option value="">
                {!form.raceId ? "Choose a race first" : full ? "Race is full" : `Choose a horse… (${eligible.length} can race)`}
              </option>
              {eligible.map((c) => <option key={c.horseId} value={c.horseId}>{c.horseName} · {c.ageMonths} months</option>)}
            </select>
            {form.raceId && blocked.length > 0 && (
              <details className="race-blocked">
                <summary>{blocked.length} of your horses cannot race in this race</summary>
                <ul>
                  {blocked.map((c) => (
                    <li key={c.horseId}>
                      <strong>{c.horseName}</strong>: {(c.problems?.length ? c.problems : [c.problem]).join("; ")}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        </div>
        <div className="hz-field" style={{ marginTop: "12px" }}>
          <label htmlFor="own-note">Note</label>
          <input id="own-note" value={form.note} maxLength={255} placeholder="Optional"
                 onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} />
        </div>
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer" style={{ marginTop: "14px" }}>
          <button type="submit" className="hz-btn" disabled={saving || full || !form.raceId || !form.horseId}>
            {saving ? "Registering…" : "Register horse"}
          </button>
        </div>
      </form>
    </Card>
  );
}

export default function OwnerRacesPage() {
  const navigate = useNavigate();
  const dialog = useAppDialog();
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

  // Danh sách ngựa (không trùng) rút ra từ các suất đua, cho ô lọc theo ngựa.
  const horses = useMemo(() => {
    const m = new Map();
    for (const e of entries || []) m.set(e.horseId, e.horseName);
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [entries]);

  const mine = (entries || []).filter((e) => !horseId || String(e.horseId) === horseId);
  // By state, not approvalStatus: a withdrawn proposal (or one whose race was cancelled)
  // keeps approvalStatus PENDING_OWNER but is SCRATCHED, and an expired one is EXPIRED.
  // Both used to stay in "proposals" with Approve / Decline buttons that could only fail.
  const pending = mine.filter((e) => e.state === "PENDING_APPROVAL");
  const upcoming = mine.filter((e) => e.state === "UPCOMING").sort((a, b) => a.raceDate.localeCompare(b.raceDate));
  const history = mine.filter((e) => e.state !== "UPCOMING" && e.state !== "PENDING_APPROVAL");
  const ran = mine.filter((e) => e.state === "FINISHED");
  const wins = ran.filter((e) => e.result === "WON").length;
  const places = ran.filter((e) => e.result === "PLACED").length;
  const prize = mine.reduce((s, e) => s + (Number(e.prizeMoney) || 0), 0);

  const handleApprove = async (e) => {
    try {
      setProcessingId(e.registrationId);
      setActionError("");
      await approveEntry(e.registrationId);
      setSuccessMsg(`Registered ${e.horseName} for ${e.raceName}. Any entry fee now appears in the horse's costs.`);
      setReloadKey((k) => k + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setSuccessMsg("");
      setActionError(errorText(err));
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
      setSuccessMsg("");
      setActionError(errorText(err));
    } finally {
      setProcessingId(null);
    }
  };

  // Chủ ngựa tự rút suất của mình (bắt buộc ghi lý do). Backend quyết định có hoàn lệ phí không.
  const handleWithdraw = async (e) => {
    const reason = await dialog.prompt({
      title: `Withdraw ${e.horseName}?`,
      message: `From ${e.raceName} on ${formatRaceDate(e.raceDate)}. The entry fee is refunded only up to the entry deadline.`,
      label: "Reason",
      // V55: start from the trainer's advice when there is one; the owner can rewrite it.
      defaultValue: e.withdrawAdvice || "",
      confirmLabel: "Withdraw",
      danger: true,
      validate: (v) => (v ? null : "Say why the horse is withdrawn"),
      onSubmit: (v) => scratchEntry(e.registrationId, v),
    });
    if (reason === null) return;
    setActionError("");
    setSuccessMsg(`Withdrew ${e.horseName} from ${e.raceName}.`);
    setReloadKey((k) => k + 1);
  };

  return (
    <HorseShell
      title="Results & races"
      subtitle="Register your horses, read your trainer's advice, and follow results"
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

      {dialog.element}
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
                <span>Trainer advice</span>
                <strong style={{ color: "var(--color-warning, #d97706)" }}>{pending.length}</strong>
              </div>
            )}
            <div className="hz-stat"><span>Upcoming</span><strong>{upcoming.length}</strong></div>
            <div className="hz-stat"><span>Races run</span><strong>{ran.length}</strong></div>
            <div className="hz-stat"><span>Wins</span><strong>{wins}</strong></div>
            <div className="hz-stat"><span>Placed</span><strong>{places}</strong></div>
            <div className="hz-stat race-stat--prize"><span>Prize money</span><strong>{formatPrize(prize)}</strong><small>all time</small></div>
          </div>

          <RegisterCard
            navigate={navigate}
            onRegistered={(entry) => {
              setActionError("");
              setSuccessMsg(`Registered ${entry.horseName} for ${entry.raceName}. Any entry fee now appears in the horse's costs.`);
              setReloadKey((k) => k + 1);
            }}
          />

          {pending.length > 0 && (
            <Card title="Advice from your Head Trainer"
              hint="Your trainer recommends these races. Nothing is entered until you press Register, which adds the entry fee to the horse's costs (see Costs & prizes). It is refunded if the horse is withdrawn by the entry deadline or the club cancels the race, but not after the deadline.">
              <div className="hz-table-wrap">
                <table className="hz-table race-table">
                  <thead>
                    <tr>
                      <th style={{ width: "105px" }}>Race day</th>
                      <th style={{ width: "22%" }}>Race</th>
                      <th style={{ width: "16%" }}>Horse</th>
                      <th style={{ width: "20%" }}>Trainer's advice</th>
                      <th>Reason if not now</th>
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
                            maxLength={255}
                            aria-label={`Reason for declining ${e.horseName}`}
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
                              {processingId === e.registrationId ? "Saving…" : "Register"}
                            </button>
                            <button
                              type="button"
                              className="hz-btn hz-btn--outline hz-btn--danger hz-btn--sm"
                              onClick={() => handleReject(e)}
                              disabled={processingId === e.registrationId}
                            >
                              Not now
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

          <Card title="Upcoming races" hint="Entries you have registered">
            {upcoming.length === 0 ? (
              <p className="hz-empty">No upcoming races.</p>
            ) : (
              <ul className="hz-list">
                {upcoming.map((e) => (
                  <li key={e.registrationId}>
                    <div>
                      <strong>{e.raceName}</strong>
                      <p>{e.horseName} · {formatRaceDate(e.raceDate)}{(e.distanceM || e.venue) ? ` · ${[e.distanceM ? `${e.distanceM} m` : null, e.venue].filter(Boolean).join(" · ")}` : ""}{e.note ? ` · ${e.note}` : ""}</p>
                      {/* V59: the club sends the entry to the organizer for the owner. */}
                      <p className="hz-muted">{e.submittedAt ? submissionText(e) : `The club will submit this entry to ${e.organizer || "the organizer"}`}</p>
                      {/* V55: the Head Trainer advises; the owner decides whether to withdraw. */}
                      {e.withdrawAdvice && (
                        <p className="hz-field__error">
                          {e.withdrawAdviceByName || "Your Head Trainer"} recommends withdrawing: {e.withdrawAdvice}
                        </p>
                      )}
                    </div>
                    <div style={{ display: "inline-flex", gap: "8px", alignItems: "center" }}>
                      <StatusChip tone={e.withdrawAdvice ? "warning" : "good"}
                                  label={e.withdrawAdvice ? "Withdrawal advised" : "Upcoming"} />
                      <button type="button" className="hz-btn hz-btn--outline hz-btn--danger hz-btn--sm"
                              onClick={() => handleWithdraw(e)}>
                        Withdraw
                      </button>
                    </div>
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
                          {e.rejectReason ? `Declined: ${e.rejectReason}`
                            : e.scratchReason ? `Scratched: ${e.scratchReason}`
                              : e.state === "EXPIRED" ? "Not registered before entries closed"
                                : e.note || "—"}
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
