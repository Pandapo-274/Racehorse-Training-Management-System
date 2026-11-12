// src/features/manager/StaffPage.js
// The club manager's Staff screen: every account in the system, grouped by role.
//
// The manager can add accounts (Head Trainer, Vet, Groom, and Horse Owners who
// register at the desk), move staff between the three staff roles, correct
// contact details, set a temporary password, and suspend / activate any
// non-manager account.
// Permissions belong to roles (RBAC), so the role picker on each staff row is
// how the manager changes what someone can do. New accounts get a temporary
// password that the person must change at their first sign-in.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import PasswordField from "../password/PasswordField";
import TrainerTeamDialog from "./TrainerTeamDialog";
import { useAppDialog } from "../common/AppDialog";
import {
  listStaff, matchesQuery, initialsOf, statusTone, statusLabel, groupTitle,
  createStaff, setStaffStatus, changeStaffRole, assignStaffTrainer, updateTrainerMaxHorses, leavingConsequences,
  getOpenWork, successorLabel,
  updateStaffContact, setTemporaryPassword,
  reassignNotice, STAFF_ROLE_OPTIONS, STAFF_ROLE_CODES, CREATE_ROLE_OPTIONS,
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
  onToggleStatus, onChangeRole, onAssignTrainer, onUpdateMaxHorses, onEdit, onResetPassword, onShowTeam, busy,
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
          <span className="st-horses st-team">
            <button type="button" className="st-team-link" onClick={() => onShowTeam(row, "grooms")}
                    title="Show the grooms of this Head Trainer (max 3 active)">
              Grooms: {row.groomCount || 0}/3
            </button>
            ·
            <button type="button" className="st-team-link" onClick={() => onShowTeam(row, "horses")}
                    title="Show the club horses this Head Trainer manages">
              Horses: {row.managedHorseCount || 0}/{row.maxHorses ?? 10}
            </button>
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
          <button type="button" className="st-action" disabled={busy} onClick={() => onEdit(row)}>
            Edit
          </button>
        )}
        {canToggle && (
          <button type="button" className="st-action" disabled={busy} onClick={() => onResetPassword(row)}>
            Reset password
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
  onToggleStatus, onChangeRole, onAssignTrainer, onUpdateMaxHorses, onEdit, onResetPassword, onShowTeam,
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
              onEdit={onEdit}
              onResetPassword={onResetPassword}
              onAssignTrainer={onAssignTrainer}
              onUpdateMaxHorses={onUpdateMaxHorses}
              onShowTeam={onShowTeam}
              busy={busyId === row.userId}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

/** Edit contact details, or set a temporary password, for one account. */
function AccountPanel({ mode, row, onDone, onCancel }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: row.fullName, email: row.email, phone: row.phone || "", password: "" });
  const [errors, setErrors] = useState({});
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const editing = mode === "edit";

  const submit = (e) => {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setMessage("");
    const call = editing ? updateStaffContact(row.userId, form) : setTemporaryPassword(row.userId, form.password);
    call
      .then((member) => onDone(member, editing
        ? `Saved @${member.username}.`
        : `New temporary password set for @${member.username}. They are signed out and must choose their own password at the next sign-in.`))
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setErrors(err.fieldErrors || {});
        setMessage(err.message);
      })
      .finally(() => setBusy(false));
  };

  return (
    <Card title={editing ? `Edit @${row.username}` : `Reset password of @${row.username}`}
          hint={editing ? "Username and role are changed elsewhere" : "Tell them the temporary password in person"}>
      <form className="st-form" onSubmit={submit} noValidate>
        {editing ? (
          <>
            <label className="st-field">
              Full name
              <input value={form.fullName} onChange={set("fullName")} />
              {errors.fullName && <span className="st-field__error">{errors.fullName}</span>}
            </label>
            <label className="st-field">
              Email
              <input type="email" value={form.email} onChange={set("email")} />
              {errors.email && <span className="st-field__error">{errors.email}</span>}
            </label>
            <label className="st-field">
              Phone (optional)
              <input value={form.phone} onChange={set("phone")} />
              {errors.phone && <span className="st-field__error">{errors.phone}</span>}
            </label>
          </>
        ) : (
          <PasswordField
            id="tempPassword"
            label="Temporary password"
            value={form.password}
            onChange={set("password")}
            error={errors.password}
            autoComplete="new-password"
            showRules
          />
        )}
        <div className="st-form__foot">
          <button type="submit" className="hz-btn" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save" : "Set password"}
          </button>
          <button type="button" className="st-action" onClick={onCancel}>Cancel</button>
          {message && <span className="st-field__error" role="alert">{message}</span>}
        </div>
      </form>
    </Card>
  );
}

