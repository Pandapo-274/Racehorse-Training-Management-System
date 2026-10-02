// src/features/auth/authService.js
// Dữ liệu cứng để dựng giao diện. Khi có backend sẽ thay bằng fetch("/api/auth/login").
export const DEMO_USERS = [
  { username: "hoa.tm",   password: "123456", fullName: "Trần Minh Hòa",    role: "CLUB_MANAGER" },
  { username: "thang.nd", password: "123456", fullName: "Nguyễn Đức Thắng", role: "HEAD_TRAINER" },
];

export const HOME_BY_ROLE = {
  CLUB_MANAGER: "/manager/horses",
  HEAD_TRAINER: "/trainer/progress",
};

export function login(username, password) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      const found = DEMO_USERS.find(
        (u) => u.username === username && u.password === password
      );
      if (!found) return reject(new Error("Sai tên đăng nhập hoặc mật khẩu"));
      const { password: _pw, ...user } = found; // không lưu mật khẩu
      resolve(user);
    }, 400);
  });
}