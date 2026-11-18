/*
 * Module API cho trang Session review (SessionEvaluationPage.js): /api/sessions (SessionController).
 * Sau khi buổi tập kết thúc (COMPLETED hoặc STOPPED_EARLY), Head Trainer ghi số liệu thực tế, xếp loại
 * POOR / FAIR / GOOD / EXCELLENT, đánh dấu lượt chạy thử (trial run) và gắn video.
 * Buổi tập hồi phục (sau chấn thương) còn phải gửi cho bác sĩ chấm (submit-to-vet).
 */
import { authFetch } from "../auth/authService";

const API = "/api/sessions";

// Danh sách buổi tập để đánh giá (tối đa 200 buổi gần nhất).
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

/** Buổi chưa hoàn thành hoặc chưa từng chạy: không thể đánh giá. */
export function hasNotRun(status) {
  return status !== "COMPLETED" && status !== "STOPPED_EARLY";
}

export function isReviewable(status) {
  return status === "COMPLETED" || status === "STOPPED_EARLY";
}

// PUT /api/sessions/{id}/evaluation: payload gồm số liệu thực tế (cự ly, vận tốc, nhịp tim...), xếp loại và
// nhận xét. Chủ ngựa thấy nhận xét này trên dashboard (3 nhận xét gần nhất).
export function evaluateSession(sessionId, payload) {
  return authFetch(`${API}/${sessionId}/evaluation`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

// POST /api/sessions/{id}/submit-to-vet: gửi buổi tập hồi phục cho bác sĩ chấm; bác sĩ nhận thông báo
// RECOVERY_SESSION_SUBMITTED và xem ở trang Recovery (VetRecoveryPage.js).
export function submitRecoveryToVet(sessionId) {
  return authFetch(`${API}/${sessionId}/submit-to-vet`, {
    method: "POST",
  });
}
