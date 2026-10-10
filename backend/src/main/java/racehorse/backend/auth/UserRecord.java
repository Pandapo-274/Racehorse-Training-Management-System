package racehorse.backend.auth;

/** Một dòng APP_USER (join ROLE) đọc từ DB. Chỉ dùng nội bộ backend vì có passwordHash. */
public record UserRecord(int userId, String username, String passwordHash,
                         String fullName, String email, String status, String role,
                         /* Thêm ở CUỐI chứ không chen vào giữa: record dựng theo thứ tự
                            tham số, chen vào giữa là mọi lời gọi new UserRecord(...) im
                            lặng gán lệch cột mà vẫn biên dịch được nếu kiểu trùng nhau. */
                         String avatarUrl) {}
