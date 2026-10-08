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
    private final TokenBlacklist blacklist;
    /** Hash giả để vẫn tốn thời gian BCrypt khi username không tồn tại, tránh lộ "user này có thật không" qua độ trễ. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       JwtService jwt, TokenBlacklist blacklist) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.blacklist = blacklist;
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
            throw new ApiException(HttpStatus.FORBIDDEN, "This role cannot be self-registered");
        }
        int roleId = users.findRoleId(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Role does not exist"));

        // Check trước để báo lỗi đúng field cho đẹp UI...
        Map<String, String> dup = new LinkedHashMap<>();
        if (users.existsByUsername(username)) dup.put("username", "Username already exists");
        if (users.existsByEmail(email)) dup.put("email", "Email is already registered");
        if (!dup.isEmpty()) throw conflict(dup);

        int userId;
        try {
            userId = users.insert(roleId, username, passwordEncoder.encode(req.password()),
                    fullName, email, phone);
        } catch (DuplicateKeyException e) {
            // ...nhưng UNIQUE của DB mới là chốt chặn cuối (2 người đăng ký cùng lúc).
            throw conflict(Map.of("username", "Username or email already exists"));
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
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        UserRecord u = found.get();
        if (!"ACTIVE".equals(u.status())) {
            users.writeAudit(u.userId(), "LOGIN", "APP_USER", u.userId(), "DENIED", ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is suspended. Please contact the club manager");
        }

        users.writeAudit(u.userId(), "LOGIN", "APP_USER", u.userId(), "SUCCESS", ip);
        String token = jwt.generate(u.userId(), u.username(), u.role());
        return new LoginResponse(token, "Bearer", jwt.expiresInSeconds(), toInfo(u));
    }

    /**
     * Logout = đưa token đang dùng vào sổ đen + ghi audit.
     * JWT là "vé không trạng thái" nên server không tự huỷ được, phải nhớ số seri vé đã huỷ.
     */
    public void logout(AuthenticatedUser user, String ip) {
        blacklist.revoke(user.tokenId(), user.expiresAt());
        users.writeAudit(user.userId(), "LOGOUT", "APP_USER", user.userId(), "SUCCESS", ip);
    }

    /** Dùng cho GET /me để frontend khôi phục phiên sau khi F5. */
    public UserInfo me(int userId) {
        UserRecord u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        return toInfo(u);
    }

    private UserInfo toInfo(UserRecord u) {
        return new UserInfo(u.userId(), u.username(), u.fullName(), u.email(), u.role());
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }
}
