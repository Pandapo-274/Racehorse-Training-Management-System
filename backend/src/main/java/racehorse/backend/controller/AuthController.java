package racehorse.backend.controller;

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

import racehorse.backend.dto.LoginRequest;
import racehorse.backend.dto.LoginResponse;
import racehorse.backend.dto.RegisterRequest;
import racehorse.backend.dto.RegisterResponse;
import racehorse.backend.dto.UserInfo;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.service.AuthService;

/*
 * CONTROLLER = lớp nhận HTTP request, đổi JSON thành DTO, gọi service, trả kết quả.
 * Quy ước của dự án: controller KHÔNG chứa logic nghiệp vụ; mọi luật nằm trong service.
 *
 *   @RestController   = @Controller + @ResponseBody: giá trị return được Jackson ghi thành JSON.
 *   @RequestMapping   = tiền tố URL chung cho mọi method trong class (/api/auth).
 *   @PostMapping("/x")= method này xử lý POST /api/auth/x.
 *   @Valid @RequestBody = đọc body JSON vào record và chạy Bean Validation (sai -> 400).
 *   @RequestAttribute(AuthInterceptor.USER_ATTR) = lấy người dùng mà AuthInterceptor đã
 *                       giải mã từ JWT và gắn vào request.
 *   HttpServletRequest.getRemoteAddr() = IP người gọi, ghi vào AUDIT_LOG.
 *
 * Endpoint của class này:
 *   POST /api/auth/register  (công khai)  - chủ ngựa tự tạo tài khoản
 *   POST /api/auth/login     (công khai)  - đăng nhập, nhận JWT
 *   POST /api/auth/logout    (cần token)  - thu hồi token đang dùng
 *   GET  /api/auth/me        (cần token)  - frontend khôi phục thông tin người dùng sau F5
 */
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
        // 201 Created thay vì 200: quy ước REST khi vừa tạo mới một tài nguyên.
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
