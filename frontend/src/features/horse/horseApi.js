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

/* Không còn createHorse: ngựa chỉ vào hệ thống qua yêu cầu đăng ký (horseRequestApi),
   Club Manager duyệt rồi backend mới tạo hồ sơ. */

export function updateHorse(id, body) {
  return authFetch(`${API}/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

/* ------------------------------------------------------------------ *
 * Quyền
 * ------------------------------------------------------------------ */

/**
 * Chỉ CHỦ NGỰA được sửa, và chỉ ngựa của mình. Club Manager và Head Trainer
 * chỉ xem. Khớp @RequireRole trên HorseController.update và kiểm quyền sở hữu
 * trong HorseService.updateByOwner (backend mới là nơi quyết định thật).
 */
export function canEditHorse(user, horse) {
  return !!user && !!horse && user.role === "HORSE_OWNER" && horse.ownerId === user.userId;
}

/** Hai role duyệt yêu cầu đăng ký ngựa. */
export function canReviewRequests(user) {
  return !!user && (user.role === "CLUB_MANAGER" || user.role === "HEAD_TRAINER");
}

export function getRequestsPath(user) {
  return user?.role === "HEAD_TRAINER" ? "/trainer/horse-requests" : "/manager/horse-requests";
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
  // Nhãn cũ "Eligible" dễ bị đọc là "đủ điều kiện đua". Thực ra nó chỉ là sức khoẻ.
  ELIGIBLE:   { label: "Fit to train", tone: "good",   hint: "Cleared to train by the vet" },
  MONITOR:    { label: "Monitor",    tone: "warning",  hint: "Needs watching" },
  INJURED:    { label: "Injured",    tone: "critical", hint: "Not fit to train" },
  QUARANTINE: { label: "Quarantine", tone: "serious",  hint: "Isolated" },
};

export function toStatus(status) {
  return STATUS_TEXT[status] || { label: status || "Unknown", tone: "neutral", hint: "" };
}

export const STATUS_OPTIONS = Object.keys(STATUS_TEXT);

/* ------------------------------------------------------------------ *
 * Loại hồ sơ và phân loại theo tuổi
 * ------------------------------------------------------------------ */

/** PHẢI khớp HorsePolicy.java ở backend. Đổi mốc thì đổi cả hai nơi. */
export const AGE_RULES = { foalBelow: 2, racingMax: 10, academyMax: 25 };

export const RECORD_TYPES = {
  ACADEMY:  "Academy horse",
  PEDIGREE: "Pedigree record only",
};

/** Ngựa chỉ là hồ sơ tham chiếu (cha mẹ, tổ tiên): không chuồng, không tập, không khám. */
export function isPedigreeOnly(horse) {
  return horse?.recordType === "PEDIGREE";
}

/**
 * Phân loại hiển thị, suy từ loại hồ sơ + tuổi (không lưu DB nên không bao giờ lỗi thời).
 * Trả về { key, label, tone, hint }.
 */
export function toStage(horse) {
  if (isPedigreeOnly(horse)) {
    return { key: "PEDIGREE", label: "Pedigree only", tone: "neutral",
             hint: "Reference record for parents and ancestors. Not a training horse." };
  }
  const age = toAge(horse?.dateOfBirth);
  if (age == null) return { key: "UNKNOWN", label: "Age unknown", tone: "neutral", hint: "" };
  if (age < AGE_RULES.foalBelow) {
    return { key: "FOAL", label: "Foal", tone: "neutral", hint: "Too young to race" };
  }
  if (age <= AGE_RULES.racingMax) {
    return { key: "RACING", label: "Racing age", tone: "neutral", hint: "In the racing age range" };
  }
  return { key: "RETIRED", label: "Retired age", tone: "neutral", hint: "Past racing age" };
}

export const STAGE_FILTERS = [
  { key: "RACING", label: "Racing" },
  { key: "FOAL", label: "Foal" },
  { key: "RETIRED", label: "Retired" },
  { key: "PEDIGREE", label: "Pedigree" },
];

/**
 * Cột/chip Health có nghĩa với con ngựa này không?
 *
 * Health (HORSE.status) chỉ mô tả sức khoẻ để TẬP. Hai trường hợp nó vô nghĩa:
 *  - Hồ sơ PEDIGREE: không tập, không khám.
 *  - Ngựa nghỉ hưu (quá tuổi đua) mà status vẫn là mặc định ELIGIBLE: "Fit to train"
 *    cho con ngựa 24 tuổi là sai sự thật. Cột status không có giá trị "đã nghỉ"
 *    nên nó chỉ đang mang giá trị mặc định.
 *
 * Ngựa nghỉ hưu mà status là MONITOR / INJURED / QUARANTINE thì VẪN hiện: đó là
 * cảnh báo thật, giấu đi là nguy hiểm.
 */
export function healthApplies(horse) {
  if (isPedigreeOnly(horse)) return false;
  if (toStage(horse).key === "RETIRED" && horse?.status === "ELIGIBLE") return false;
  return true;
}

/** Cùng luật với HorsePolicy.ageProblem: trả thông báo lỗi hoặc null. */
export function ageProblem(recordType, dateOfBirth) {
  if (recordType === "PEDIGREE") return null;
  if (!dateOfBirth) return "Date of birth is required for an academy horse";
  const age = toAge(dateOfBirth);
  if (age != null && age > AGE_RULES.academyMax) {
    return `This horse is ${age} years old, above the academy limit of ${AGE_RULES.academyMax}. Register it as a pedigree record instead.`;
  }
  return null;
}

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