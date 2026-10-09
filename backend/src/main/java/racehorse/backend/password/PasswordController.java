package racehorse.backend.password;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;

@RestController
@RequestMapping("/api/auth")
public class PasswordController {

    private final PasswordService passwordService;

    public PasswordController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    /** Cần token. Đổi xong mọi phiên đăng nhập (kể cả phiên này) bị huỷ. */
    @PutMapping("/change-password")
    public Map<String, String> changePassword(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                              @Valid @RequestBody ChangePasswordRequest request,
                                              HttpServletRequest http) {
        passwordService.changePassword(me, request, http.getRemoteAddr());
        return Map.of("message", "Password changed successfully. Please log in again.");
    }

    /** Công khai (không cần token). */
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                              HttpServletRequest http) {
        passwordService.forgotPassword(request, http.getRemoteAddr());
        return Map.of("message", "If an account with that email exists, a password reset link has been sent.");
    }

    /** Công khai (không cần token). */
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                             HttpServletRequest http) {
        passwordService.resetPassword(request, http.getRemoteAddr());
        return Map.of("message", "Password has been reset. You can now log in.");
    }
}
