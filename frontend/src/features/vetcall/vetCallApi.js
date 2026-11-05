// src/features/vetcall/vetCallApi.js
// Gọi bác sĩ từ cảnh báo buổi tập (V27). Dùng chung cho Head Trainer (gọi, xem trả lời
// trên Live session) và bác sĩ (hàng chờ Vet calls, form trả lời).

import { authFetch } from "../auth/authService";

/** Head Trainer gọi bác sĩ cho một cảnh báo: { reason, observations, urgency, stopSession }. */
export function createVetCall(alertId, body) {
  return authFetch(`/api/alerts/${alertId}/vet-call`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** status: PENDING | ANSWERED | ALL. Ca chờ trả lời luôn đứng đầu. */
export function listVetCalls({ status = "ALL", limit = 30, signal } = {}) {
  return authFetch(`/api/vet-calls?status=${status}&limit=${limit}`, { signal });
}

export function getVetCall(callId, signal) {
  return authFetch(`/api/vet-calls/${callId}`, { signal });
}

/** Bác sĩ trả lời: { decision, instructions, medication }. */
export function answerVetCall(callId, body) {
  return authFetch(`/api/vet-calls/${callId}/answer`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export const VET_DECISIONS = [
  {
    value: "CONTINUE_LIGHT",
    label: "Continue at light intensity",
    hint: "The session keeps running. Tell the trainer how to ease off and what to watch.",
  },
  {
    value: "TREAT_ON_SITE",
    label: "Stop and treat at the stable",
    hint: "The session stops now. Say which medication to give straight away.",
  },
  {
    value: "EMERGENCY_STOP",
    label: "Emergency stop, bring the horse in",
    hint: "The session stops now and the horse joins your examination queue. Training stays blocked until you examine it.",
  },
];

export function decisionLabel(value) {
  return VET_DECISIONS.find((d) => d.value === value)?.label || value || "";
}

export const URGENCIES = [
  { value: "HIGH", label: "High: come now" },
  { value: "MEDIUM", label: "Medium: within the hour" },
  { value: "LOW", label: "Low: advice is enough" },
];

export function urgencyLabel(value) {
  return URGENCIES.find((u) => u.value === value)?.label.split(":")[0] || value || "";
}

/** Gợi ý dấu hiệu để HLV bấm nhanh thay vì gõ. */
export const SIGN_SUGGESTIONS = [
  "Heavy sweating",
  "Laboured breathing",
  "Lameness",
  "Stumbling",
  "Refusing to go on",
  "Heart rate not dropping",
];

export function metricText(call) {
  const unit = call.metricType === "VELOCITY" ? "m/s" : "bpm";
  const name = call.metricType === "VELOCITY" ? "Speed" : "Heart rate";
  return `${name} ${Number(call.actualValue)} ${unit}, limit ${Number(call.thresholdValue)} ${unit}`;
}
