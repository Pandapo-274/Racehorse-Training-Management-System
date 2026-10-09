// src/features/horse/horseRequestApi.js
// Yêu cầu đăng ký ngựa - đường để chủ ngựa nói với học viện rằng mình có ngựa
// muốn gửi.
//
// Vì sao cần: POST /api/horses chỉ nhận HEAD_TRAINER và CLUB_MANAGER, nên chủ
// ngựa bấm "thêm ngựa" sẽ nhận 403. Yêu cầu không phải là cửa sau vòng qua
// luật đó - nó chỉ thành hồ sơ ngựa khi học viện bấm duyệt, và lúc duyệt backend
// vẫn gọi đúng HorseService.create với đầy đủ kiểm tra.

import { authFetch } from "../auth/authService";

const API = "/api/horse-requests";

/** Chủ ngựa nhận yêu cầu của mình; trainer và manager nhận cả hàng chờ. */
export function listRequests(signal) {
  return authFetch(API, { signal });
}

/** Chỉ HORSE_OWNER gọi được. Chỉ horseName là bắt buộc. */
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
    }),
  });
}

export function approveRequest(id, { registrationCode, stallNo, note }) {
  return authFetch(`${API}/${id}/approve`, {
    method: "PUT",
    body: JSON.stringify({
      registrationCode: registrationCode.trim(),
      stallNo: stallNo?.trim() || null,
      note: note?.trim() || null,
    }),
  });
}

export function rejectRequest(id, note) {
  return authFetch(`${API}/${id}/reject`, {
    method: "PUT",
    body: JSON.stringify({ note: note.trim() }),
  });
}

/* ------------------------------------------------------------------ *
 * Hiển thị
 * ------------------------------------------------------------------ */

const STATUS = {
  PENDING:  { label: "Waiting for the academy", tone: "warning" },
  APPROVED: { label: "Approved", tone: "good" },
  REJECTED: { label: "Turned down", tone: "critical" },
};

export function toRequestStatus(status) {
  return STATUS[status] || { label: status || "Unknown", tone: "neutral" };
}

/** Yêu cầu đang chờ, để màn hình biết có nên mời gửi thêm hay không. */
export function pendingOnly(rows) {
  return (rows || []).filter((r) => r.status === "PENDING");
}
