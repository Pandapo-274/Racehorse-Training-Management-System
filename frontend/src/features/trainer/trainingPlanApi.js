import { authFetch } from "../auth/authService";

const API = "/api/training-plans";

export function listTrainingPlans({ horseId, status, signal } = {}) {
  const params = new URLSearchParams();
  if (horseId) params.set("horseId", horseId);
  if (status) params.set("status", status);
  const suffix = params.toString();
  return authFetch(`${API}${suffix ? `?${suffix}` : ""}`, { signal });
}

export function getTrainingPlan(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

export function createTrainingPlan(payload) {
  return authFetch(API, { method: "POST", body: JSON.stringify(payload) });
}

export function changeTrainingPlanStatus(id, status) {
  return authFetch(`${API}/${id}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}
