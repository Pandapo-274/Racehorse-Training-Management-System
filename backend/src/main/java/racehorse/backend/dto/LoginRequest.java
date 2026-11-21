package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "username" nhận cả username lẫn email (tài liệu UC2 ghi đăng nhập bằng Email). */
// Body của POST /api/auth/login (công khai). AuthService tìm theo username HOẶC email, so
// BCrypt, đếm số lần sai (khoá 15 phút sau 5 lần, V39), rồi phát JWT.
public record LoginRequest(
        @NotBlank(message = "Username or email is required")
        @Size(max = 100, message = "Must be at most 100 characters")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 72, message = "Must be at most 72 characters")
        String password
) {}
