package racehorse.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class GoogleAuthController {

    private final GoogleAuthService service;

    public GoogleAuthController(GoogleAuthService service) {
        this.service = service;
    }

    /** Công khai (đã được mở trong WebConfig). Nhận ID token từ Google, trả JWT của hệ thống. */
    @PostMapping("/google")
    public GoogleLoginResponse google(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        return service.login(request, http.getRemoteAddr());
    }
}
