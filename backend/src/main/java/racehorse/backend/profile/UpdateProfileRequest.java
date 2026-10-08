package racehorse.backend.profile;

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
        String phone
) {}
