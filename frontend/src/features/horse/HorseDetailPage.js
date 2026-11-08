// src/features/horse/HorseDetailPage.js
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";

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
} from "./horseApi";

const TABS = [
  { id: "overview", label: "Overview" },
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
        <div className="hz-form-grid">
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

export default function HorseDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const user = getUser();

  const isVet = user?.role === "VETERINARIAN";
  const mayViewPedigree = canViewPedigree(user);
  const mayViewVitals = canViewVitals(user);
  const horseBase = getHorseBasePath(user);

  const [tab, setTab] = useState("overview");
  const [horse, setHorse] = useState(null);
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
          (t.id !== "vitals" || (mayViewVitals && !pedigreeOnly))
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

          {user?.role === "CLUB_MANAGER" && <IdentityForm horse={horse} onSaved={setHorse} />}

          {!pedigreeOnly && <HorseHealthPanel horse={horse} user={user} />}

          <HorseWithdrawalPanel horse={horse} user={user} />
        </div>
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
                ? "Name a sire or a dam in the edit form to start building the pedigree."
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
