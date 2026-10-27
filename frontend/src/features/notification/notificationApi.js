// src/features/notification/notificationApi.js
// Chuông thông báo trên header của cả năm vai trò.
//
// Backend chỉ lưu thông báo trỏ tới cái gì (targetEntity + targetId), không
// lưu đường dẫn: cùng một con ngựa thì mỗi vai trò mở ở một trang khác nhau.
// Việc đổi ra đường dẫn nằm ở notificationTarget() bên dưới.

import { authFetch } from "../auth/authService";
import { getHorseBasePath } from "../horse/horseApi";

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

export function markRead(id) {
  return authFetch(`${API}/${id}/read`, { method: "PUT" });
}

export function markAllRead() {
  return authFetch(`${API}/read-all`, { method: "PUT" });
}

/* ------------------------------------------------------------------ *
 * Thông báo -> trang cần mở
 * ------------------------------------------------------------------ */

/**
 * Đường dẫn của trang mà thông báo này nói tới, theo vai trò người đang xem.
 * Trả null nếu không có trang nào phù hợp - khi đó bấm vào chỉ đánh dấu đã đọc.
 */
export function notificationTarget(n, user) {
  const role = user?.role;
  /* Thông báo nhắc lịch phòng ngừa là tin tóm tắt cho cả đàn nên không trỏ tới
     một entity nào (targetEntity null); nhận ra nó bằng loại thông báo. */
  if (n?.type === "PREVENTIVE_CARE_DUE") {
    return role === "VETERINARIAN" ? "/veterinarian/preventive-care" : null;
  }
  switch (n?.targetEntity) {
    case "HORSE":
      return `${getHorseBasePath(user)}/${n.targetId}`;
    case "HORSE_REQUEST":
      if (role === "CLUB_MANAGER") return "/manager/horse-requests";
      if (role === "HORSE_OWNER") return "/horse-owner/request";
      return null;
    case "TRAINING_SESSION":
      if (role === "GROOM") return "/groom";
      if (role === "HEAD_TRAINER") return "/trainer/schedule";
      return null;
    case "HORSE_WITHDRAWAL":
      if (role === "CLUB_MANAGER") return "/manager/withdrawals";
      return null;
    case "THRESHOLD_ALERT":
      if (role === "HEAD_TRAINER") return "/trainer/live-session";
      if (role === "VETERINARIAN") return "/veterinarian";
      return null;
    default:
      return null;
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
  if (!iso) return null;
  const hasZone = /(?:[zZ]|[+-]\d{2}:?\d{2})$/.test(iso);
  const d = new Date(hasZone ? iso : `${iso}Z`);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "Just now", "5 min ago", "3 h ago", "2 d ago", rồi tới ngày cụ thể. */
export function timeAgo(iso, now = Date.now()) {
  const d = parseUtc(iso);
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
