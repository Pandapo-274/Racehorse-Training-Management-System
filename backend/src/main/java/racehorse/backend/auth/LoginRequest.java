package racehorse.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "username" nhận cả username lẫn email (tài liệu UC2 ghi đăng nhập bằng Email). */
public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập tên đăng nhập hoặc email")
        @Size(max = 100, message = "Tối đa 100 ký tự")
        String username,

        @NotBlank(message = "Vui lòng nhập mật khẩu")
        @Size(max = 72, message = "Tối đa 72 ký tự")
        String password
) {}
