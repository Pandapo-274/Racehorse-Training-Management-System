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

export function resolveAlert(alertId, actionTaken) {
  return authFetch(`${ALERTS_API}/${alertId}/resolve`, {
    method: "PUT",
    body: JSON.stringify({ actionTaken }),
  });
}
