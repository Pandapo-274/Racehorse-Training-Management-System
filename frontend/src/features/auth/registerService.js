// src/features/auth/registerService.js  (UC1)
// Gọi POST /api/auth/register. Dùng authFetch/ApiError có sẵn trong authService,
// không sửa authService. Backend trả lỗi dạng { status, message, errors: {field: msg} }
// và authService đã gói sẵn errors vào ApiError.fieldErrors.
import { authFetch } from "./authService";

/**
 * Form công khai chỉ tạo tài khoản chủ ngựa. Các vai trò còn lại do Quản lý cấp.
 * Lưu ý: đây KHÔNG phải biện pháp bảo vệ, backend phải tự chặn (xem ghi chú bàn giao).
 */
export const SELF_SERVICE_ROLE = "HORSE_OWNER";

/** Thành công: 201 { userId, username, fullName, email, roleName, status }. Lỗi: throw ApiError. */
export function registerAccount(form) {
  return authFetch("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({
      username: form.username.trim().toLowerCase(),
      password: form.password,
      fullName: form.fullName.trim(),
      email: form.email.trim(),
      phone: form.phone.trim() || null,
      roleName: SELF_SERVICE_ROLE, // backend đọc field "roleName"
    }),
  });
}
