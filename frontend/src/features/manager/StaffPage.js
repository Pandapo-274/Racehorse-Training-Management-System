// src/features/manager/StaffPage.js
// The club manager's Staff screen: every account in the system, grouped by role.
//
// The manager can add staff accounts (Head Trainer, Vet, Groom), move them
// between those three roles, and suspend / activate any non-manager account.
// Permissions belong to roles (RBAC), so the role picker on each staff row is
// how the manager changes what someone can do. New accounts get a temporary
// password that the person must change at their first sign-in.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import PasswordField from "../password/PasswordField";
import {
  listStaff, matchesQuery, initialsOf, statusTone, statusLabel, groupTitle,
  createStaff, setStaffStatus, changeStaffRole, reassignNotice,
  STAFF_ROLE_OPTIONS, STAFF_ROLE_CODES,
} from "./staffApi";
import "../password/password.css"; // styles for PasswordField (.pw-*)
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
};

function MemberRow({ row, canToggle, canChangeRole, onToggleStatus, onChangeRole, busy }) {
  return (
    <li className="st-row">
      <Avatar row={row} />

      <div className="st-identity">
        <p className="st-name">{row.fullName}</p>
        <p className="st-username">@{row.username}</p>
      </div>

      <div className="st-contact">
        {/* Email and phone are real ways to reach someone, so they are
            selectable and clickable rather than plain text. */}
        <a className="st-email" href={`mailto:${row.email}`}>{row.email}</a>
        <p className="st-phone">{row.phone || "—"}</p>
      </div>

      <div className="st-meta">
        {/* Horse count only means something for owners; for every other role
            it is always 0, so showing it would just be noise. */}
        {row.roleName === "HORSE_OWNER" && (
          <span className="st-horses">
            {row.horseCount} {row.horseCount === 1 ? "horse" : "horses"}
          </span>
        )}
        <StatusChip tone={statusTone(row.status)} label={statusLabel(row.status)} />
      </div>

      <div className="st-actions">
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

function RoleGroup({ group, query, myId, busyId, onToggleStatus, onChangeRole }) {
  const shown = group.members.filter((m) => matchesQuery(m, query));

  /* "Empty because of the filter" and "empty because nobody holds this role"
     are different facts, and what the reader should do next differs too:
     clear the search, or hire someone.

     The role label is deliberately NOT spliced into the sentence: the card
     heading already says which group this is. */
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
              canToggle={row.roleName !== "CLUB_MANAGER" && row.userId !== myId}
              canChangeRole={STAFF_ROLE_CODES.has(row.roleName) && row.userId !== myId}
              onToggleStatus={onToggleStatus}
              onChangeRole={onChangeRole}
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

  // Add-staff form + suspend/activate state.
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

  /* Count across every group, not across what is currently shown: the number
     at the top of the page is about the whole system, while the "matches your
     search" number is about the filter. */
  const everyone = useMemo(
    () => (groups || []).flatMap((g) => g.members),
    [groups],
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
    createStaff(form)
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

  /* Replace the one row in place instead of reloading: reloading blanks the
     whole screen to "Loading staff…" for a change that touches a single row. */
  const toggleStatus = (row) => {
    const next = row.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    if (next === "SUSPENDED" && !window.confirm(`Suspend @${row.username}? They will be signed out and cannot log in.`)) {
      return;
    }
    setBusyId(row.userId);
    setNotice("");
    setStaffStatus(row.userId, next)
      .then(({ member, sessionsToReassign }) => {
        replaceRow(member);
        setNotice(`@${member.username} is now ${next === "ACTIVE" ? "active" : "suspended"}.`
          + reassignNotice(member.fullName, sessionsToReassign));
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setNotice(err.message);
      })
      .finally(() => setBusyId(null));
  };

  const replaceRow = (updated) => setGroups((prev) => (prev || []).map((g) => ({
    ...g,
    members: g.members.map((m) => (m.userId === updated.userId ? updated : m)),
  })));

  /* A role change moves the person to another group, so reload instead of
     patching one row. They are signed out everywhere: their token carried the
     old role. */
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
            {/* Reuses the shared password box (eye toggle + live rules) from UC5. */}
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
              query={query}
              myId={myId}
              busyId={busyId}
              onToggleStatus={toggleStatus}
              onChangeRole={changeRole}
            />
          ))}
        </>
      )}
    </HorseShell>
  );
}