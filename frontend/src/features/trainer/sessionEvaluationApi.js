import { authFetch } from "../auth/authService";

const API = "/api/sessions";

export function fetchSessions(signal) {
  return authFetch(`${API}?limit=200`, { signal });
}

/**
 * PUT /api/sessions/{id}/trial-run: đánh dấu lượt chạy thử và gắn link video.
 * trialRun=false thì video (nếu có) bị gỡ theo. Chủ ngựa được báo khi có video mới.
 */
export function updateTrialRun(sessionId, { trialRun, videoUrl }) {
  return authFetch(`${API}/${sessionId}/trial-run`, {
    method: "PUT",
    body: JSON.stringify({ trialRun, videoUrl: videoUrl || null }),
  });
}

/** Buổi chưa từng chạy: không có gì để đánh giá hay quay video. */
export function hasNotRun(status) {
  return status === "SCHEDULED" || status === "CANCELLED";
}

export function evaluateSession(sessionId, payload) {
  return authFetch(`${API}/${sessionId}/evaluation`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}
