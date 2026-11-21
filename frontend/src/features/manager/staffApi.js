// src/features/manager/staffApi.js
/*
 * Module API cho trang quản lý nhân sự của Club Manager (StaffPage.js, TrainerTeamDialog.js).
 * Mọi endpoint /api/staff/... thuộc StaffController (@RequireRole CLUB_MANAGER) và StaffService.
 * Phần cuối file là các hàm hiển thị nhỏ (nhãn, tông màu, tìm kiếm, câu cảnh báo trong hộp xác nhận).
 */
import { authFetch } from "../auth/authService";
import { toRoleLabel } from "../profile/profileApi";

// GET /api/staff: mảng StaffGroup { roleName, roleLabel, members: [StaffRow] } - mỗi nhóm một vai trò,
// thứ tự nhóm do backend quyết định (nhóm rỗng vẫn trả về để màn hình hiện "chưa có ai").
export function listStaff() {
  return authFetch("/api/staff");
}

// Ba vai trò nhân viên. Không có CLUB_MANAGER: giao diện không tạo hay chuyển ai sang Manager.
export const STAFF_ROLE_OPTIONS = [
  { value: "HEAD_TRAINER", label: "Head Trainer" },
  { value: "VETERINARIAN", label: "Veterinarian" },
  { value: "GROOM", label: "Groom" },
];

// POST /api/staff: body { username, password, fullName, email, phone, roleName, trainerId?, maxHorses? }.
// Backend kiểm định dạng (username 3-50 ký tự, mật khẩu 8-72 có chữ và số...) và trả lỗi từng ô (400).
export function createStaff(body) {
  return authFetch("/api/staff", { method: "POST", body: JSON.stringify(body) });
}

/**
 * Roles the manager can CREATE: the three staff roles plus Horse Owner, for an
 * owner who registers at the club desk instead of signing up online. Changing
 * role still stays within STAFF_ROLE_OPTIONS.
 */
export const CREATE_ROLE_OPTIONS = [
  ...STAFF_ROLE_OPTIONS,
  { value: "HORSE_OWNER", label: "Horse Owner" },
];

/** Correct name, email or phone of an account (not a club manager's). */
export function updateStaffContact(userId, { fullName, email, phone }) {
  return authFetch(`/api/staff/${userId}`, {
    method: "PUT",
    body: JSON.stringify({ fullName: fullName.trim(), email: email.trim(), phone: phone?.trim() || null }),
  });
}

/** New temporary password: must be changed at next sign-in; old sessions end; sign-in lock cleared. */
export function setTemporaryPassword(userId, password) {
  return authFetch(`/api/staff/${userId}/password`, {
    method: "PUT",
    body: JSON.stringify({ password }),
  });
}

/** Staff role codes the manager can move people between (same as STAFF_ROLES in StaffService). */
export const STAFF_ROLE_CODES = new Set(STAFF_ROLE_OPTIONS.map((o) => o.value));

/** handoverTo: optional account (same role) that takes over the person's open work when they are suspended. */
export function setStaffStatus(userId, status, handoverTo = null) {
  return authFetch(`/api/staff/${userId}/status`, {
    method: "PUT",
    body: JSON.stringify({ status, handoverTo }),
  });
}

export function changeStaffRole(userId, roleName, handoverTo = null) {
  return authFetch(`/api/staff/${userId}/role`, {
    method: "PUT",
    body: JSON.stringify({ roleName, handoverTo }),
  });
}

/**
 * What a staff member leaves unfinished and who may take it over:
 * { role, items: [text], candidates: [StaffRow], fallback, note }.
 */
export function getOpenWork(userId) {
  return authFetch(`/api/staff/${userId}/open-work`);
}

/** Option text for a successor in the hand-over select. */
export function successorLabel(role, c) {
  if (role === "HEAD_TRAINER") {
    return `${c.fullName} (${c.managedHorseCount}/${c.maxHorses ?? 10} horses, ${c.groomCount}/3 grooms)`;
  }
  return `${c.fullName} (@${c.username})`;
}

/** V37: Club Manager gán hoặc gỡ Groom cho một Head Trainer (tối đa 3 Groom / Head Trainer). */
export function assignStaffTrainer(groomId, trainerId) {
  return authFetch(`/api/staff/${groomId}/trainer`, {
    method: "PUT",
    body: JSON.stringify({ trainerId: trainerId ? Number(trainerId) : null }),
  });
}

/** Grooms and club horses (at the club) of one Head Trainer: { trainer, grooms, horses }. */
export function getTrainerTeam(trainerId) {
  return authFetch(`/api/staff/${trainerId}/team`);
}

/** V37: Club Manager cập nhật hạn mức số ngựa tối đa cho một Head Trainer. */
export function updateTrainerMaxHorses(trainerId, maxHorses) {
  return authFetch(`/api/staff/${trainerId}/max-horses`, {
    method: "PUT",
    body: JSON.stringify({ maxHorses: Number(maxHorses) }),
  });
}

// Câu bổ sung vào thông báo thành công khi một Groom bị khoá hoặc đổi vai trò mà còn buổi tập dang dở
// (count lấy từ phản hồi của backend). count = 0 -> chuỗi rỗng.
export function reassignNotice(fullName, count) {
  if (!count) return "";
  return ` ${fullName} still had ${count} unfinished training ${count === 1 ? "session" : "sessions"}; `
    + "their Head Trainer has been asked to reassign them.";
}

/**
 * What leaving a role (role change or suspension) does to someone's work, said
 * in the confirm dialog BEFORE the manager clicks, not only afterwards.
 */
export function leavingConsequences(row) {
  if (row.roleName === "HEAD_TRAINER") {
    const horses = row.managedHorseCount || 0;
    const grooms = row.groomCount || 0;
    const parts = [];
    if (horses) parts.push(`${horses} ${horses === 1 ? "horse loses its" : "horses lose their"} Head Trainer (you then assign a new one in Club records)`);
    if (grooms) parts.push(`${grooms} ${grooms === 1 ? "groom becomes" : "grooms become"} unassigned`);
    return parts.length ? ` ${parts.join(" and ")}.` : "";
  }
  if (row.roleName === "GROOM") {
    return " Their unfinished sessions and care tasks stay in their name until their Head Trainer reassigns them.";
  }
  return "";
}

// Tiêu đề nhóm: ưu tiên nhãn của profileApi.toRoleLabel; vai trò lạ (không có nhãn) thì dùng roleLabel
// backend gửi kèm.
export function groupTitle(group) {
  const label = toRoleLabel(group.roleName);
  return label === group.roleName ? group.roleLabel || group.roleName : label;
}

// Ô tìm kiếm: khớp không phân biệt hoa thường trên họ tên, username, email, số điện thoại.
export function matchesQuery(row, query) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [row.fullName, row.username, row.email, row.phone]
    .some((v) => (v || "").toLowerCase().includes(q));
}

// Chữ viết tắt cho avatar: hai từ CUỐI của họ tên ("Trần Minh Hoà" -> "MH"), hợp với tên người Việt
// (tên gọi đứng cuối).
export function initialsOf(fullName) {
  const parts = (fullName || "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const last = parts[parts.length - 1][0];
  const first = parts.length > 1 ? parts[parts.length - 2][0] : "";
  return (first + last).toUpperCase();
}

// tone dùng làm hậu tố lớp CSS (good = xanh lá, warning = vàng).
export function statusTone(status) {
  return status === "ACTIVE" ? "good" : "warning";
}

export function statusLabel(status) {
  return status === "ACTIVE" ? "Active" : "Suspended";
}
