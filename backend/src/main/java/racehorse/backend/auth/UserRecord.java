package racehorse.backend.auth;

/** Một dòng APP_USER (join ROLE) đọc từ DB. Chỉ dùng nội bộ backend vì có passwordHash. */
public record UserRecord(int userId, String username, String passwordHash,
                         String fullName, String email, String status, String role) {}
