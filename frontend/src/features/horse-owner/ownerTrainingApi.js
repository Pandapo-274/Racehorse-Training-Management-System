// src/features/horse-owner/ownerTrainingApi.js
// Trang "Training" của chủ ngựa: giáo án, lịch tập, nhật ký buổi tập, nhận xét
// của Head Trainer và video chạy thử.
//
// Backend tự lọc theo chủ: HORSE_OWNER chỉ nhận giáo án và buổi tập của ngựa
// mình (TrainingPlanService.list, TrainingSessionService.list), và đoán id của
// ngựa người khác thì bị 403.
import { authFetch } from "../auth/authService";

export function listMyPlans(signal) {
  return authFetch("/api/training-plans", { signal });
}

export function getPlan(planId, signal) {
  return authFetch(`/api/training-plans/${planId}`, { signal });
}

/** Mọi buổi tập của ngựa mình, mới nhất trước. Có cả số đo, đánh giá, nhận xét, video. */
export function listMySessions(signal) {
  return authFetch("/api/training-sessions", { signal });
}

/**
 * PUT /api/training-plans/{id}/owner-review: chủ ngựa duyệt hoặc từ chối giáo án
 * đang chờ. Từ chối thì bắt buộc ghi lý do (backend trả fieldErrors.note).
 */
export function reviewPlan(planId, { approve, note }) {
  return authFetch(`/api/training-plans/${planId}/owner-review`, {
    method: "PUT",
    body: JSON.stringify({ approve, note: note || null }),
  });
}

/**
 * PUT /api/training-plans/{id}/owner-feedback: chủ ngựa gửi phản hồi/nhận xét (optional) cho Trainer.
 */
export function sendOwnerFeedback(planId, note) {
  return authFetch(`/api/training-plans/${planId}/owner-feedback`, {
    method: "PUT",
    body: JSON.stringify({ note }),
  });
}

/**
 * Giáo án đáng hiện nhất của một con ngựa: đang chờ chủ duyệt (cần chủ làm gì
 * đó), rồi đang chạy, tạm dừng, rồi mới nhất.
 */
export function pickPlan(plans, horseId) {
  const mine = (plans || []).filter((p) => p.horseId === horseId);
  return mine.find((p) => p.status === "PENDING_APPROVAL")
    || mine.find((p) => p.status === "ACTIVE")
    || mine.find((p) => p.status === "PAUSED")
    || mine[0]
    || null;
}

const PLAN_STATUS = {
  DRAFT: { label: "Draft", tone: "neutral" },
  PENDING_APPROVAL: { label: "Waiting for your approval", tone: "warning" },
  APPROVED: { label: "Approved, not started", tone: "good" },
  ACTIVE: { label: "In progress", tone: "good" },
  PAUSED: { label: "Paused", tone: "warning" },
  COMPLETED: { label: "Completed", tone: "neutral" },
};

export function toPlanStatus(s) {
  return PLAN_STATUS[s] || { label: s || "—", tone: "neutral" };
}

const SESSION_STATUS = {
  SCHEDULED: { label: "Scheduled", tone: "neutral" },
  RUNNING: { label: "In progress", tone: "warning" },
  COMPLETED: { label: "Completed", tone: "good" },
  STOPPED_EARLY: { label: "Stopped early", tone: "serious" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
};

export function toSessionStatus(s) {
  return SESSION_STATUS[s] || { label: s || "—", tone: "neutral" };
}

/** Giai đoạn đang diễn ra hôm nay (theo ngày máy). */
export function isCurrentPhase(phase, today) {
  return phase.startDate <= today && today <= phase.endDate;
}

export function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function formatMetres(m) {
  return m == null ? "—" : `${Number(m).toLocaleString("en-US")} m`;
}
