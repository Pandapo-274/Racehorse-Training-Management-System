// src/features/staff/StaffPage.js
// The club manager's Staff screen: every account in the system, grouped by role.
//
// This screen is READ-ONLY. Changing a role or a permission belongs to the
// Permissions screen - and the backend has no write route for it either (see
// StaffController). The button on each row therefore says so rather than
// pretending to act.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  listStaff, matchesQuery, initialsOf, statusTone, statusLabel,
} from "./staffApi";
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

function MemberRow({ row, onOpenPermissions }) {
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

      <button type="button" className="st-action" onClick={() => onOpenPermissions(row)}>
        Permissions
      </button>
    </li>
  );
}

function RoleGroup({ group, query, onOpenPermissions }) {
  const shown = group.members.filter((m) => matchesQuery(m, query));

  /* "Empty because of the filter" and "empty because nobody holds this role"
     are different facts, and what the reader should do next differs too:
     clear the search, or hire someone.

     The role label is deliberately NOT spliced into the sentence. Labels come
     from ROLE.description, which the database stores in the club's own
     language, so dropping one into the middle of an English sentence produces
     something that is neither grammatical nor easy to read. The card heading
     already says which group this is. */
  const emptyText = group.members.length === 0
    ? "No accounts in this role yet"
    : "No one here matches your search";

  return (
    <Card
      className="st-group"
      title={group.roleLabel}
      hint={`${group.members.length} ${group.members.length === 1 ? "account" : "accounts"}`}
    >
      {shown.length === 0 ? (
        <p className="hz-empty">{emptyText}</p>
      ) : (
        <ul className="st-list">
          {shown.map((row) => (
            <MemberRow key={row.userId} row={row} onOpenPermissions={onOpenPermissions} />
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

  /* The Permissions screen does not exist yet (roleNav lists that item with no
     path). Say so plainly instead of letting the button do nothing - a silent
     button makes people think they clicked wrong. */
  const openPermissions = () => {
    window.alert("The Permissions screen is not built yet. Role changes will live there.");
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
              onOpenPermissions={openPermissions}
            />
          ))}
        </>
      )}
    </HorseShell>
  );
}
