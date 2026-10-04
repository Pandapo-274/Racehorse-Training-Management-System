// src/features/auth/authService.js
// Toàn bộ việc nói chuyện với /api/auth/* và quản lý phiên đăng nhập nằm ở đây.
// Các feature khác (trainer, manager, vet, ...) chỉ import authFetch / getUser /
// logout / handleAuthError từ file này, không tự đụng vào localStorage.

const TOKEN_KEY = "token";
const USER_KEY = "user"; // giữ tên key cũ để code đang đọc "user" vẫn chạy

export const HOME_BY_ROLE = {
  CLUB_MANAGER: "/manager",
  HEAD_TRAINER: "/trainer",
  VETERINARIAN: "/veterinarian",
  GROOM: "/groom",
  HORSE_OWNER: "/horse-owner",
};

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
export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
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
 * Trả true nếu đã xử lý (màn không cần hiện lỗi nữa), false nếu là lỗi khác.
 */
export function handleAuthError(err, navigate) {
  if (!(err instanceof ApiError)) return false;
  if (err.status === 401) {
    logout();
    navigate("/login", { replace: true });
    return true;
  }
  if (err.status === 403) {
    navigate("/access-denied", { replace: true });
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

/** GET /api/auth/me - lấy lại thông tin user theo token hiện có. */
export function fetchMe() {
  return authFetch("/api/auth/me");
}
