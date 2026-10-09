// src/features/profile/profileApi.js
// UC4 - Profile.
//
// Ba endpoint, nhưng chỉ hai cái đi qua authFetch được.
//
// Upload avatar KHÔNG dùng authFetch, và đây là lý do, không phải sở thích:
// authFetch tự đặt "Content-Type: application/json" khi thấy có body. Với
// multipart/form-data thì header này phải do TRÌNH DUYỆT đặt, vì nó còn phải
// kèm theo chuỗi `boundary` ngẫu nhiên ngăn cách các phần của gói tin. Tự đặt
// tay là máy chủ không tách nổi phần nào ra phần nào, và Spring sẽ báo lỗi
// "Required request part 'file' is not present" - một thông báo chẳng gợi ý gì
// về nguyên nhân thật. Nên hàm uploadAvatar ở dưới tự gọi fetch, chỉ gắn
// Authorization và để trống Content-Type.
//
// Lỗi của nó vẫn được gói thành ApiError y như authFetch, để handleAuthError
// trong màn hình xử lý 401/403 được cho cả ba endpoint.

import { ApiError, getToken } from "../auth/authService";
import { authFetch } from "../auth/authService";

const API = "/api/profile";

/* ------------------------------------------------------------------ *
 * Đọc và sửa hồ sơ
 * ------------------------------------------------------------------ */

export function getProfile(signal) {
  return authFetch(API, { signal });
}

/**
 * Chỉ ba trường. Backend cố ý không nhận username, role hay status - đổi vai
 * trò hay khoá tài khoản là việc của Manager, không phải của chính người dùng.
 */
export function updateProfile({ fullName, email, phone }) {
  return authFetch(API, {
    method: "PUT",
    body: JSON.stringify({
      fullName: fullName.trim(),
      email: email.trim(),
      phone: phone.trim() || null,
    }),
  });
}

/* ------------------------------------------------------------------ *
 * Avatar
 * ------------------------------------------------------------------ */

export const MAX_AVATAR_BYTES = 2 * 1024 * 1024; // khớp MAX_AVATAR_BYTES bên ProfileService

/**
 * Nhận dạng ảnh bằng mấy byte đầu file, đúng cách ProfileService.detectExtension
 * đang làm. Chép lại ở đây là có chủ ý.
 *
 * Máy chủ cố tình không tin `file.type` do trình duyệt khai, vì đổi đuôi một
 * file .exe thành .jpg là xong. Nếu ở đây chỉ kiểm `file.type` thì sẽ có trường
 * hợp trình duyệt cho qua còn máy chủ chặn - người dùng chờ upload xong mới
 * nhận lỗi, và không hiểu vì sao "ảnh jpg của tôi" lại bị từ chối. Kiểm cùng
 * một tiêu chuẩn thì hai bên không bao giờ bất đồng.
 */
async function detectImageType(file) {
  const head = new Uint8Array(await file.slice(0, 12).arrayBuffer());

  const is = (offset, ...bytes) =>
    bytes.every((b, i) => head[offset + i] === (typeof b === "string" ? b.charCodeAt(0) : b));

  if (head.length >= 3 && is(0, 0xff, 0xd8, 0xff)) return "JPEG";
  if (head.length >= 8 && is(0, 0x89, "P", "N", "G", 0x0d, 0x0a, 0x1a, 0x0a)) return "PNG";
  if (head.length >= 12 && is(0, "R", "I", "F", "F") && is(8, "W", "E", "B", "P")) return "WebP";
  return null;
}

/**
 * Kiểm trước khi gửi. Trả null nếu hợp lệ, hoặc câu lỗi để hiện ngay.
 * Đây chỉ là để người dùng biết sớm - máy chủ vẫn kiểm lại, và nó mới là chốt.
 */
export async function checkAvatar(file) {
  if (!file) return "Pick an image first";
  if (file.size > MAX_AVATAR_BYTES) {
    const mb = (file.size / 1024 / 1024).toFixed(1);
    return `That image is ${mb} MB. The limit is 2 MB.`;
  }
  const kind = await detectImageType(file);
  if (!kind) return "Only JPEG, PNG or WebP images are allowed";
  return null;
}

/** POST multipart. Tên phần phải đúng là "file" - khớp @RequestParam bên controller. */
export async function uploadAvatar(file) {
  const body = new FormData();
  body.append("file", file);

  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  // Cố ý không đặt Content-Type. Xem ghi chú đầu file.

  const res = await fetch(`${API}/avatar`, { method: "POST", headers, body });

  if (!res.ok) {
    let payload = null;
    try {
      payload = await res.json();
    } catch {
      /* thân rỗng hoặc không phải JSON */
    }
    throw new ApiError(
      res.status,
      payload?.message || `Upload failed (HTTP ${res.status})`,
      payload?.errors || {}
    );
  }

  return res.json();
}

/* ------------------------------------------------------------------ *
 * Hiển thị
 * ------------------------------------------------------------------ */

const ROLE_LABEL = {
  CLUB_MANAGER: "Club Manager",
  HEAD_TRAINER: "Head Trainer",
  VETERINARIAN: "Veterinarian",
  GROOM: "Groom",
  HORSE_OWNER: "Horse Owner",
};

export function toRoleLabel(role) {
  return ROLE_LABEL[role] || role || "—";
}

/** ACTIVE / SUSPENDED - hai giá trị CK_USER_status cho phép. */
export function toAccountStatus(status) {
  return status === "SUSPENDED"
    ? { label: "Suspended", tone: "critical", hint: "Ask the academy manager to restore it" }
    : { label: "Active", tone: "good", hint: "" };
}

export function formatJoined(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString("en-GB", { day: "2-digit", month: "long", year: "numeric" });
}

export function toInitials(fullName) {
  if (!fullName) return "??";
  return fullName.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
}
