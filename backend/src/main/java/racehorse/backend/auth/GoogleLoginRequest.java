package racehorse.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * idToken: chuỗi "credential" mà Google trả cho frontend sau khi người dùng chọn tài khoản.
 * roleName: tuỳ chọn, CHỈ dùng khi đây là lần đầu người này vào hệ thống (tạo tài khoản mới). Mặc định HORSE_OWNER.
 */
public record GoogleLoginRequest(
        @NotBlank(message = "Google token is required")
        @Size(max = 4096, message = "Google token is invalid")
        String idToken,

        @Size(max = 30, message = "Role name is too long")
        String roleName
) {}
