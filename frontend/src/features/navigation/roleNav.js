// src/features/navigation/roleNav.js
// Menu thanh bên của từng vai trò - MỘT nguồn duy nhất.
//
// Trước đây mỗi dashboard tự viết sidebar của mình, còn HorseShell (các trang
// ngựa) có sidebar riêng chỉ với "Dashboard / Horses", nên vào trang ngựa là
// các tab khác biến mất. Giờ mọi sidebar đều vẽ từ danh sách này (xem NavItems.js).
//
// Mỗi mục:
//   key    - định danh, dùng cho huy hiệu (badges) và để so khớp
//   label  - chữ hiển thị
//   path   - đường dẫn. Không có path = chưa làm, hiện nhưng chưa bấm đi đâu được
//   exact  - chỉ active khi đúng path (mục "trang chủ" của role, vì mọi đường
//            dẫn khác của role đều bắt đầu bằng nó)
//   hash   - cuộn tới phần tử có id này trên trang path (mục chỉ là lối tắt
//            trong cùng một dashboard, nên không bao giờ "active")
//
// Thêm tab mới cho một role: thêm một dòng ở đây là mọi màn của role đó có ngay.

export const ROLE_NAV = {
  HORSE_OWNER: [
    { key: "overview", label: "Overview", path: "/horse-owner", exact: true },
    { key: "horses", label: "My horses", path: "/horse-owner/horses" },
    { key: "results", label: "Results & races", path: "/horse-owner", hash: "results-races" },
    { key: "costs", label: "Costs & prizes", path: "/horse-owner", hash: "costs-prizes" },
  ],

  CLUB_MANAGER: [
    { key: "overview", label: "Overview", path: "/manager", exact: true },
    { key: "horses", label: "Horses", path: "/manager/horses" },
    { key: "registrations", label: "Registrations", path: "/manager/horse-requests" },
    { key: "withdrawals", label: "Withdrawals", path: "/manager/withdrawals" },
    { key: "staff", label: "Staff", path: "/manager/staff" },
    { key: "supplies", label: "Supplies & feed" },
    { key: "permissions", label: "Permissions", path: "/manager/permissions" },
    { key: "audit", label: "Audit log" },
  ],

  HEAD_TRAINER: [
    { key: "overview", label: "Overview", path: "/trainer", exact: true },
    { key: "horses", label: "Horses", path: "/trainer/horses" },
    { key: "plans", label: "Training plans", path: "/trainer/training-plans" },
    { key: "schedule", label: "Schedule", path: "/trainer/schedule" },
    { key: "live", label: "Live session", path: "/trainer/live-session" },
    { key: "review", label: "Session review", path: "/trainer/session-review" },
    { key: "races", label: "Races" },
  ],

  VETERINARIAN: [
    { key: "health", label: "Herd health", path: "/veterinarian", exact: true },
    { key: "horses", label: "Horses", path: "/veterinarian/horses" },
    { key: "examination", label: "Examination", path: "/veterinarian/examination" },
    { key: "recovery", label: "Recovery", path: "/veterinarian/recovery" },
    { key: "preventive", label: "Preventive care", path: "/veterinarian/preventive-care" },
    { key: "prescriptions", label: "Prescriptions", path: "/veterinarian/prescriptions" },
  ],

  GROOM: [
    { key: "today", label: "Today", path: "/groom", exact: true },
    { key: "stable", label: "Stable map", path: "/groom/horses" },
    { key: "feeding", label: "Feeding" },
    { key: "incidents", label: "Incident reports" },
    { key: "supplies", label: "Supplies" },
  ],
};

/** Mục có đang là trang hiện tại không. */
export function isNavActive(item, pathname) {
  if (!item.path || item.hash) return false;
  if (item.exact) return pathname === item.path;
  return pathname === item.path || pathname.startsWith(`${item.path}/`);
}

/** Đích của mục: đường dẫn, kèm #hash nếu là lối tắt cuộn trang. */
export function navTarget(item) {
  return item.hash ? `${item.path}#${item.hash}` : item.path;
}
