package racehorse.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Chỉ cho sửa 3 thứ này. Cố ý KHÔNG có username, role, status:
 * đổi role/khoá tài khoản là việc của Manager (UC Manage Roles), không phải của chính user.
 */
public record UpdateProfileRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email is invalid")
        @Size(max = 100, message = "Email must be at most 100 characters")
        String email,

        @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Phone number is invalid")
        String phone,
        /* Required only when the email changes: the email is where "forgot
           password" sends the reset link, so whoever holds an open session must
           not be able to redirect it without knowing the password. */
        @Size(max = 72, message = "Must be at most 72 characters")
        String currentPassword
) {}
