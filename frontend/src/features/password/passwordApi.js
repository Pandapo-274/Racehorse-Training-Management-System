// src/features/password/passwordApi.js
// UC5 - Password: đổi mật khẩu, quên mật khẩu, đặt lại mật khẩu.
//
// Ba endpoint nằm chung dưới /api/auth, nhưng khác nhau ở chỗ cần token hay
// không, và điều đó quyết định cách gọi:
//
//   PUT  /api/auth/change-password   cần token   -> authFetch
//   POST /api/auth/forgot-password   công khai   -> fetch thẳng
//   POST /api/auth/reset-password    công khai   -> fetch thẳng
//
// Hai cái sau cố ý không qua authFetch. Người đang quên mật khẩu thì hoặc chưa
// đăng nhập, hoặc token đã hỏng; gắn một Authorization vô nghĩa vào chỉ tổ làm
// máy chủ phải xử lý thêm, và nếu AuthInterceptor khó tính thì còn trả 401 cho
// một việc lẽ ra không cần đăng nhập.

import { ApiError, authFetch } from "../auth/authService";

/* ------------------------------------------------------------------ *
 * Luật mật khẩu - chép từ @Size và @Pattern của ChangePasswordRequest
 * và ResetPasswordRequest (hai record dùng chung một bộ luật).
 * ------------------------------------------------------------------ */

export const MIN_LENGTH = 8;
export const MAX_LENGTH = 72; // giới hạn của BCrypt, không phải con số tuỳ ý

/**
 * Trả về danh sách luật kèm trạng thái đạt/chưa đạt, để màn hình hiện thành
 * một checklist sống thay vì chỉ báo lỗi sau khi bấm nút.
 *
 * Người dùng thấy được mình còn thiếu gì ngay trong lúc gõ, thay vì gõ xong,
 * bấm, bị từ chối, rồi đoán. Cùng một luật, chỉ khác thời điểm nói ra.
 */
export function passwordRules(value) {
  const v = value || "";
  return [
    { id: "len", label: `${MIN_LENGTH} to ${MAX_LENGTH} characters`,
      ok: v.length >= MIN_LENGTH && v.length <= MAX_LENGTH },
    { id: "letter", label: "At least one letter", ok: /[A-Za-z]/.test(v) },
    { id: "digit", label: "At least one digit", ok: /\d/.test(v) },
  ];
}

/** Câu lỗi cho ô mật khẩu mới, hoặc null nếu đạt. */
export function checkNewPassword(value) {
  const failed = passwordRules(value).filter((r) => !r.ok);
  if (failed.length === 0) return null;
  if (!value) return "New password is required";
  // Nêu đúng luật đầu tiên chưa đạt, không liệt kê cả ba: checklist bên cạnh
  // đã hiện đủ rồi, nhắc lại hết ở đây chỉ làm rối.
  return failed[0].id === "len"
    ? `Password must be ${MIN_LENGTH}-${MAX_LENGTH} characters`
    : "Password must contain both letters and digits";
}

/* ------------------------------------------------------------------ *
 * Đổi mật khẩu - cần đăng nhập
 * ------------------------------------------------------------------ */

/**
 * Lưu ý quan trọng: đổi xong, backend huỷ TOÀN BỘ phiên đăng nhập, kể cả
 * token đang dùng để gọi chính lời gọi này. Màn hình vì vậy phải tự đăng xuất
 * ngay sau khi thành công - xem ghi chú trong ChangePasswordPage.
 *
 * Nhập sai mật khẩu hiện tại trả về 400 kèm errors.currentPassword, KHÔNG phải
 * 401. Backend chọn 400 có chủ đích, vì 401 sẽ khiến handleAuthError tưởng hết
 * phiên và đá người dùng ra trang đăng nhập giữa chừng.
 */
export function changePassword({ currentPassword, newPassword }) {
  return authFetch("/api/auth/change-password", {
    method: "PUT",
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

/* ------------------------------------------------------------------ *
 * Quên và đặt lại mật khẩu - không cần đăng nhập
 * ------------------------------------------------------------------ */

async function publicPost(url, payload) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch {
      /* thân rỗng hoặc không phải JSON */
    }
    throw new ApiError(
      res.status,
      body?.message || `Request failed (HTTP ${res.status})`,
      body?.errors || {}
    );
  }

  return res.json();
}

/**
 * Máy chủ LUÔN trả về cùng một câu, dù email có tài khoản hay không:
 *
 *   "If an account with that email exists, a password reset link has been sent."
 *
 * Đó là biện pháp chống dò tài khoản: nếu phản hồi khác nhau, người ngoài chỉ
 * cần thử lần lượt là biết email nào có đăng ký. Màn hình phải hiện đúng câu
 * đó và KHÔNG được thêm thắt kiểu "không tìm thấy email này" - thêm vào là phá
 * đúng cái mà backend đang bảo vệ.
 */
export function forgotPassword(email) {
  return publicPost("/api/auth/forgot-password", { email: email.trim() });
}

export function resetPassword({ token, newPassword }) {
  return publicPost("/api/auth/reset-password", { token, newPassword });
}
