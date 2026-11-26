// src/features/notification/notificationApi.js
// Chuông thông báo trên header của cả năm vai trò.
//
// Backend chỉ lưu thông báo trỏ tới cái gì (targetEntity + targetId), không
// lưu đường dẫn: cùng một con ngựa thì mỗi vai trò mở ở một trang khác nhau.
// Việc đổi ra đường dẫn nằm ở notificationTarget() bên dưới.

import { authFetch } from "../auth/authService";
import { getHorseBasePath, parseServerTime } from "../horse/horseApi";

// Tiền tố chung của mọi endpoint thông báo (NotificationController). Mỗi thông báo backend trả về có dạng
// NotificationRow: { notificationId, type, title, message, targetEntity, targetId, createdAt, readAt, read }.
// Dùng ở NotificationBell.js (chuông trên header của 5 dashboard).
const API = "/api/notifications";

/* ------------------------------------------------------------------ *
 * Endpoint
 * ------------------------------------------------------------------ */

/** Thông báo mới nhất trước. Backend kẹp limit trong [1, 100]. */
export function listNotifications(limit = 20, signal) {
  return authFetch(`${API}?limit=${limit}`, { signal });
}

/** Trả về một con số. */
export async function getUnreadCount(signal) {
  const body = await authFetch(`${API}/unread-count`, { signal });
  return body?.count ?? 0;
}

// PUT /api/notifications/{id}/read: đánh dấu một thông báo đã đọc (câu UPDATE chỉ khớp thông báo của chính
// người đang đăng nhập, nên không thể đánh dấu hộ người khác).
export function markRead(id) {
  return authFetch(`${API}/${id}/read`, { method: "PUT" });
}

// PUT /api/notifications/read-all: đánh dấu đã đọc mọi thông báo của người đang đăng nhập.
export function markAllRead() {
  return authFetch(`${API}/read-all`, { method: "PUT" });
}

/* ------------------------------------------------------------------ *
 * Thông báo -> trang cần mở
 * ------------------------------------------------------------------ */

/**
 * Thông báo không trỏ tới một entity (targetEntity null) - tin tóm tắt, tin về
 * kho, về nhân sự - thì chọn trang theo LOẠI thông báo và vai trò người xem.
 * Trước đây những tin này trả null, nên bấm vào chỉ đánh dấu đã đọc, không mở
 * trang nào. Tên loại là tham số đầu của new Notice(...) ở backend.
 */
const PAGE_BY_TYPE = {
  // V57: khoản tính riêng đã được trả online.
  COST_PAYMENT_PAID: { HORSE_OWNER: "/horse-owner/payments", CLUB_MANAGER: "/manager/expenses" },
  // Flow 4: giao việc chăm sóc (giao hàng loạt không gắn ngựa) và việc bỏ lỡ.
  DAILY_TASK_ASSIGNED: { GROOM: "/groom/stable" },
  DAILY_TASK_REASSIGNED: { GROOM: "/groom/stable" },
  DAILY_TASK_MISSED: { HEAD_TRAINER: "/trainer/stable-care?tab=missed" },
  // Flow 4: kho (thiếu, hết, nhập hàng, tổng hợp 07:05) - gửi cho Club Manager.
  SUPPLY_LOW: { CLUB_MANAGER: "/manager/supplies", VETERINARIAN: "/veterinarian/supplies" },
  SUPPLY_SHORTFALL: { CLUB_MANAGER: "/manager/supplies", VETERINARIAN: "/veterinarian/supplies" },
  SUPPLY_RESTOCKED: { CLUB_MANAGER: "/manager/supplies", VETERINARIAN: "/veterinarian/supplies" },
  SUPPLY_DIGEST: { CLUB_MANAGER: "/manager/supplies", VETERINARIAN: "/veterinarian/supplies" },
  SUPPLY_REPORTED: { CLUB_MANAGER: "/manager/supplies", VETERINARIAN: "/veterinarian/supplies" },
  SUPPLY_COUNT_UP: { CLUB_MANAGER: "/manager/supplies" },
  // Flow 4: khẩu phần đổi - mở trang khẩu phần của từng vai trò (thông báo có gắn ngựa).
  RATION_CHANGED: {
    GROOM: "/groom/feeding",
    HEAD_TRAINER: "/trainer/stable-care?tab=rations",
    VETERINARIAN: "/veterinarian/feeding",
  },
  // Nhân sự: đổi HLV, chuyển việc khi một người bị khoá, ca y tế trả về hàng chờ.
  TRAINER_ASSIGNED: { GROOM: "/groom" },
  TRAINER_LEFT: { GROOM: "/groom" },
  TRAINING_SESSION_UNASSIGNED: { GROOM: "/groom" },
  GROOM_ASSIGNED: { HEAD_TRAINER: "/trainer/schedule" },
  SESSIONS_NEED_GROOM: { HEAD_TRAINER: "/trainer/schedule" },
  OWNER_SUSPENDED: { HEAD_TRAINER: "/trainer/horses" },
  WORK_HANDED_OVER: { GROOM: "/groom", HEAD_TRAINER: "/trainer/schedule", VETERINARIAN: "/veterinarian" },
  CASES_BACK_IN_QUEUE: { VETERINARIAN: "/veterinarian" },
  HORSE_TRANSFERRED: { HORSE_OWNER: "/horse-owner/horses" },
  STAFF_ROLE_CHANGED: "/profile",
  TEMP_PASSWORD_SET: "/change-password",
};

