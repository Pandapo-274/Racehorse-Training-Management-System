/*
 * Module API giáo án huấn luyện (UC9) cho Head Trainer: /api/training-plans (TrainingPlanController,
 * TrainingPlanService). Dùng chủ yếu ở TrainingPlanPage.js và SchedulePage.js.
 * Vòng đời: DRAFT -> PENDING_APPROVAL (chủ ngựa duyệt) -> APPROVED -> ACTIVE <-> PAUSED -> COMPLETED.
 */
import { authFetch } from "../auth/authService";

const API = "/api/training-plans";

// Nhận hai kiểu tham số để tương thích code cũ: listTrainingPlans(signal) hoặc
// listTrainingPlans({ horseId, status, signal }). instanceof AbortSignal phân biệt hai kiểu.
export function listTrainingPlans(opts = {}) {
  const isSignal = typeof AbortSignal !== "undefined" && opts instanceof AbortSignal;
  const signal = isSignal ? opts : opts?.signal;
  const horseId = isSignal ? undefined : opts?.horseId;
  const status = isSignal ? undefined : opts?.status;
  const params = new URLSearchParams();
  if (horseId) params.set("horseId", horseId);
  if (status) params.set("status", status);
  const suffix = params.toString();
  return authFetch(`${API}${suffix ? `?${suffix}` : ""}`, { signal });
}

// GET /api/training-plans/grooms: Groom trong đội của HLV kèm số giáo án đang giữ (tối đa 2, V42) và cờ eligible.
// Góp ý hội đồng: danh sách rỗng nghĩa là HLV chưa có Groom, không lấy danh sách khác thay vào.
export async function listPlanGrooms(signal) {
  const res = await authFetch(`${API}/grooms`, { signal });
  return Array.isArray(res) ? res : [];
}

/** Chuẩn cân nặng, chiều cao, trần tốc độ theo giống và tháng tuổi (HorseStandards). */
export function getHorseStandards(signal) {
  return authFetch(`${API}/standards`, { signal });
}

export function getTrainingPlan(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

// POST: tạo giáo án mới ở trạng thái DRAFT; payload gồm horseId, groomId, tên, ngày bắt đầu/kết thúc và
// danh sách giai đoạn (phases) với mục tiêu từng giai đoạn.
export function createTrainingPlan(payload) {
  return authFetch(API, { method: "POST", body: JSON.stringify(payload) });
}

/** Chỉ giáo án DRAFT; backend trả 409 cho trạng thái khác. */
// (Ghi chú trên chưa cập nhật: backend cho xoá DRAFT, PENDING_APPROVAL và APPROVED - TrainingPlanService.DELETABLE.)
export function deleteTrainingPlan(id) {
  return authFetch(`${API}/${id}`, { method: "DELETE" });
}

// PUT /{id}/status: DRAFT -> PENDING_APPROVAL (gửi chủ duyệt), APPROVED -> ACTIVE, ACTIVE <-> PAUSED,
// ACTIVE/PAUSED -> COMPLETED. Chuyển không hợp lệ hoặc vi phạm luật (khoá FULL, ngựa đã rút...) -> 409/400.
export function changeTrainingPlanStatus(id, status) {
  return authFetch(`${API}/${id}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}

// Backend nhận cả PUT lẫn POST cho /{id} (@RequestMapping hai phương thức). Nếu PUT bị chặn (405, ví dụ do
// proxy) thì gửi lại bằng POST. Giáo án đã APPROVED/ACTIVE/PAUSED: bản sửa chờ chủ ngựa duyệt (V47).
export async function updateTrainingPlan(id, payload) {
  try {
    return await authFetch(`${API}/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  } catch (err) {
    if (err.message && (err.message.includes("PUT") || err.message.includes("405") || err.message.toLowerCase().includes("not supported"))) {
      return await authFetch(`${API}/${id}`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    throw err;
  }
}

// Trùng tên với hàm trong ownerTrainingApi.js (cùng endpoint owner-feedback).
export function sendOwnerFeedback(id, note) {
  return authFetch(`${API}/${id}/owner-feedback`, {
    method: "PUT",
    body: JSON.stringify({ note }),
  });
}

// V50: Lấy danh sách 9 Template mặc định + gợi ý & hệ số điều chỉnh theo hồ sơ ngựa.
export function getPlanTemplates(horseId, signal) {
  const q = horseId ? `?horseId=${encodeURIComponent(horseId)}` : "";
  return authFetch(`${API}/templates${q}`, { signal });
}

// V50: Kích hoạt Auto-Scheduling Engine sinh buổi tập + ngày nghỉ từ start_date đến end_date.
export function autoScheduleTrainingPlan(planId) {
  return authFetch(`${API}/${planId}/auto-schedule`, { method: "POST" });
}