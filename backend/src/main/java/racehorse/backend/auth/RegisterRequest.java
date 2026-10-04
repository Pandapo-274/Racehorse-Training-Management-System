package racehorse.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dữ liệu client gửi lên khi đăng ký. Validate ngay tại cửa vào. */
public record RegisterRequest(
        @NotBlank(message = "Vui lòng nhập tên đăng nhập")
        @Size(min = 3, max = 50, message = "Tên đăng nhập dài 3-50 ký tự")
        @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "Chỉ gồm chữ, số, dấu chấm và gạch dưới")
        String username,

        @NotBlank(message = "Vui lòng nhập mật khẩu")
        @Size(min = 8, max = 72, message = "Mật khẩu dài 8-72 ký tự")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Mật khẩu phải có cả chữ và số")
        String password,

        @NotBlank(message = "Vui lòng nhập họ tên")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        String fullName,

        @NotBlank(message = "Vui lòng nhập email")
        @Email(message = "Email không hợp lệ")
        @Size(max = 100, message = "Email tối đa 100 ký tự")
        String email,

        @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Số điện thoại không hợp lệ")
        String phone,

        @NotBlank(message = "Vui lòng chọn vai trò")
        String roleName
) {}
