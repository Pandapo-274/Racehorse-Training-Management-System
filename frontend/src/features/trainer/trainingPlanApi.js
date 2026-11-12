import { authFetch } from "../auth/authService";

const API = "/api/training-plans";

export function listTrainingPlans({ horseId, status, signal } = {}) {
  const params = new URLSearchParams();
  if (horseId) params.set("horseId", horseId);
  if (status) params.set("status", status);
  const suffix = params.toString();
  return authFetch(`${API}${suffix ? `?${suffix}` : ""}`, { signal });
}

export async function listPlanGrooms(signal) {
  try {
    const res = await authFetch(`${API}/grooms`, { signal });
    if (Array.isArray(res) && res.length > 0) return res;
    // Fallback: Nếu /grooms trả về mảng rỗng, thử gọi /api/training-sessions/assignees
    const assignees = await authFetch("/api/training-sessions/assignees", { signal }).catch(() => []);
    if (Array.isArray(assignees) && assignees.length > 0) {
      return assignees.map((g) => ({
        userId: g.userId,
        fullName: g.fullName,
        activePlanCount: 0,
        eligible: true,
      }));
    }
    return Array.isArray(res) ? res : [];
  } catch (err) {
    try {
      const fallback = await authFetch("/api/training-sessions/assignees", { signal });
      if (Array.isArray(fallback) && fallback.length > 0) {
        return fallback.map((g) => ({
          userId: g.userId,
          fullName: g.fullName,
          activePlanCount: 0,
          eligible: true,
        }));
      }
    } catch {
      // ignore
    }
    throw err;
  }
}

export function getTrainingPlan(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

export function createTrainingPlan(payload) {
  return authFetch(API, { method: "POST", body: JSON.stringify(payload) });
}

/** Chỉ giáo án DRAFT; backend trả 409 cho trạng thái khác. */
export function deleteTrainingPlan(id) {
  return authFetch(`${API}/${id}`, { method: "DELETE" });
}

export function changeTrainingPlanStatus(id, status) {
  return authFetch(`${API}/${id}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}

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

export function sendOwnerFeedback(id, note) {
  return authFetch(`${API}/${id}/owner-feedback`, {
    method: "PUT",
    body: JSON.stringify({ note }),
  });
}
