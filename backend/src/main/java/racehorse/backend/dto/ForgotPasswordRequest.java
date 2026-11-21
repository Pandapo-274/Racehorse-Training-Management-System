package racehorse.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Body của POST /api/auth/forgot-password (công khai, không cần token). PasswordService
// luôn trả cùng một câu trả lời dù email có tồn tại hay không, để không lộ email nào có
// tài khoản (chống dò tài khoản).
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email is invalid")
        @Size(max = 100, message = "Email must be at most 100 characters")
        String email
) {}
