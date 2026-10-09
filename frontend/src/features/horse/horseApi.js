// src/features/horse/horseApi.js
// UC7 (Horse Management) + UC8 (Horse Vitals).
//
// Mọi lời gọi đi qua authFetch của authService nên tự có Bearer token, và lỗi
// HTTP trở thành ApiError có .status cùng .fieldErrors - biểu mẫu đọc thẳng
// fieldErrors để tô đỏ đúng ô, không phải tự đoán.
//
// Toàn bộ phép quy đổi giữa từ vựng cơ sở dữ liệu (ELIGIBLE, COLT, HIGH) và từ
// vựng hiển thị nằm ở đây, không rải trong JSX.

import { authFetch } from "../auth/authService";

const API = "/api/horses";

/* ------------------------------------------------------------------ *
 * Năm endpoint
 * ------------------------------------------------------------------ */

/** GET /api/horses - chủ ngựa chỉ nhận ngựa của mình, các role khác nhận tất. */
export function listHorses(signal) {
  return authFetch(API, { signal });
}

export function getHorse(id, signal) {
  return authFetch(`${API}/${id}`, { signal });
}

/** generations bị backend kẹp trong [1, 5] dù gửi bao nhiêu. */
export function getPedigree(id, generations = 3, signal) {
  return authFetch(`${API}/${id}/pedigree?generations=${generations}`, { signal });
}

export function getVitals(id, signal) {
  return authFetch(`${API}/${id}/vitals`, { signal });
}

export function createHorse(body) {
  return authFetch(API, { method: "POST", body: JSON.stringify(body) });
}

