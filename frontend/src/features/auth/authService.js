// src/features/auth/authService.js
// Toàn bộ việc nói chuyện với /api/auth/* và quản lý phiên đăng nhập nằm ở đây.
// Các feature khác (trainer, manager, vet, ...) chỉ import authFetch / getUser /
// logout / handleAuthError từ file này, không tự đụng vào localStorage.

import { googleLogout } from "@react-oauth/google";

const TOKEN_KEY = "token";
const USER_KEY = "user"; // giữ tên key cũ để code đang đọc "user" vẫn chạy

export const HOME_BY_ROLE = {
  CLUB_MANAGER: "/manager",
  HEAD_TRAINER: "/trainer",
  VETERINARIAN: "/veterinarian",
  GROOM: "/groom",
  HORSE_OWNER: "/horse-owner",
};

/**
 * Trang đầu tiên sau khi đăng nhập. Tài khoản đang dùng mật khẩu tạm do Club
 * Manager đặt (V14) phải đổi mật khẩu trước, nên đi thẳng tới trang đó.
 */
export function homeFor(user) {
  if (user?.mustChangePassword) return "/change-password";
  return HOME_BY_ROLE[user?.role] || "/access-denied";
}

/* ------------------------------------------------------------------ *
 * Phiên đăng nhập
 * ------------------------------------------------------------------ */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

/** User đang đăng nhập, hoặc null. Không bao giờ throw dù localStorage hỏng. */
export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || "null");
  } catch {
    return null;
  }
}

function saveSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** Đăng xuất phía client: xoá token và user. */
/**
 * Đăng xuất: báo backend thu hồi token (POST /api/auth/logout, ghi LOGOUT vào
 * audit log) rồi xoá phiên trên trình duyệt. Trước đây chỉ xoá localStorage,
 * nên token cũ vẫn gọi được API tới khi hết hạn (8 tiếng).
 *
 * Không chờ backend trả lời: nút Sign out phải phản hồi ngay, và mạng lỗi thì
 * phiên trên trình duyệt vẫn bị xoá. keepalive giữ request sống khi trang
 * chuyển sang /login.
 */
export function logout() {
  const token = getToken();
  if (token) {
    fetch("/api/auth/logout", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      keepalive: true,
    }).catch(() => {});
  }
  clearSession();
}

/** Chỉ xoá phiên trên trình duyệt, không gọi backend. Dùng khi token đã hỏng (401). */
function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  // Để lần sau Google không tự chọn lại tài khoản cũ. Chưa từng mở nút Google
  // thì hàm này không làm gì, nên gọi cho mọi kiểu đăng xuất.
  googleLogout();
}

/**
 * Cập nhật vài trường của user đang lưu, giữ nguyên token. Dùng sau khi UC4
 * sửa hồ sơ thành công.
 *
 * Thêm vào đây chứ không để màn hình tự ghi localStorage: tên hai khoá
 * TOKEN_KEY và USER_KEY là chuyện nội bộ của file này. Nếu nơi khác cũng ghi
 * thẳng "user", thì ngày nào đó đổi tên khoá ở đây sẽ làm chỗ kia hỏng âm thầm
 * - không lỗi, chỉ là sidebar hiện mãi tên cũ và không ai hiểu vì sao.
 *
 * Trả về user sau khi gộp, hoặc null nếu chưa đăng nhập.
 */
export function updateStoredUser(partial) {
  const current = getUser();
  if (!current) return null;
  const merged = { ...current, ...partial };
  localStorage.setItem(USER_KEY, JSON.stringify(merged));
  return merged;
}

/* ------------------------------------------------------------------ *
 * Gọi API
 * ------------------------------------------------------------------ */

/** Lỗi HTTP có sẵn status và (nếu backend trả) lỗi theo từng field. */
export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

// Backend luôn trả lỗi dạng { status, message, errors, timestamp }.
async function toApiError(res) {
  let body = null;
  try {
    body = await res.json();
  } catch {
    /* body rỗng hoặc không phải JSON */
  }
  return new ApiError(
    res.status,
    body?.message || `Request failed (HTTP ${res.status})`,
    body?.errors || {}
  );
}