export default function StaffPage() {
  const navigate = useNavigate();
  const dialog = useAppDialog();
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
  const [panel, setPanel] = useState(null); // { mode: "edit" | "password", row }
  const [team, setTeam] = useState(null); // { row, focus: "grooms" | "horses" } -> TrainerTeamDialog
  const closeTeam = useCallback(() => setTeam(null), []);

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

  /**
   * Confirm that someone leaves their role (suspension or role change). The
   * dialog lists what they leave unfinished; when someone of the same role can
   * take it over, the manager picks them in the same dialog. Any error from the
   * request (e.g. that Head Trainer has no room) is shown inside the dialog.
   * send(handoverTo) does the request; resolves with its result, or null if cancelled.
   */
  const confirmLeaving = async (row, { title, message, confirmLabel, danger }, send) => {
    let work = null;
    if (STAFF_ROLE_CODES.has(row.roleName)) {
      try {
        work = await getOpenWork(row.userId);
      } catch (err) {
        if (handleAuthError(err, navigate)) return null;
      }
    }
    const items = work?.items || [];
    let result = null;
    if (items.length > 0 && work.candidates.length > 0) {
      const picked = await dialog.prompt({
        title, danger, confirmLabel,
        message: `${message} Unfinished work:`,
        items,
        note: work.note,
        label: "Hand it over to",
        options: [
          { value: "", label: work.fallback },
          ...work.candidates.map((c) => ({ value: String(c.userId), label: successorLabel(work.role, c) })),
        ],
        defaultValue: "",
        onSubmit: async (v) => { result = await send(v ? Number(v) : null); },
      });
      return picked === null ? null : result;
    }
    const nobody = items.length > 0 && work.fallback
      ? `No one of the same role can take it over. ${work.fallback.replace(/^Nobody: /, "").replace(/^./, (c) => c.toUpperCase())}.`
      : null;
    const ok = await dialog.confirm({
      title, danger, confirmLabel,
      message: work ? (items.length ? `${message} Unfinished work:` : message) : message + leavingConsequences(row),
      items,
      note: [nobody, work?.note].filter(Boolean).join(" ") || null,
    });
    if (!ok) return null;
    return send(null);
  };

  const toggleStatus = async (row) => {
    const next = row.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    setNotice("");
    let done;
    try {
      if (next === "SUSPENDED") {
        done = await confirmLeaving(row, {
          title: `Suspend ${row.fullName}?`,
          message: row.roleName === "HORSE_OWNER"
            ? `@${row.username} will be signed out and cannot sign in. Their pending horse registration requests are cancelled, and head trainers are told about approvals on hold.`
            : `@${row.username} will be signed out and cannot sign in until the account is activated again.`,
          confirmLabel: "Suspend",
          danger: true,
        }, (handoverTo) => {
          setBusyId(row.userId);
          return setStaffStatus(row.userId, next, handoverTo);
        });
        if (!done) return;
      } else {
        setBusyId(row.userId);
        done = await setStaffStatus(row.userId, next);
      }
      const { member, sessionsToReassign, notice: extra } = done;
      setNotice(`@${member.username} is now ${next === "ACTIVE" ? "active" : "suspended"}.`
        + reassignNotice(member.fullName, sessionsToReassign)
        + (extra ? ` ${extra}` : ""));
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setNotice(err.message);
    } finally {
      setBusyId(null);
    }
  };

  const changeRole = async (row, roleName) => {
    if (roleName === row.roleName) return;
    const label = STAFF_ROLE_OPTIONS.find((o) => o.value === roleName)?.label || roleName;
    setNotice("");
    try {
      const done = await confirmLeaving(row, {
        title: `Change role to ${label}?`,
        message: `${row.fullName} (@${row.username}) will be signed out and get the new permissions when they sign in again.`,
        confirmLabel: "Change role",
      }, (handoverTo) => {
        setBusyId(row.userId);
        return changeStaffRole(row.userId, roleName, handoverTo);
      });
      if (!done) return;
      const { member, sessionsToReassign, notice: extra } = done;
      setNotice(`@${member.username} is now ${label}.` + reassignNotice(member.fullName, sessionsToReassign)
        + (extra ? ` ${extra}` : ""));
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setNotice(err.message);
    } finally {
      setBusyId(null);
    }
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

  const handleUpdateMaxHorses = async (row) => {
    const managed = row.managedHorseCount || 0;
    let updated = null;
    const value = await dialog.prompt({
      title: "Max horses",
      message: `How many club horses can ${row.fullName} manage? They manage ${managed} now.`,
      label: "Maximum horses (1-100)",
      inputType: "number",
      min: 1,
      max: 100,
      defaultValue: String(row.maxHorses ?? 10),
      validate: (v) => {
        const n = Number(v);
        if (!Number.isInteger(n) || n < 1 || n > 100) return "Enter a whole number from 1 to 100";
        if (n < managed) return `${row.fullName} manages ${managed} horses now. Reassign some horses before going below ${managed}.`;
        return null;
      },
      // The request runs inside the dialog: a refusal from the server is shown there.
      onSubmit: async (v) => {
        try {
          updated = await updateTrainerMaxHorses(row.userId, Number(v));
        } catch (err) {
          if (handleAuthError(err, navigate)) return;
          throw err;
        }
      },
    });
    if (value === null || !updated) return;
    setNotice(`${updated.fullName} can now manage up to ${updated.maxHorses} horses.`);
    load();
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
        <Card title="Add account" hint="Head Trainer, Veterinarian, Groom, or a Horse Owner registering at the desk">
          <form className="st-form" onSubmit={submitForm} noValidate>
            <label className="st-field">
              Full name
              <input value={form.fullName} onChange={setField("fullName")} />
              {fieldErrors.fullName && <span className="st-field__error">{fieldErrors.fullName}</span>}
            </label>
            <label className="st-field">
              Role
              <select value={form.roleName} onChange={setField("roleName")}>
                {CREATE_ROLE_OPTIONS.map((o) => (
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

      {panel && (
        <AccountPanel
          key={`${panel.mode}-${panel.row.userId}`}
          mode={panel.mode}
          row={panel.row}
          onCancel={() => setPanel(null)}
          onDone={(member, message) => { setPanel(null); setNotice(message); load(); }}
        />
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
              onEdit={(row) => { setNotice(""); setPanel({ mode: "edit", row }); }}
              onResetPassword={(row) => { setNotice(""); setPanel({ mode: "password", row }); }}
              onAssignTrainer={handleAssignTrainer}
              onUpdateMaxHorses={handleUpdateMaxHorses}
              onShowTeam={(row, focus) => setTeam({ row, focus })}
            />
          ))}
        </>
      )}
    {team && (
      <TrainerTeamDialog key={team.row.userId} row={team.row} focus={team.focus} onClose={closeTeam} />
    )}
    {dialog.element}
    </HorseShell>
  );
}
