// src/features/horse/contactsApi.js
// V63: insurer and second contact (contract clause 8) are picked from lists, not typed.
import { authFetch } from "../auth/authService";

/** Insurers the club accepts: { insurerId, name, emergencyPhone }. */
export function listInsurers(signal) {
  return authFetch("/api/insurers", { signal });
}

/** An owner's people: { contactId, fullName, phone, relationship }. The manager passes ownerId. */
export function listOwnerContacts(ownerId, signal) {
  const qs = ownerId ? `?ownerId=${encodeURIComponent(ownerId)}` : "";
  return authFetch(`/api/owner-contacts${qs}`, { signal });
}

export function addOwnerContact({ ownerId, fullName, phone, relationship }) {
  return authFetch("/api/owner-contacts", {
    method: "POST",
    body: JSON.stringify({
      ownerId: ownerId || null,
      fullName: fullName.trim(),
      phone: phone.trim(),
      relationship: relationship?.trim() || null,
    }),
  });
}
