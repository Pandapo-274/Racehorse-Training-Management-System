package racehorse.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "username" nhận cả username lẫn email (tài liệu UC2 ghi đăng nhập bằng Email). */
public record LoginRequest(
        @NotBlank(message = "Username or email is required")
        @Size(max = 100, message = "Must be at most 100 characters")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 72, message = "Must be at most 72 characters")
        String password
) {}
