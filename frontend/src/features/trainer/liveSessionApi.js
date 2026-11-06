import { authFetch } from "../auth/authService";

const ALERTS_API = "/api/alerts";
const SESSIONS_API = "/api/training-sessions";

export function fetchAlerts({ afterId, openOnly = true, limit = 50, signal } = {}) {
  const params = new URLSearchParams();
  if (afterId !== undefined && afterId !== null) params.set("afterId", String(afterId));
  params.set("openOnly", String(openOnly));
  params.set("limit", String(limit));
  return authFetch(`${ALERTS_API}?${params.toString()}`, { signal });
}

export function fetchRunningSessions(signal) {
  return authFetch(`${SESSIONS_API}?status=RUNNING`, { signal });
}

/** RUNNING -> COMPLETED: buổi kết thúc bình thường, chấm điểm sau ở Session review. */
export function finishSession(sessionId) {
  return authFetch(`${SESSIONS_API}/${sessionId}/finish`, { method: "PUT" });
}

/**
 * Báo cáo sự cố bác sĩ chưa nhận (PENDING). Màn này chỉ hiện những báo cáo
 * sinh ra từ một lần dừng buổi tập (sessionId khác null).
 */
export function fetchPendingIncidents(signal) {
  return authFetch("/api/incident-reports?status=PENDING&limit=20", { signal });
}

/** PUT /api/incident-reports/{id}/dismiss: huỷ báo động giả, bắt buộc có lý do. */
export function dismissIncident(reportId, reason) {
  return authFetch(`/api/incident-reports/${reportId}/dismiss`, {
    method: "PUT",
    body: JSON.stringify({ reason }),
  });
}

export function resolveAlert(alertId, actionTaken) {
  return authFetch(`${ALERTS_API}/${alertId}/resolve`, {
    method: "PUT",
    body: JSON.stringify({ actionTaken }),
  });
}
