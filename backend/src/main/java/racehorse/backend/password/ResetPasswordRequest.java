package racehorse.backend.password;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required")
        @Size(max = 200, message = "Reset token is invalid")
        String token,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain both letters and digits")
        String newPassword
) {}
