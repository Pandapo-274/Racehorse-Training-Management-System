package racehorse.backend.dto;

/** Trả về cho client. TUYỆT ĐỐI không có password_hash. */
// status luôn là "ACTIVE" với tài khoản vừa đăng ký.
public record RegisterResponse(
        int userId,
        String username,
        String fullName,
        String email,
        String roleName,
        String status
) {}
