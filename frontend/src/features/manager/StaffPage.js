// src/features/manager/StaffPage.js
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import PasswordField from "../password/PasswordField";
import {
  listStaff, matchesQuery, initialsOf, statusTone, statusLabel, groupTitle,
  createStaff, setStaffStatus, changeStaffRole, assignStaffTrainer, updateTrainerMaxHorses,
  reassignNotice, STAFF_ROLE_OPTIONS, STAFF_ROLE_CODES,
} from "./staffApi";
import "../password/password.css";
import "./staff.css";

function Avatar({ row }) {
  if (row.avatarUrl) {
    return <img className="st-avatar" src={row.avatarUrl} alt="" />;
  }
  return (
    <span className="st-avatar st-avatar--text" aria-hidden="true">
      {initialsOf(row.fullName)}
    </span>
  );
}

const EMPTY_FORM = {
  fullName: "", username: "", email: "", phone: "", roleName: "GROOM", password: "",
  trainerId: "", maxHorses: 10,
};

function MemberRow({
  row, activeTrainers, canToggle, canChangeRole,
  onToggleStatus, onChangeRole, onAssignTrainer, onUpdateMaxHorses, busy,
}) {
  return (
    <li className="st-row">
      <Avatar row={row} />

      <div className="st-identity">
        <p className="st-name">{row.fullName}</p>
        <p className="st-username">@{row.username}</p>
      </div>

      <div className="st-contact">
        <a className="st-email" href={`mailto:${row.email}`}>{row.email}</a>
        <p className="st-phone">{row.phone || "—"}</p>
      </div>

      <div className="st-meta">
        {row.roleName === "HORSE_OWNER" && (
          <span className="st-horses">
            {row.horseCount} {row.horseCount === 1 ? "horse" : "horses"}
          </span>
        )}
        {row.roleName === "HEAD_TRAINER" && (
          <span className="st-horses" title="Active Grooms (max 3) & Managed Club Horses">
            Grooms: {row.groomCount || 0}/3 · Horses: {row.managedHorseCount || 0}/{row.maxHorses ?? 10}
          </span>
        )}
        {row.roleName === "GROOM" && (
          <span className="st-horses">
            Trainer: {row.trainerName || "Unassigned"}
          </span>
        )}
        <StatusChip tone={statusTone(row.status)} label={statusLabel(row.status)} />
      </div>

      <div className="st-actions">
        {row.roleName === "GROOM" && (
          <select
            className="st-action st-role"
            value={row.trainerId ?? ""}
            disabled={busy}
            onChange={(e) => onAssignTrainer(row, e.target.value)}
            aria-label={`Head Trainer of ${row.fullName}`}
            title="Assign Groom to Head Trainer (max 3 active grooms per trainer)"
          >
            <option value="">— No Head Trainer —</option>
            {activeTrainers.map((t) => {
              const full = (t.groomCount || 0) >= 3 && t.userId !== row.trainerId;
              return (
                <option key={t.userId} value={t.userId} disabled={full}>
                  {t.fullName} ({t.groomCount || 0}/3 grooms)
                </option>
              );
            })}
          </select>
        )}

        {row.roleName === "HEAD_TRAINER" && (
          <button
            type="button"
            className="st-action"
            disabled={busy}
            onClick={() => onUpdateMaxHorses(row)}
            title="Set maximum number of club horses this Head Trainer can manage"
          >
            Max horses ({row.maxHorses ?? 10})
          </button>
        )}

        {canToggle && (
          <button
            type="button"
            className="st-action"
            disabled={busy}
            onClick={() => onToggleStatus(row)}
          >
            {row.status === "ACTIVE" ? "Suspend" : "Activate"}
          </button>
        )}
        {canChangeRole && (
          <select
            className="st-action st-role"
            value={row.roleName}
            disabled={busy}
            onChange={(e) => onChangeRole(row, e.target.value)}
            aria-label={`Role of ${row.fullName}`}
          >
            {STAFF_ROLE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        )}
      </div>
    </li>
  );
}

function RoleGroup({
  group, activeTrainers, query, myId, busyId,
  onToggleStatus, onChangeRole, onAssignTrainer, onUpdateMaxHorses,
}) {
  const shown = group.members.filter((m) => matchesQuery(m, query));
  const emptyText = group.members.length === 0
    ? "No accounts in this role yet"
    : "No one here matches your search";

  return (
    <Card
      className="st-group"
      title={groupTitle(group)}
      hint={`${group.members.length} ${group.members.length === 1 ? "account" : "accounts"}`}
    >
      {shown.length === 0 ? (
        <p className="hz-empty">{emptyText}</p>
      ) : (
        <ul className="st-list">
          {shown.map((row) => (
            <MemberRow
              key={row.userId}
              row={row}
              activeTrainers={activeTrainers}
              canToggle={row.roleName !== "CLUB_MANAGER" && row.userId !== myId}
              canChangeRole={STAFF_ROLE_CODES.has(row.roleName) && row.userId !== myId}
              onToggleStatus={onToggleStatus}
              onChangeRole={onChangeRole}
              onAssignTrainer={onAssignTrainer}
              onUpdateMaxHorses={onUpdateMaxHorses}
              busy={busyId === row.userId}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

export default function StaffPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState(null);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const myId = getUser()?.userId;

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [notice, setNotice] = useState("");

  const load = useCallback(() => {
    setError("");
    setGroups(null);
    listStaff()
      .then(setGroups)
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
  }, [navigate]);

  useEffect(load, [load]);

  const everyone = useMemo(
    () => (groups || []).flatMap((g) => g.members),
    [groups],
  );
  const activeTrainers = useMemo(
    () => everyone.filter((r) => r.roleName === "HEAD_TRAINER" && r.status === "ACTIVE"),
    [everyone],
  );
  const totalShown = useMemo(
    () => everyone.filter((r) => matchesQuery(r, query)).length,
    [everyone, query],
  );

  const setField = (name) => (e) => setForm((f) => ({ ...f, [name]: e.target.value }));

  const closeForm = () => {
    setFormOpen(false);
    setForm(EMPTY_FORM);
    setFieldErrors({});
    setFormError("");
  };

  const submitForm = (e) => {
    e.preventDefault();
    setSaving(true);
    setFieldErrors({});
    setFormError("");
    const payload = {
      fullName: form.fullName,
      username: form.username,
      email: form.email,
      phone: form.phone,
      roleName: form.roleName,
      password: form.password,
      trainerId: form.roleName === "GROOM" && form.trainerId ? Number(form.trainerId) : null,
      maxHorses: form.roleName === "HEAD_TRAINER" && form.maxHorses ? Number(form.maxHorses) : null,
    };
    createStaff(payload)
      .then((created) => {
        closeForm();
        setNotice(`Created account @${created.username}. Give them the temporary password you set; `
          + "they will be asked to choose their own password the first time they sign in.");
        load();
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setFieldErrors(err.fieldErrors || {});
        setFormError(err.message);
      })
      .finally(() => setSaving(false));
  };

  const toggleStatus = (row) => {
    const next = row.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    if (next === "SUSPENDED" && !window.confirm(`Suspend @${row.username}? They will be signed out and cannot log in.`)) {
      return;
    }
    setBusyId(row.userId);
    setNotice("");
    setStaffStatus(row.userId, next)
      .then(({ member, sessionsToReassign }) => {
        setNotice(`@${member.username} is now ${next === "ACTIVE" ? "active" : "suspended"}.`
          + reassignNotice(member.fullName, sessionsToReassign));
        load();
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setNotice(err.message);
      })
      .finally(() => setBusyId(null));
  };

  const changeRole = (row, roleName) => {
    if (roleName === row.roleName) return;
    const label = STAFF_ROLE_OPTIONS.find((o) => o.value === roleName)?.label || roleName;
    if (!window.confirm(`Change @${row.username} to ${label}? They will be signed out and get the new permissions when they sign in again.`)) {
      return;
    }
    setBusyId(row.userId);
    setNotice("");
    changeStaffRole(row.userId, roleName)
      .then(({ member, sessionsToReassign }) => {
        setNotice(`@${member.username} is now ${label}.` + reassignNotice(member.fullName, sessionsToReassign));
        load();
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setNotice(err.message);
      })
      .finally(() => setBusyId(null));
  };

  const handleAssignTrainer = (row, trainerIdStr) => {
    const nextTrainerId = trainerIdStr ? Number(trainerIdStr) : null;
    if ((row.trainerId ?? null) === nextTrainerId) return;
    setBusyId(row.userId);
    setNotice("");
    assignStaffTrainer(row.userId, nextTrainerId)
      .then((updated) => {
        setNotice(updated.trainerName
          ? `Assigned Groom ${updated.fullName} to Head Trainer ${updated.trainerName}.`
          : `Unassigned Groom ${updated.fullName} from Head Trainer.`);
        load();
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setNotice(err.message);
      })
      .finally(() => setBusyId(null));
  };

  const handleUpdateMaxHorses = (row) => {
    const input = window.prompt(
      `Set maximum horses that Head Trainer ${row.fullName} can manage (currently managing ${row.managedHorseCount || 0}):`,
      String(row.maxHorses ?? 10),
    );
    if (input === null) return;
    const parsed = Number(input.trim());
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) {
      setNotice("Max horses must be an integer between 1 and 100.");
      return;
    }
    setBusyId(row.userId);
    setNotice("");
    updateTrainerMaxHorses(row.userId, parsed)
      .then((updated) => {
        setNotice(`Updated max horse limit for ${updated.fullName} to ${updated.maxHorses}.`);
        load();
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setNotice(err.message);
      })
      .finally(() => setBusyId(null));
  };

  return (
    <HorseShell
      title="Staff"
      subtitle="Everyone with an account, grouped by role"
      actions={
        <div className="hz-filters st-filters">
          <input
            className="st-search"
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search name, username, email or phone"
            aria-label="Search staff"
          />
          {query && (
            <button type="button" className="hz-clear" onClick={() => setQuery("")}>
              Clear
            </button>
          )}
          <button type="button" className="hz-btn" onClick={() => setFormOpen((v) => !v)}>
            {formOpen ? "Close" : "Add staff"}
          </button>
        </div>
      }
    >
      {error && (
        <StateBlock kind="error" title="Could not load staff" onRetry={load}>
          {error}
        </StateBlock>
      )}

      {!error && groups === null && (
        <StateBlock kind="info" title="Loading staff…" />
      )}

      {formOpen && (
        <Card title="Add staff account" hint="Head Trainer, Veterinarian or Groom">
          <form className="st-form" onSubmit={submitForm} noValidate>
            <label className="st-field">
              Full name
              <input value={form.fullName} onChange={setField("fullName")} />
              {fieldErrors.fullName && <span className="st-field__error">{fieldErrors.fullName}</span>}
            </label>
            <label className="st-field">
              Role
              <select value={form.roleName} onChange={setField("roleName")}>
                {STAFF_ROLE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              {fieldErrors.roleName && <span className="st-field__error">{fieldErrors.roleName}</span>}
            </label>
            {form.roleName === "GROOM" && (
              <label className="st-field">
                Assign to Head Trainer (max 3 grooms/trainer)
                <select value={form.trainerId} onChange={setField("trainerId")}>
                  <option value="">— Select Head Trainer —</option>
                  {activeTrainers.map((t) => (
                    <option key={t.userId} value={t.userId} disabled={(t.groomCount || 0) >= 3}>
                      {t.fullName} ({t.groomCount || 0}/3 grooms)
                    </option>
                  ))}
                </select>
                {fieldErrors.trainerId && <span className="st-field__error">{fieldErrors.trainerId}</span>}
              </label>
            )}
            {form.roleName === "HEAD_TRAINER" && (
              <label className="st-field">
                Max managed horses (1–100)
                <input
                  type="number"
                  min={1}
                  max={100}
                  value={form.maxHorses}
                  onChange={setField("maxHorses")}
                />
                {fieldErrors.maxHorses && <span className="st-field__error">{fieldErrors.maxHorses}</span>}
              </label>
            )}
            <label className="st-field">
              Username
              <input value={form.username} onChange={setField("username")} autoComplete="off" />
              {fieldErrors.username && <span className="st-field__error">{fieldErrors.username}</span>}
            </label>
            <label className="st-field">
              Email
              <input type="email" value={form.email} onChange={setField("email")} />
              {fieldErrors.email && <span className="st-field__error">{fieldErrors.email}</span>}
            </label>
            <label className="st-field">
              Phone (optional)
              <input value={form.phone} onChange={setField("phone")} />
              {fieldErrors.phone && <span className="st-field__error">{fieldErrors.phone}</span>}
            </label>
            <PasswordField
              id="staffPassword"
              label="Temporary password"
              value={form.password}
              onChange={setField("password")}
              error={fieldErrors.password}
              autoComplete="new-password"
              showRules
            />
            <div className="st-form__foot">
              <button type="submit" className="hz-btn" disabled={saving}>
                {saving ? "Creating…" : "Create account"}
              </button>
              <button type="button" className="st-action" onClick={closeForm}>Cancel</button>
              {formError && <span className="st-field__error" role="alert">{formError}</span>}
            </div>
          </form>
        </Card>
      )}

      {notice && <p className="st-notice" role="status">{notice}</p>}

      {!error && groups !== null && (
        <>
          <p className="st-count">
            {query
              ? `${totalShown} of ${everyone.length} accounts match “${query}”`
              : `${everyone.length} accounts`}
          </p>
          {groups.map((group) => (
            <RoleGroup
              key={group.roleName}
              group={group}
              activeTrainers={activeTrainers}
              query={query}
              myId={myId}
              busyId={busyId}
              onToggleStatus={toggleStatus}
              onChangeRole={changeRole}
              onAssignTrainer={handleAssignTrainer}
              onUpdateMaxHorses={handleUpdateMaxHorses}
            />
          ))}
        </>
      )}
    </HorseShell>
  );
}
