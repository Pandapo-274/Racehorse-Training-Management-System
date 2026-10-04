package racehorse.backend.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /**
     * Vai trò được TỰ đăng ký. CLUB_MANAGER bị loại có chủ đích: không ai được
     * tự phong mình làm quản lý. Manager chỉ được tạo/đổi quyền qua UC Manage Roles (Sprint 3).
     */
    private static final Set<String> SELF_REGISTER_ROLES =
            Set.of("HORSE_OWNER", "GROOM", "HEAD_TRAINER", "VETERINARIAN");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** @Transactional: insert user + ghi audit hoặc cùng thành công, hoặc cùng rollback. */
    @Transactional
    public RegisterResponse register(RegisterRequest req, String ip) {
        String username = req.username().trim();
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String fullName = req.fullName().trim();
        String phone = (req.phone() == null || req.phone().isBlank()) ? null : req.phone().trim();
        String roleName = req.roleName().trim().toUpperCase(Locale.ROOT);

        if (!SELF_REGISTER_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Không thể tự đăng ký với vai trò này");
        }
        int roleId = users.findRoleId(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Vai trò không tồn tại"));

        // Check trước để báo lỗi đúng field cho đẹp UI...
        Map<String, String> dup = new LinkedHashMap<>();
        if (users.existsByUsername(username)) dup.put("username", "Tên đăng nhập đã tồn tại");
        if (users.existsByEmail(email)) dup.put("email", "Email đã được đăng ký");
        if (!dup.isEmpty()) throw conflict(dup);

        int userId;
        try {
            userId = users.insert(roleId, username, passwordEncoder.encode(req.password()),
                    fullName, email, phone);
        } catch (DuplicateKeyException e) {
            // ...nhưng UNIQUE của DB mới là chốt chặn cuối (2 người đăng ký cùng lúc).
            throw conflict(Map.of("username", "Tên đăng nhập hoặc email đã tồn tại"));
        }

        users.writeAudit(userId, "REGISTER", "APP_USER", userId, "SUCCESS", ip);

        // TODO: gửi email xác nhận (cần spring-boot-starter-mail + cấu hình SMTP).
        return new RegisterResponse(userId, username, fullName, email, roleName, "ACTIVE");
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Thông tin đã tồn tại", errors);
    }
}
