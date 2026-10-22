// src/features/manager/staffApi.js
// Calls GET /api/staff.
//
// The backend returns data ALREADY GROUPED:
//   [{ roleName, roleLabel, members: [...] }]
//
// This file used to do the grouping itself, and that broke in two places: an
// empty group had no member to read its label from (the screen showed the raw
// code "VETERINARIAN"), and the role order was declared twice, once in SQL and
// once here. Both the label and the order now come from the ROLE table, which
// is the only place that knows them.
import { authFetch } from "../auth/authService";
import { toRoleLabel } from "../profile/profileApi";

export function listStaff() {
  return authFetch("/api/staff");
}

/**
 * Heading of a role group. ROLE.description is stored in the club's own
 * language (Vietnamese), so the screen shows the English label from the same
 * table the profile page uses, keyed by the role code. A role the table does
 * not know yet falls back to the text the backend sent, so a new role still
 * shows something readable instead of a bare code.
 */
export function groupTitle(group) {
  const label = toRoleLabel(group.roleName);
  return label === group.roleName ? group.roleLabel || group.roleName : label;
}

/** Match against name, username, email or phone. */
export function matchesQuery(row, query) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [row.fullName, row.username, row.email, row.phone]
    .some((v) => (v || "").toLowerCase().includes(q));
}

/** Initials for the avatar circle when an account has no picture. */
export function initialsOf(fullName) {
  const parts = (fullName || "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  // Vietnamese names put the family name first, so the LAST two words are the
  // given name - that is what people are called by.
  const last = parts[parts.length - 1][0];
  const first = parts.length > 1 ? parts[parts.length - 2][0] : "";
  return (first + last).toUpperCase();
}

export function statusTone(status) {
  return status === "ACTIVE" ? "good" : "warning";
}

export function statusLabel(status) {
  return status === "ACTIVE" ? "Active" : "Suspended";
}
