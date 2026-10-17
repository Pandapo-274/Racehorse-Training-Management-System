import { authFetch } from "../auth/authService";

const API = "/api/sessions";

export function fetchSessions(signal) {
  return authFetch(`${API}?limit=200`, { signal });
}

export function evaluateSession(sessionId, payload) {
  return authFetch(`${API}/${sessionId}/evaluation`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}
