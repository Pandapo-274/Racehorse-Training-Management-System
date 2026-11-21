package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * Body của PUT /api/auth/change-password (PasswordController -> PasswordService).
 * max = 72: BCrypt chỉ dùng 72 byte đầu của mật khẩu, phần dư bị bỏ qua âm thầm, nên
 * chặn luôn ở đây để người dùng không tưởng mật khẩu dài hơn thì an toàn hơn.
 * Regex ^(?=.*[A-Za-z])(?=.*\d).*$ = "có ít nhất một chữ cái VÀ ít nhất một chữ số"
 * (hai lookahead (?=...) kiểm điều kiện mà không "ăn" ký tự nào).
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 72, message = "Must be at most 72 characters")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain both letters and digits")
        String newPassword
) {}
