package racehorse.backend.auth;

/** Thông tin user an toàn để trả cho frontend. Field "role" khớp với user.role mà frontend đang dùng. */
public record UserInfo(int userId, String username, String fullName, String email, String role) {}
