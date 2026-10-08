package racehorse.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest http) {
        RegisterResponse body = authService.register(request, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    /** Cần token. Sau lệnh này token hiện tại không dùng được nữa. */
    @PostMapping("/logout")
    public Map<String, String> logout(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                      HttpServletRequest http) {
        authService.logout(user, http.getRemoteAddr());
        return Map.of("message", "Logged out successfully");
    }

    /** Cần token. Interceptor đã giải mã sẵn và nhét user vào request. */
    @GetMapping("/me")
    public UserInfo me(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return authService.me(user.userId());
    }
}
