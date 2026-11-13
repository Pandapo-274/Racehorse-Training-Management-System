// src/features/horse/horseRequestApi.js
import { authFetch } from "../auth/authService";

const API = "/api/horse-requests";

export function listRequests(signal) {
  return authFetch(API, { signal });
}

export function submitRequest(form) {
  return authFetch(API, {
    method: "POST",
    body: JSON.stringify({
      horseName: form.horseName.trim(),
      breed: form.breed.trim() || null,
      horseGender: form.horseGender || null,
      dateOfBirth: form.dateOfBirth || null,
      color: form.color.trim() || null,
      note: form.note.trim() || null,
      recordType: form.recordType || "ACADEMY",
      // V41: parents from the horse's papers (optional)
      sireId: form.sireId ? Number(form.sireId) : null,
      damId: form.damId ? Number(form.damId) : null,
    }),
  });
}

export function approveRequest(id, { registrationCode, stallNo, note, trainerId }) {
  return authFetch(`${API}/${id}/approve`, {
    method: "PUT",
    body: JSON.stringify({
      registrationCode: registrationCode.trim(),
      stallNo: stallNo?.trim() || null,
      note: note?.trim() || null,
      trainerId: trainerId ? Number(trainerId) : null,
    }),
  });
}

export function cancelRequest(id) {
  return authFetch(`${API}/${id}/cancel`, { method: "PUT" });
}

export function rejectRequest(id, note) {
  return authFetch(`${API}/${id}/reject`, {
    method: "PUT",
    body: JSON.stringify({ note: note.trim() }),
  });
}

const STATUS = {
  PENDING:  { label: "Waiting for the club", tone: "warning" },
  APPROVED: { label: "Approved", tone: "good" },
  REJECTED: { label: "Turned down", tone: "critical" },
  CANCELLED: { label: "Cancelled by owner", tone: "neutral" },
};

export function toRequestStatus(status) {
  return STATUS[status] || { label: status || "Unknown", tone: "neutral" };
}

export function pendingOnly(rows) {
  return (rows || []).filter((r) => r.status === "PENDING");
}
