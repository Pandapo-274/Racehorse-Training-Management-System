// src/features/horse/HorseDetailPage.js
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams, Link } from "react-router-dom";
import HorseMedicalHistoryTab from "./HorseMedicalHistoryTab";

import { getUser, handleAuthError } from "../auth/authService";

import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import PedigreeTree from "./PedigreeTree";
import HorsePhoto from "./HorsePhoto";
import VitalsPanel from "./VitalsPanel";
import HorseHealthPanel from "./HorseHealthPanel";
import HorseWithdrawalPanel from "./HorseWithdrawalPanel";

import {
  getHorse, getPedigree, getVitals, canEditHorse, getHorseBasePath, toStage, isPedigreeOnly, healthApplies,
  canViewPedigree, canViewVitals, isWithdrawn, toActivity, updateHorseIdentity, listActiveTrainers,
  toStatus, toGender, toAge, formatDate, formatDateTime, formatWeight,
  readmitHorse, transferHorseOwner,
  listParentCandidates, correctHorseParents, parentLabel, SIRE_GENDERS, DAM_GENDERS,
} from "./horseApi";
import { listStaff } from "../manager/staffApi";
import { useAppDialog } from "../common/AppDialog";
import ResultLine, { ok, fail } from "../common/ResultLine";

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "medical-history", label: "Medical History" },
  { id: "pedigree", label: "Pedigree" },
  { id: "vitals", label: "Vitals" },
];

const GENERATION_OPTIONS = [2, 3, 4];

