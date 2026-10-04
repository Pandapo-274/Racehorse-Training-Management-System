// src/features/auth/authService.js
// Dữ liệu cứng để dựng giao diện. Khi có backend sẽ thay bằng fetch("/api/auth/login").
export const DEMO_USERS = [
  {
    username: "manager",
    password: "123456",
    fullName: "Club Manager",
    role: "CLUB_MANAGER"
  },
  {
    username: "trainer",
    password: "123456",
    fullName: "Head Trainer",
    role: "HEAD_TRAINER"
  },
  {
    username: "vet",
    password: "123456",
    fullName: "Veterinarian",
    role: "VETERINARIAN"
  },
  {
    username: "groom",
    password: "123456",
    fullName: "GROOM",
    role: "GROOM"
  },
  {
    username: "owner",
    password: "123456",
    fullName: "Horse Owner",
    role: "HORSE_OWNER"
  }
];

export const HOME_BY_ROLE = {
  CLUB_MANAGER: "/manager",
  HEAD_TRAINER: "/trainer",
  VETERINARIAN: "/veterinarian",
  GROOM: "/groom",
  HORSE_OWNER: "/horse-owner",
};

export function login(username, password) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      const found = DEMO_USERS.find(
        (u) => u.username === username && u.password === password
      );
      if (!found) return reject(new Error("Incorrect username or password"));
      const { password: _pw, ...user } = found; // không lưu mật khẩu
      resolve(user);
    }, 400);
  });
}