// src/features/manager/PermissionsPage.js
// UC19 (read half) - what each role is allowed to do.
//
// Read-only on purpose. Permissions belong to roles (RBAC) and are enforced by
// the backend; the matrix below is read from those very rules, so it is always
// what the system actually does. To change what a person can do, the manager
// changes their role on the Staff page.
//
// API endpoints are deliberately not shown: the manager works with functions,
// not with API paths. The backend still reads every row from @RequireRole.
import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock } from "../horse/HorseShell";
import { toRoleLabel } from "../profile/profileApi";
import { getPermissions, groupByArea, toAccess } from "./permissionsApi";
import "./permissions.css";

function AccessCell({ level }) {
  const a = toAccess(level);
  return (
    <td className={`pm-cell pm-cell--${a.tone}`} title={a.title}>
      <span aria-label={a.title}>{a.label}</span>
    </td>
  );
}

function AreaTable({ group, roles }) {
  return (
    <Card title={group.area} hint={`${group.rows.length} ${group.rows.length === 1 ? "function" : "functions"}`}>
      <div className="pm-scroll">
        <table className="pm-table">
          <thead>
            <tr>
              <th scope="col" className="pm-fn">Function</th>
              {roles.map((r) => <th scope="col" key={r}>{toRoleLabel(r)}</th>)}
            </tr>
          </thead>
          <tbody>
            {group.rows.map((f) => (
              <tr key={f.endpoint}>
                <th scope="row" className="pm-fn">{f.label}</th>
                {roles.map((r) => <AccessCell key={r} level={f.access[r]} />)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

export default function PermissionsPage() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    getPermissions(ctrl.signal)
      .then(setData)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const groups = useMemo(() => groupByArea(data?.functions), [data]);

  return (
    <HorseShell
      title="Permissions"
      subtitle="What each role can do, read from the rules the system enforces"
    >
      {error && (
        <StateBlock kind="error" title="Could not load permissions" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !data && <StateBlock title="Loading permissions…" />}

      {data && (
        <>
          <Card>
            <div className="pm-intro">
              <p>
                Permissions belong to <strong>roles</strong>, not to individual people. Everyone with
                the same role can do the same things. This page is read-only: to change what someone
                can do, change their role on the <Link to="/manager/staff">Staff</Link> page. They are
                signed out and get the new role's permissions at their next sign-in.
              </p>
              <ul className="pm-legend" aria-label="Legend">
                <li><span className="pm-chip pm-cell--all">✓</span> Allowed</li>
                <li><span className="pm-chip pm-cell--own">Own</span> Only on their own data (their own horses, sessions assigned to them…)</li>
                <li><span className="pm-chip pm-cell--none">—</span> Not allowed</li>
              </ul>
            </div>
          </Card>

          {groups.map((g) => <AreaTable key={g.area} group={g} roles={data.roles} />)}
        </>
      )}
    </HorseShell>
  );
}