function Row({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

/** Club Manager sửa mã đăng ký, số chuồng và điều phối Head Trainer phụ trách (V37). */
function IdentityForm({ horse, onSaved }) {
  const navigate = useNavigate();
  const [code, setCode] = useState(horse.registrationCode || "");
  const [stall, setStall] = useState(horse.stallNo || "");
  const [trainerId, setTrainerId] = useState(horse.trainerId ? String(horse.trainerId) : "");
  const [trainers, setTrainers] = useState([]);
  const [errors, setErrors] = useState({});
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const stallAllowed = !isPedigreeOnly(horse) && !isWithdrawn(horse);

  useEffect(() => {
    setCode(horse.registrationCode || "");
    setStall(horse.stallNo || "");
    setTrainerId(horse.trainerId ? String(horse.trainerId) : "");
  }, [horse.registrationCode, horse.stallNo, horse.trainerId]);

  useEffect(() => {
    if (!stallAllowed) return undefined;
    const ctrl = new AbortController();
    listActiveTrainers(ctrl.signal)
      .then((rows) => setTrainers(rows || []))
      .catch(() => {});
    return () => ctrl.abort();
  }, [stallAllowed, horse.trainerId]);

  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setMessage("");
    try {
      const updated = await updateHorseIdentity(horse.horseId, {
        registrationCode: code,
        stallNo: stallAllowed ? stall : null,
        trainerId: stallAllowed && trainerId ? Number(trainerId) : null,
      });
      onSaved(updated);
      setMessage("Saved.");
      if (stallAllowed) {
        listActiveTrainers().then((rows) => setTrainers(rows || [])).catch(() => {});
      }
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) setErrors(err.fieldErrors);
      else setMessage(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Club records & Trainer allocation" hint="Only the club manager can change these">
      <form onSubmit={save} noValidate>
        <div className="hz-form-grid hz-allocation-grid">
          <div className="hz-field">
            <label htmlFor="identity-code">Registration code</label>
            <input id="identity-code" className="hz-mono" maxLength={30} value={code}
                   onChange={(e) => setCode(e.target.value)} />
            {errors.registrationCode && <p className="hz-field__error">{errors.registrationCode}</p>}
          </div>
          {stallAllowed && (
            <>
              <div className="hz-field">
                <label htmlFor="identity-stall">Stall</label>
                <input id="identity-stall" className="hz-mono" maxLength={20} value={stall}
                       onChange={(e) => setStall(e.target.value)} />
                {errors.stallNo && <p className="hz-field__error">{errors.stallNo}</p>}
              </div>
              <div className="hz-field">
                <label htmlFor="identity-trainer">Assigned Head Trainer</label>
                <select
                  id="identity-trainer"
                  value={trainerId}
                  onChange={(e) => setTrainerId(e.target.value)}
                >
                  <option value="">— Unassigned —</option>
                  {trainers.map((t) => {
                    const isCurrent = Number(trainerId) === t.userId || horse.trainerId === t.userId;
                    const full = t.managedHorseCount >= t.maxHorses && !isCurrent;
                    return (
                      <option key={t.userId} value={t.userId} disabled={full}>
                        {t.fullName} ({t.managedHorseCount}/{t.maxHorses} horses)
                      </option>
                    );
                  })}
                </select>
                {errors.trainerId && <p className="hz-field__error">{errors.trainerId}</p>}
              </div>
            </>
          )}
        </div>
        {message && <p className="hz-muted hz-note" role="status">{message}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy}>{busy ? "Saving…" : "Save"}</button>
        </div>
      </form>
    </Card>
  );
}

/**
 * Club Manager: chuyển ngựa sang chủ khác, hoặc nhận lại ngựa đã rút. Backend
 * chặn chuyển chủ khi chủ hiện tại còn việc phải duyệt (giáo án, đăng ký giải,
 * yêu cầu rút) - lỗi đó hiện nguyên văn ở đây.
 */
/*
 * result / setResult live in the page, not here: a successful transfer changes
 * the owner and a re-admit changes the activity, and both remount this card
 * (its key), which used to wipe the "Transferred ... to ..." line the moment it
 * appeared.
 */
function OwnershipForm({ horse, onSaved, result, setResult }) {
  const navigate = useNavigate();
  const dialog = useAppDialog();
  const [owners, setOwners] = useState([]);
  const [ownerId, setOwnerId] = useState("");
  const [reason, setReason] = useState("");
  const [stall, setStall] = useState("");
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const withdrawn = isWithdrawn(horse);
  const pedigree = isPedigreeOnly(horse);

  useEffect(() => {
    if (withdrawn) return undefined;
    let alive = true;
    listStaff()
      .then((groups) => {
        if (!alive) return;
        const g = (groups || []).find((x) => x.roleName === "HORSE_OWNER");
        setOwners((g?.members || []).filter((m) => m.status === "ACTIVE" && m.userId !== horse.ownerId));
      })
      .catch(() => {});
    return () => { alive = false; };
  }, [withdrawn, horse.ownerId]);

  /* Runs fn; on success shows done(updated), on failure failText + the server's reason. */
  const run = async (fn, done, failText) => {
    setBusy(true);
    setErrors({});
    setResult(null);
    try {
      const updated = await fn();
      onSaved(updated);
      setResult(ok(done(updated)));
      return true;
    } catch (err) {
      if (handleAuthError(err, navigate)) return false;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) setErrors(err.fieldErrors);
      setResult(fail(`${failText}: ${err.message}`));
      return false;
    } finally {
      setBusy(false);
    }
  };

  if (withdrawn) {
    if (pedigree) return null;
    const readmit = async (e) => {
      e.preventDefault();
      const s = stall.trim();
      const done = await run(
        () => readmitHorse(horse.horseId, s),
        (h) => `${h.horseName} is back at the club in stall ${h.stallNo}. ${h.ownerName} has been notified. Assign a Head Trainer in Club records.`,
        `Could not re-admit ${horse.horseName} to stall ${s}`,
      );
      if (done) setStall("");
    };
    return (
      <Card title="Re-admit to the club" hint="The horse comes back with a new stall. Its health status stays as the vet left it.">
        {dialog.element}
        <form onSubmit={readmit} noValidate>
          <div className="hz-form-grid" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
            <div className="hz-field">
              <label htmlFor="readmit-stall">Stall</label>
              <input id="readmit-stall" className="hz-mono" maxLength={20} value={stall}
                     onChange={(e) => setStall(e.target.value)} />
              {errors.stallNo && <p className="hz-field__error">{errors.stallNo}</p>}
            </div>
          </div>
          <div className="hz-form-footer">
            <button type="submit" className="hz-btn" disabled={busy || !stall.trim()}>{busy ? "Saving…" : "Re-admit"}</button>
          </div>
          <ResultLine result={result} />
        </form>
      </Card>
    );
  }

  const transfer = async (e) => {
    e.preventDefault();
    const target = owners.find((o) => String(o.userId) === String(ownerId));
    const targetName = target ? `${target.fullName} (@${target.username})` : "the chosen owner";
    const confirmed = await dialog.confirm({
      title: `Transfer ${horse.horseName}?`,
      message: `${horse.horseName} moves from ${horse.ownerName} to ${targetName}. Both owners are told. Reason: ${reason.trim()}`,
      confirmLabel: "Transfer",
    });
    if (!confirmed) return;
    const done = await run(
      () => transferHorseOwner(horse.horseId, Number(ownerId), reason),
      () => `Transferred ${horse.horseName} from ${horse.ownerName} to ${targetName}. Both owners have been notified.`,
      `Could not transfer ${horse.horseName} to ${targetName}`,
    );
    if (done) { setOwnerId(""); setReason(""); }
  };

  return (
    <Card title="Transfer to another owner" hint="For a sale or a gift. The current and the new owner are both told.">
      {dialog.element}
      <form onSubmit={transfer} noValidate>
        <div className="hz-form-grid" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
          <div className="hz-field">
            <label htmlFor="transfer-owner">New owner</label>
            <select id="transfer-owner" value={ownerId} onChange={(e) => setOwnerId(e.target.value)}>
              <option value="">Choose an owner…</option>
              {owners.map((o) => <option key={o.userId} value={o.userId}>{o.fullName} (@{o.username})</option>)}
            </select>
            {errors.ownerId && <p className="hz-field__error">{errors.ownerId}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="transfer-reason">Reason</label>
            <input id="transfer-reason" maxLength={255} value={reason} placeholder="e.g. Sold on 9 Nov 2026"
                   onChange={(e) => setReason(e.target.value)} />
            {errors.reason && <p className="hz-field__error">{errors.reason}</p>}
          </div>
        </div>
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy || !ownerId || !reason.trim()}>{busy ? "Saving…" : "Transfer"}</button>
        </div>
        <ResultLine result={result} />
      </form>
    </Card>
  );
}

/**
 * Club Manager (V41): correct the sire / dam on record when the papers show
 * otherwise. The owner can only add a parent that is still unknown, so this is
 * the one place a parent on record changes. A reason is required; it goes into
 * the audit log and to the owner.
 */
function ParentsForm({ horse, onSaved }) {
  const navigate = useNavigate();
  const [herd, setHerd] = useState([]);
  const [sireId, setSireId] = useState(horse.sireId ? String(horse.sireId) : "");
  const [damId, setDamId] = useState(horse.damId ? String(horse.damId) : "");
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  useEffect(() => {
    setSireId(horse.sireId ? String(horse.sireId) : "");
    setDamId(horse.damId ? String(horse.damId) : "");
  }, [horse.sireId, horse.damId]);

  useEffect(() => {
    const ctrl = new AbortController();
    listParentCandidates(ctrl.signal, horse.horseId).then((h) => setHerd(h || [])).catch(() => {});
    return () => ctrl.abort();
  }, [horse.horseId]);

  const others = herd.filter((h) => h.horseId !== horse.horseId);
  const sires = others.filter((h) => SIRE_GENDERS.has(h.horseGender));
  const dams = others.filter((h) => DAM_GENDERS.has(h.horseGender));
  const changed = String(horse.sireId ?? "") !== sireId || String(horse.damId ?? "") !== damId;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setResult(null);
    try {
      const updated = await correctHorseParents(horse.horseId, { sireId, damId, reason });
      onSaved(updated);
      setReason("");
      setResult(ok(`Parents of ${updated.horseName} corrected. ${updated.ownerName} has been notified.`));
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors) setErrors(err.fieldErrors);
      setResult(fail(`Could not correct the parents of ${horse.horseName}: ${err.message}`));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Parents" hint="From the horse's papers. Correct them when the papers show otherwise: the owner is told and the reason is logged.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
          <div className="hz-field">
            <label htmlFor="correct-sire">Sire</label>
            <select id="correct-sire" value={sireId} onChange={(e) => setSireId(e.target.value)}>
              <option value="">Unknown</option>
              {sires.map((h) => <option key={h.horseId} value={h.horseId}>{parentLabel(h)}</option>)}
            </select>
            {errors.sireId && <p className="hz-field__error">{errors.sireId}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="correct-dam">Dam</label>
            <select id="correct-dam" value={damId} onChange={(e) => setDamId(e.target.value)}>
              <option value="">Unknown</option>
              {dams.map((h) => <option key={h.horseId} value={h.horseId}>{parentLabel(h)}</option>)}
            </select>
            {errors.damId && <p className="hz-field__error">{errors.damId}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="correct-reason">Reason</label>
            <input id="correct-reason" maxLength={200} value={reason} placeholder="What the papers show"
                   onChange={(e) => setReason(e.target.value)} />
            {errors.reason && <p className="hz-field__error">{errors.reason}</p>}
          </div>
        </div>
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={busy || !changed || !reason.trim()}>
            {busy ? "Saving…" : "Correct parents"}
          </button>
        </div>
        <ResultLine result={result} />
      </form>
    </Card>
  );
}

export default function HorseDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const user = getUser();

  const isVet = user?.role === "VETERINARIAN";
  const mayViewPedigree = canViewPedigree(user);
  const mayViewVitals = canViewVitals(user);
  const horseBase = getHorseBasePath(user);

  const [searchParams] = useSearchParams();
  const initialTab = searchParams.get("tab") || "overview";
  const [tab, setTab] = useState(initialTab);
  const [horse, setHorse] = useState(null);
  const [ownershipResult, setOwnershipResult] = useState(null); // survives the card's remount
  useEffect(() => { setOwnershipResult(null); }, [id]); // another horse: start clean
  const [error, setError] = useState("");

  const [generations, setGenerations] = useState(3);
  const [pedigree, setPedigree] = useState(null);
  const [pedigreeError, setPedigreeError] = useState("");

  const [vitals, setVitals] = useState(null);
  const [vitalsError, setVitalsError] = useState("");

  const fail = useCallback(
    (set) => (err) => {
      if (err.name === "AbortError") return;
      if (handleAuthError(err, navigate)) return;
      set(err.message);
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    getHorse(id, ctrl.signal)
      .then(setHorse)
      .catch(fail(setError));
    return () => ctrl.abort();
  }, [id, fail]);

  useEffect(() => {
    if (tab !== "pedigree") return undefined;
    const ctrl = new AbortController();
    setPedigreeError("");
    getPedigree(id, generations, ctrl.signal)
      .then(setPedigree)
      .catch(fail(setPedigreeError));
    return () => ctrl.abort();
  }, [tab, id, generations, fail]);

  useEffect(() => {
    if (tab !== "vitals" || vitals) return undefined;
    const ctrl = new AbortController();
    setVitalsError("");
    getVitals(id, ctrl.signal)
      .then(setVitals)
      .catch(fail(setVitalsError));
    return () => ctrl.abort();
  }, [tab, id, vitals, fail]);

  if (error) {
    return (
      <HorseShell title="Horse">
        <StateBlock kind="error" title={error}>
          Go back to <Link to={horseBase}>the registry</Link>.
        </StateBlock>
      </HorseShell>
    );
  }

  if (!horse) {
    return (
      <HorseShell title="Horse">
        <StateBlock title="Loading horse…" />
      </HorseShell>
    );
  }

  const st = toStatus(horse.status);
  const stg = toStage(horse);
  const pedigreeOnly = isPedigreeOnly(horse);
  const mayEdit = canEditHorse(user, horse);
  const age = toAge(horse.dateOfBirth);
  const withdrawn = isWithdrawn(horse);
  const act = toActivity(horse.activityStatus);

  return (
    <HorseShell
      title={horse.horseName}
      subtitle={`${horse.registrationCode} · ${toGender(
        horse.horseGender
      )}${age == null ? "" : ` · ${age} years old`}`}
      actions={
        <>
          <StatusChip tone={stg.tone} label={stg.label} title={stg.hint} />

          {withdrawn && (
            <StatusChip tone={act.tone} label={act.label} title={act.hint} />
          )}

          {healthApplies(horse) && (
            <StatusChip tone={st.tone} label={st.label} title={st.hint} />
          )}

          {mayEdit && (
            <button
              type="button" className="hz-btn"
              onClick={() => navigate(`${horseBase}/${id}/edit`)}
            >
              Edit
            </button>
          )}

          {isVet && !pedigreeOnly && !withdrawn && (
            <button
              type="button" className="hz-btn"
              onClick={() => navigate(`/veterinarian/examination/${id}`)}
            >
              Update health status
            </button>
          )}

          {isVet && !pedigreeOnly && !withdrawn && horse.status !== "ELIGIBLE" && (
            <button
              type="button" className="hz-btn hz-btn--ghost"
              onClick={() => navigate(`/veterinarian/recovery/${id}`)}
            >
              Recovery
            </button>
          )}

          <button
            type="button" className="hz-btn hz-btn--ghost"
            onClick={() => navigate(horseBase)}
          >
            Back
          </button>
        </>
      }
    >
      <div className="hz-tabs" role="tablist">
        {TABS.filter((t) =>
          (t.id !== "pedigree" || mayViewPedigree) &&
          (t.id !== "vitals" || (mayViewVitals && !pedigreeOnly)) &&
          (t.id !== "medical-history" || !pedigreeOnly)
        ).map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={tab === t.id ? "active" : ""}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="hz-detail-grid">
          <Card title="Identity">
            <HorsePhoto horse={horse} className="hz-photo--sm" />
            <Row label="Name">{horse.horseName}</Row>
            <Row label="Registration code">
              <span className="hz-mono">{horse.registrationCode}</span>
            </Row>
            <Row label="Sex">{toGender(horse.horseGender)}</Row>
            <Row label="Date of birth">
              {formatDate(horse.dateOfBirth)}
              {age != null && <span className="hz-muted"> · {age} years</span>}
            </Row>
            <Row label="Breed">{horse.breed || "—"}</Row>
            <Row label="Colour">{horse.color || "—"}</Row>
          </Card>

          <Card title={pedigreeOnly ? "Record" : "Stable"}>
            {pedigreeOnly ? (
              <p className="hz-muted hz-note">
                Pedigree record only. This horse is kept as a parent or ancestor, so it has no
                stall, weight, health status or training plan.
              </p>
            ) : (
              <>
                <Row label="Weight">{formatWeight(horse.weight)}</Row>
                <Row label="Stall">
                  <span className="hz-mono">{horse.stallNo || "—"}</span>
                </Row>
                <Row label="Head Trainer">
                  {horse.trainerName || "Unassigned"}
                </Row>
              </>
            )}

            <Row label="Owner">
              {horse.ownerName || `User #${horse.ownerId}`}
            </Row>

            {healthApplies(horse) && (
              <Row label="Health status">
                <StatusChip tone={st.tone} label={st.label} title={st.hint} />
                <p className="hz-muted hz-note">
                  {isVet
                    ? "Record an examination to change it, or confirm recovery to clear it."
                    : "Set by the veterinarian, not in this form."}
                </p>
              </Row>
            )}

            <Row label="Registered">{formatDateTime(horse.createdAt)}</Row>
            <Row label="Last updated">{formatDateTime(horse.updatedAt)}</Row>
          </Card>

          {user?.role === "CLUB_MANAGER" && <IdentityForm key={`id-${horse.activityStatus}`} horse={horse} onSaved={setHorse} />}
          {user?.role === "CLUB_MANAGER" && <OwnershipForm key={`own-${horse.ownerId}-${horse.activityStatus}`} horse={horse} onSaved={setHorse}
                                                                  result={ownershipResult} setResult={setOwnershipResult} />}
          {user?.role === "CLUB_MANAGER" && <ParentsForm horse={horse} onSaved={setHorse} />}

          {!pedigreeOnly && <HorseHealthPanel horse={horse} user={user} onViewHistory={() => setTab("medical-history")} />}

          <HorseWithdrawalPanel horse={horse} user={user} />
        </div>
      )}

      {tab === "medical-history" && !pedigreeOnly && (
        <HorseMedicalHistoryTab horse={horse} user={user} />
      )}

      {tab === "pedigree" && (
        <Card
          title="Pedigree"
          hint="Sire line runs along the top of each pair, dam line along the bottom"
          actions={
            <div className="hz-segment" role="group" aria-label="Generations to show">
              {GENERATION_OPTIONS.map((g) => (
                <button
                  key={g}
                  type="button"
                  aria-pressed={g === generations}
                  onClick={() => setGenerations(g)}
                >
                  {g} gen
                </button>
              ))}
            </div>
          }
        >
          {pedigreeError ? (
            <StateBlock kind="error" title={pedigreeError} />
          ) : !pedigree ? (
            <StateBlock title="Loading pedigree…" />
          ) : !pedigree.sire && !pedigree.dam ? (
            <StateBlock title="No parents on record">
              {mayEdit
                ? "Add the sire and dam from the horse's papers in the edit form to start the pedigree."
                : "Nobody has recorded this horse's parents yet."}
            </StateBlock>
          ) : (
            <PedigreeTree root={pedigree} />
          )}
        </Card>
      )}

      {tab === "vitals" &&
        (vitalsError ? (
          <StateBlock kind="error" title={vitalsError} />
        ) : !vitals ? (
          <StateBlock title="Loading vitals…" />
        ) : (
          <VitalsPanel vitals={vitals} />
        ))}
    </HorseShell>
  );
}
