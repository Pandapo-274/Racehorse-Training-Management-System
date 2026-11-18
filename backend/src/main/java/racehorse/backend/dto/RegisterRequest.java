package racehorse.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dữ liệu client gửi lên khi đăng ký. Validate ngay tại cửa vào. */
/*
 * Body của POST /api/auth/register (công khai). AuthService chỉ cho tự đăng ký vai trò
 * HORSE_OWNER; tài khoản nhân viên do Club Manager tạo ở trang Staff (StaffController
 * dùng cùng bộ luật kiểm tra này cho username/mật khẩu).
 * Username: 3-50 ký tự, chỉ chữ, số, dấu chấm và gạch dưới. Phone: rỗng hoặc 9-15 chữ số,
 * có thể có dấu + ở đầu.
 */
public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "Username may only contain letters, digits, dots and underscores")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain both letters and digits")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email is invalid")
        @Size(max = 100, message = "Email must be at most 100 characters")
        String email,

        @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Phone number is invalid")
        String phone,

        @NotBlank(message = "Role is required")
        String roleName
) {}