// Tra bảng PAGE_BY_TYPE: giá trị là chuỗi (mọi vai trò mở cùng một trang) hoặc object { VAI_TRÒ: trang }.
// Vai trò không có trong object -> null (bấm chỉ đánh dấu đã đọc).
function pageByType(n, role) {
  const entry = PAGE_BY_TYPE[n?.type];
  if (!entry) return null;
  return typeof entry === "string" ? entry : entry[role] || null;
}

/**
 * Đường dẫn của trang mà thông báo này nói tới, theo vai trò người đang xem.
 * Trả null nếu không có trang nào phù hợp - khi đó bấm vào chỉ đánh dấu đã đọc.
 */
export function notificationTarget(n, user) {
  const role = user?.role;
  // Thứ tự các nhánh dưới đây quan trọng: luật theo LOẠI thông báo (type) được xét trước, rồi mới tới
  // luật theo entity (switch ở cuối). Nhánh nào return trước thì thắng.
  /* Việc chăm sóc: Groom cần chỗ để làm việc (Stable map), không phải hồ sơ
     ngựa, kể cả khi thông báo có gắn con ngựa. */
  if (role === "GROOM" && (n?.type === "DAILY_TASK_ASSIGNED" || n?.type === "DAILY_TASK_REASSIGNED")) {
    return "/groom/stable";
  }
  /* Khẩu phần đổi: mở bảng khẩu phần (ai cũng cần xem khẩu phần mới), không phải hồ sơ ngựa. */
  if (n?.type === "RATION_CHANGED" && pageByType(n, role)) {
    return pageByType(n, role);
  }
  /* Thông báo nhắc lịch phòng ngừa là tin tóm tắt cho cả đàn nên không trỏ tới
     một entity nào (targetEntity null); nhận ra nó bằng loại thông báo. */
  // (Ghi chú ngay trên thực ra nói về PREVENTIVE_CARE_DUE ở dưới; khối if này xử lý thông báo giải đua.)
  if (n?.type === "RACE_REGISTERED" || n?.type === "RACE_APPROVED" || n?.type === "RACE_REJECTED"
      || n?.type === "RACE_TO_SUBMIT" || n?.type === "RACE_SUBMITTED") {
    if (role === "HORSE_OWNER") return "/horse-owner/races";
    if (role === "HEAD_TRAINER") return "/trainer/races";
    if (role === "CLUB_MANAGER") return "/manager/races";
  }
  if (n?.type === "PREVENTIVE_CARE_DUE") {
    return role === "VETERINARIAN" ? "/veterinarian/preventive-care" : null;
  }
  if (n?.type === "RECOVERY_SESSION_SUBMITTED") {
    if (role === "VETERINARIAN") return `/veterinarian/recovery/${n.targetId}`;
    if (role === "HEAD_TRAINER") return "/trainer/session-review";
  }
  // Head Trainer: thông báo về khoá tập, sức khoẻ, buổi tập đều mở Lịch tập (/trainer/schedule) - nơi HLV
  // xử lý hệ quả (đổi lịch, gán Groom...), kể cả khi thông báo trỏ tới con ngựa.
  if (role === "HEAD_TRAINER") {
    if (
      n?.type === "TRAINING_LOCK_ISSUED" ||
      n?.type === "TRAINING_LOCK_RELEASED" ||
      n?.type === "TRAINING_LOCK_LEVEL_UPDATED" ||
      n?.type === "HORSE_CLEARED_TO_TRAIN" ||
      n?.type === "HEALTH_STATUS_CHANGED" ||
      n?.type === "TRAINING_SESSION_ASSIGNED" ||
      n?.type === "TRAINING_SESSION_FINISHED" ||
      n?.type === "TRAINING_SESSION_AUTO_FINISHED"
    ) {
      return "/trainer/schedule";
    }
  }
  // Còn lại: chọn trang theo loại entity thông báo trỏ tới; không khớp entity nào (targetEntity null hoặc
  // loại khác) thì thử bảng PAGE_BY_TYPE theo loại thông báo.
  switch (n?.targetEntity) {
    case "HORSE":
      // Thông báo về lịch (khoá tập, sức khoẻ, buổi tập) đã được đưa tới Schedule ở nhánh theo loại phía trên.
      // Còn lại, kể cả "New horse assigned to your management", mở hồ sơ con ngựa: HLV cần biết ngựa trước khi lập kế hoạch.
      return `${getHorseBasePath(user)}/${n.targetId}`;
    case "TRAINING_LOCK":
      if (role === "HEAD_TRAINER") return "/trainer/schedule";
      return null;
    case "HORSE_REQUEST":
      // V49: contract steps travel on the request; the contract pages are where they are acted on.
      if (n?.type?.startsWith("CONTRACT_")) {
        if (role === "CLUB_MANAGER") return "/manager/contracts";
        if (role === "HORSE_OWNER") return "/horse-owner/contracts";
      }
      if (role === "HORSE_OWNER" && n?.type === "HORSE_REQUEST_APPROVED") return "/horse-owner/contracts";
      if (role === "CLUB_MANAGER") return "/manager/horse-requests";
      if (role === "HORSE_OWNER") return "/horse-owner/request";
      return null;
    case "TRAINING_SESSION":
      if (role === "GROOM") return "/groom";
      if (role === "HEAD_TRAINER") return "/trainer/schedule";
      if (role === "HORSE_OWNER") return `/horse-owner/training?sessionId=${n.targetId}`;
      return null;
    case "HORSE_WITHDRAWAL":
      if (role === "CLUB_MANAGER") return "/manager/withdrawals";
      return null;
    case "THRESHOLD_ALERT":
      if (role === "HEAD_TRAINER") return "/trainer/live-session";
      if (role === "VETERINARIAN") return "/veterinarian/vet-calls";
      return null;
    case "VET_CALL":
      if (role === "VETERINARIAN") return `/veterinarian/vet-calls/${n.targetId}`;
      if (role === "HEAD_TRAINER") return "/trainer/live-session#vet-calls";
      return null;
    case "TRAINING_PLAN":
      // Giáo án: chủ ngựa duyệt/xem ở trang Training, Groom xem dashboard, Head Trainer xem ở trang giáo án.
      if (role === "HORSE_OWNER") return `/horse-owner/training?planId=${n.targetId}`;
      if (role === "GROOM") return "/groom";
      if (role === "HEAD_TRAINER") return "/trainer/training-plans";
      return null;
    default:
      return pageByType(n, role);
  }
}

/* ------------------------------------------------------------------ *
 * Định dạng
 * ------------------------------------------------------------------ */

/**
 * Backend ghi giờ bằng SYSUTCDATETIME() và trả về dạng "2026-10-22T05:10:00",
 * không kèm múi giờ. new Date() đọc chuỗi đó như giờ địa phương, nên ở Việt
 * Nam mọi thông báo sẽ trông như cũ hơn 7 tiếng. Thêm "Z" để đọc đúng là UTC.
 */
function parseUtc(iso) {
  // Uỷ quyền cho parseServerTime trong horseApi.js - chỉ một chỗ duy nhất xử lý giờ UTC không kèm múi giờ.
  return parseServerTime(iso);
}

/** "Just now", "5 min ago", "3 h ago", "2 d ago", rồi tới ngày cụ thể. */
export function timeAgo(iso, now = Date.now()) {
  const d = parseUtc(iso);
  // now truyền vào được (để test với một thời điểm cố định); mặc định là thời điểm hiện tại tính bằng ms.
  if (!d) return "";
  const minutes = Math.floor((now - d.getTime()) / 60000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} d ago`;
  return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
}