export function updateHorse(id, body) {
  return authFetch(`${API}/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

/* ------------------------------------------------------------------ *
 * Quyền
 * ------------------------------------------------------------------ */

/** Chỉ hai role này được tạo và sửa - khớp @RequireRole trên HorseController. */
const CAN_EDIT = new Set(["HEAD_TRAINER", "CLUB_MANAGER"]);

export function canEditHorses(user) {
  return !!user && CAN_EDIT.has(user.role);
}

/**
 * Xem phả hệ (UC7) và chỉ số sinh tồn (UC8): backend không giới hạn theo role
 * ở hai endpoint này (chỉ kiểm tra quyền xem từng con ngựa), nên mọi role đã
 * đăng nhập đều thấy. Nếu sau này cần siết quyền, sửa tại đây.
 */
export function canViewPedigree(user) {
  return !!user;
}

export function canViewVitals(user) {
  return !!user;
}

export function getHorseBasePath(user) {
  const paths = {
    CLUB_MANAGER: "/manager/horses",
    HEAD_TRAINER: "/trainer/horses",
    VETERINARIAN: "/veterinarian/horses",
    GROOM: "/groom/horses",
    HORSE_OWNER: "/horse-owner/horses",
  };
  return paths[user?.role] || "/login";
}

/* ------------------------------------------------------------------ *
 * Từ vựng hiển thị
 * ------------------------------------------------------------------ */

/**
 * Trạng thái sức khoẻ. `tone` chỉ dùng cho màu nền/chấm, KHÔNG BAO GIỜ là thứ
 * duy nhất mang nghĩa: mỗi chip luôn có nhãn chữ đi kèm. Lý do cụ thể: hai màu
 * vàng và xanh lá trong token.css cách nhau ΔE 4.2 dưới mắt mù màu đỏ-lục, tức
 * gần như trùng nhau. Ai chỉ nhìn màu sẽ đọc sai.
 */
const STATUS_TEXT = {
  ELIGIBLE:   { label: "Eligible",   tone: "good",     hint: "Cleared to train" },
  MONITOR:    { label: "Monitor",    tone: "warning",  hint: "Needs watching" },
  INJURED:    { label: "Injured",    tone: "critical", hint: "Not fit to train" },
  QUARANTINE: { label: "Quarantine", tone: "serious",  hint: "Isolated" },
};

export function toStatus(status) {
  return STATUS_TEXT[status] || { label: status || "Unknown", tone: "neutral", hint: "" };
}

export const STATUS_OPTIONS = Object.keys(STATUS_TEXT);

/** Giới tính. Backend chỉ nhận đúng năm giá trị này (@Pattern trên HorseRequest). */
const GENDER_TEXT = {
  COLT:     "Colt",
  FILLY:    "Filly",
  STALLION: "Stallion",
  MARE:     "Mare",
  GELDING:  "Gelding",
};

export const GENDER_OPTIONS = Object.keys(GENDER_TEXT);

export function toGender(g) {
  return GENDER_TEXT[g] || "—";
}

/**
 * Giới tính hợp lệ cho cha và mẹ. Chép từ SIRE_GENDERS/DAM_GENDERS trong
 * HorseService - để biểu mẫu lọc sẵn danh sách thay vì để người dùng chọn sai
 * rồi mới nhận lỗi 400 từ máy chủ.
 */
export const SIRE_GENDERS = new Set(["COLT", "STALLION"]);
export const DAM_GENDERS = new Set(["FILLY", "MARE"]);

/** Mức nghiêm trọng của cảnh báo và chấn thương. */
const SEVERITY_TONE = {
  HIGH:     "critical",
  CRITICAL: "critical",
  MEDIUM:   "serious",
  MODERATE: "serious",
  LOW:      "warning",
  MINOR:    "warning",
};

export function toSeverity(s) {
  const key = String(s || "").toUpperCase();
  return {
    label: key ? key.charAt(0) + key.slice(1).toLowerCase() : "—",
    tone: SEVERITY_TONE[key] || "neutral",
  };
}

const METRIC_TEXT = {
  HEART_RATE: "Heart rate",
  VELOCITY: "Speed",
  WORKLOAD: "Workload",
};

export function toMetric(m) {
  return METRIC_TEXT[m] || m;
}

/**
 * Hằng số của cơ sở dữ liệu -> câu chữ người đọc được: UNDER_TREATMENT thành
 * "Under treatment", COMPLETED thành "Completed".
 *
 * Dùng cho những cột mà backend chưa chốt danh sách giá trị (trạng thái buổi
 * tập, trạng thái chấn thương). Những cột đã chốt - status, giới tính, mức
 * nghiêm trọng - có bảng tra riêng ở trên, vì ở đó cách viết hoa chưa đủ: ví
 * dụ ELIGIBLE còn phải kèm câu giải thích "Cleared to train".
 */
export function toEnumLabel(v) {
  if (!v) return "—";
  const s = String(v).replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/* ------------------------------------------------------------------ *
 * Định dạng
 * ------------------------------------------------------------------ */

export function formatDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
}

export function formatDateTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleString("en-GB", {
        day: "2-digit", month: "short", year: "numeric",
        hour: "2-digit", minute: "2-digit",
      });
}

/** Ngày sinh -> số tuổi. Ngựa đua tính tuổi theo năm nên lấy tròn năm là đủ. */
export function toAge(dateOfBirth) {
  if (!dateOfBirth) return null;
  const born = new Date(dateOfBirth);
  if (Number.isNaN(born.getTime())) return null;
  const now = new Date();
  let age = now.getFullYear() - born.getFullYear();
  const m = now.getMonth() - born.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < born.getDate())) age -= 1;
  return age;
}

export function formatWeight(w) {
  const n = Number(w);
  return Number.isFinite(n) && n > 0 ? `${n.toFixed(1)} kg` : "—";
}

/** Chữ viết tắt cho ô avatar: "Symboli Rudolf" -> "SR". */
export function toInitials(name) {
  if (!name) return "??";
  return name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
}

/* ------------------------------------------------------------------ *
 * Danh sách chủ ngựa
 * ------------------------------------------------------------------ */

/**
 * Backend chưa có endpoint liệt kê tài khoản, nhưng biểu mẫu cần chọn ownerId,
 * và HorseService từ chối mọi ownerId không phải role HORSE_OWNER.
 *
 * Cách lấp: GET /api/horses đã trả kèm ownerId và ownerName của từng con, nên
 * suy ngược ra được danh sách chủ ngựa *đang có ít nhất một con*. Đủ cho việc
 * thường gặp là gán con mới cho một chủ đã có. Chủ chưa có con nào thì không
 * suy ra được - biểu mẫu vì vậy vẫn cho gõ thẳng số ID.
 *
 * Khi nào backend có GET /api/users?role=HORSE_OWNER thì thay hàm này, phần
 * còn lại của biểu mẫu không phải sửa.
 */
export function ownersFromHorses(horses) {
  const byId = new Map();
  (horses || []).forEach((h) => {
    if (h.ownerId != null && !byId.has(h.ownerId)) {
      byId.set(h.ownerId, { ownerId: h.ownerId, ownerName: h.ownerName || `User #${h.ownerId}` });
    }
  });
  return [...byId.values()].sort((a, b) => a.ownerName.localeCompare(b.ownerName));
}