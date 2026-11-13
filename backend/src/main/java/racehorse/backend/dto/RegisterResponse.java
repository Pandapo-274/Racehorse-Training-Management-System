package racehorse.backend.dto;

/** Trả về cho client. TUYỆT ĐỐI không có password_hash. */
public record RegisterResponse(
        int userId,
        String username,
        String fullName,
        String email,
        String roleName,
        String status
) {}
