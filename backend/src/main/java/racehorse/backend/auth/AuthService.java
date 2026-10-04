package racehorse.backend.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final JwtService jwt;
    /** Hash giả để vẫn tốn thời gian BCrypt khi username không tồn tại, tránh lộ "user này có thật không" qua độ trễ. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwt) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
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

    /**
     * KHÔNG gắn @Transactional ở đây có chủ đích: khi login sai ta ghi audit rồi ném lỗi 401.
     * Nếu có transaction, lỗi ném ra sẽ rollback luôn dòng audit vừa ghi.
     */
    public LoginResponse login(LoginRequest req, String ip) {
        Optional<UserRecord> found = users.findByLogin(req.username().trim());
        Integer foundId = found.map(UserRecord::userId).orElse(null);

        String hash = found.map(UserRecord::passwordHash).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(req.password(), hash);

        // Sai username và sai password dùng CHUNG một thông báo để kẻ xấu không dò được tài khoản nào tồn tại.
        if (found.isEmpty() || !passwordOk) {
            users.writeAudit(foundId, "LOGIN", "APP_USER", foundId, "DENIED", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu");
        }

        UserRecord u = found.get();
        if (!"ACTIVE".equals(u.status())) {
            users.writeAudit(u.userId(), "LOGIN", "APP_USER", u.userId(), "DENIED", ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khoá, liên hệ quản lý câu lạc bộ");
        }

        users.writeAudit(u.userId(), "LOGIN", "APP_USER", u.userId(), "SUCCESS", ip);
        String token = jwt.generate(new AuthenticatedUser(u.userId(), u.username(), u.role()));
        return new LoginResponse(token, "Bearer", jwt.expiresInSeconds(), toInfo(u));
    }

    /** Dùng cho GET /me để frontend khôi phục phiên sau khi F5. */
    public UserInfo me(int userId) {
        UserRecord u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản không còn tồn tại"));
        return toInfo(u);
    }

    private UserInfo toInfo(UserRecord u) {
        return new UserInfo(u.userId(), u.username(), u.fullName(), u.email(), u.role());
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Thông tin đã tồn tại", errors);
    }
}