/**
 * fetch có sẵn header Authorization và Content-Type.
 * Trả JSON đã parse (null nếu 204). Lỗi HTTP -> throw ApiError.
 * `signal` truyền qua options như fetch thường.
 */
export async function authFetch(url, options = {}) {
  const headers = { ...(options.headers || {}) };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body && !headers["Content-Type"]) {
    headers["Content-Type"] = "application/json";
  }

  const res = await fetch(url, { ...options, headers });
  if (!res.ok) throw await toApiError(res);
  return res.status === 204 ? null : res.json();
}

/**
 * Dùng trong .catch() của mọi màn có gọi API:
 *   - 401 (hết phiên / chưa đăng nhập) -> xoá phiên, về /login
 *   - 403 (sai role)                   -> sang /access-denied
 *   - 428 (còn mật khẩu tạm, V14)      -> sang /change-password
 * Trả true nếu đã xử lý (màn không cần hiện lỗi nữa), false nếu là lỗi khác.
 */
export function handleAuthError(err, navigate) {
  if (!(err instanceof ApiError)) return false;
  if (err.status === 401) {
    // Token đã hỏng hoặc bị thu hồi: không cần báo backend nữa.
    clearSession();
    navigate("/login", { replace: true });
    return true;
  }
  if (err.status === 403) {
    navigate("/access-denied", { replace: true });
    return true;
  }
  if (err.status === 428) {
    updateStoredUser({ mustChangePassword: true });
    navigate("/change-password", { replace: true });
    return true;
  }
  return false;
}

/* ------------------------------------------------------------------ *
 * Auth endpoints
 * ------------------------------------------------------------------ */

/**
 * POST /api/auth/login. Thành công: lưu token + user, trả về user
 * ({ userId, username, fullName, email, role }). Sai thông tin: throw ApiError.
 * Không dùng authFetch vì lúc này chưa có token.
 */
export async function login(username, password) {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw await toApiError(res);

  const { token, user } = await res.json();
  saveSession(token, user);
  return user;
}

/**
 * Client ID của Google, đọc từ frontend/.env (REACT_APP_GOOGLE_CLIENT_ID).
 * Rỗng thì ẩn nút Google: chưa cấu hình mà vẫn hiện nút thì bấm vào chỉ ra lỗi
 * khó hiểu từ phía Google. Phải trùng GOOGLE_CLIENT_ID trong backend/.env.
 */
export const GOOGLE_CLIENT_ID = (process.env.REACT_APP_GOOGLE_CLIENT_ID || "").trim();

/**
 * POST /api/auth/google. idToken là `credential` Google vừa trả cho nút đăng
 * nhập; chỉ gửi đi, KHÔNG lưu và KHÔNG log (đó là token của Google, không
 * phải của hệ thống). Thành công: lưu token + user của hệ thống y như login().
 *
 * Trả về { user, outcome }:
 *   LOGIN      - đã từng dùng Google, vào thẳng
 *   REGISTERED - lần đầu, vừa tạo tài khoản mới (mặc định HORSE_OWNER)
 *   LINKED     - email đã có tài khoản thường, vừa gắn Google; mật khẩu cũ bị vô hiệu
 *
 * roleName chỉ có tác dụng ở lần đầu (REGISTERED); không truyền = HORSE_OWNER.
 * Không gọi được backend (tắt máy chủ, mất mạng): throw ApiError status 0 với
 * câu dễ hiểu, thay vì để lọt TypeError "Failed to fetch".
 */
export async function loginWithGoogle(idToken, roleName) {
  let res;
  try {
    res = await fetch("/api/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(roleName ? { idToken, roleName } : { idToken }),
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Please try again in a moment.");
  }
  if (!res.ok) throw await toApiError(res);

  const { token, user, outcome } = await res.json();
  saveSession(token, user);
  return { user, outcome };
}

/** GET /api/auth/me - lấy lại thông tin user theo token hiện có. */
export function fetchMe() {
  return authFetch("/api/auth/me");
}
