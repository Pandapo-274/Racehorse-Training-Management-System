// src/features/manager/permissionsApi.js
// Calls GET /api/permissions (CLUB_MANAGER only).
//
// The backend builds the matrix from the @RequireRole rules the system really
// enforces, so this screen never drifts from what is actually allowed.
import { authFetch } from "../auth/authService";

export function getPermissions(signal) {
  return authFetch("/api/permissions", { signal });
}

/** Cell text and tone for one role on one function. */
const ACCESS = {
  ALL: { label: "✓", tone: "all", title: "Allowed" },
  OWN: { label: "Own", tone: "own", title: "Allowed only on their own data" },
};

export function toAccess(level) {
  return ACCESS[level] || { label: "—", tone: "none", title: "Not allowed" };
}

/** How an API endpoint is protected. */
const ENDPOINT_ACCESS = {
  PUBLIC: "Public (no sign-in)",
  SIGNED_IN: "Any signed-in user",
};

export function toEndpointAccess(row, roleLabel) {
  if (ENDPOINT_ACCESS[row.access]) return ENDPOINT_ACCESS[row.access];
  return row.roles.map(roleLabel).join(", ");
}

/** Keep the backend's order of areas, group functions under each. */
export function groupByArea(functions) {
  const groups = [];
  const index = new Map();
  for (const f of functions || []) {
    if (!index.has(f.area)) {
      index.set(f.area, groups.length);
      groups.push({ area: f.area, rows: [] });
    }
    groups[index.get(f.area)].rows.push(f);
  }
  return groups;
}
