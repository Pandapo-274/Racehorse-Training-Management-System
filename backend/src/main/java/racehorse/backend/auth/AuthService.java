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
     * Vai trò được TỰ đăng ký: chỉ HORSE_OWNER. Tài khoản nhân viên (GROOM, HEAD_TRAINER,
     * VETERINARIAN) và CLUB_MANAGER không ai được tự phong; do Club Manager cấp (xem Staff).
     */
    static final Set<String> SELF_REGISTER_ROLES =
            Set.of("HORSE_OWNER");

    /** V39: this many wrong passwords in a row lock sign-in for LOCK_MINUTES. */
    static final int MAX_FAILED_LOGINS = 5;
    static final int LOCK_MINUTES = 15;
    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());

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

        /* Không bắt xác minh email ở đây, có chủ đích: mail tắt mặc định
           (app.mail.enabled=false) nên bắt xác minh là khoá mọi tài khoản mới; và
           mối nguy "đăng ký trước bằng email của người khác" đã được chặn ở
           GoogleAuthService (liên kết Google vô hiệu mật khẩu cũ) và ở quên mật
           khẩu (link chỉ tới hộp thư thật của chủ email). */
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

        /* V39: a locked account is refused even with the right password, and the
           password is not even compared, so guessing during the lock teaches
           nothing. BCrypt above still ran, so the response time is the same. */
        if (found.isPresent() && found.get().isLocked()) {
            users.writeAudit(foundId, "LOGIN", "APP_USER", foundId, "DENIED", ip,
                    "Sign-in is locked after too many wrong passwords");
            throw locked(found.get().lockedUntil());
        }
        // Sai username và sai password dùng CHUNG một thông báo để kẻ xấu không dò được tài khoản nào tồn tại.
        if (found.isEmpty() || !passwordOk) {
            users.writeAudit(foundId, "LOGIN", "APP_USER", foundId, "DENIED", ip,
                    found.isPresent() ? "Wrong password" : "Unknown username or email: " + req.username().trim());
            if (found.isPresent()) {
                java.time.Instant lockedNow = users.recordFailedLogin(foundId, MAX_FAILED_LOGINS, LOCK_MINUTES);
                if (lockedNow != null) {
                    users.writeAudit(foundId, "LOGIN_LOCKED", "APP_USER", foundId, "SUCCESS", ip,
                            MAX_FAILED_LOGINS + " wrong passwords in a row. Locked for " + LOCK_MINUTES + " minutes");
                    throw locked(lockedNow);
                }
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        UserRecord u = found.get();
        if (!"ACTIVE".equals(u.status())) {
            users.writeAudit(u.userId(), "LOGIN", "APP_USER", u.userId(), "DENIED", ip, "Account is suspended");
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is suspended. Please contact the club manager");
        }
        users.clearLoginLock(u.userId());
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
        return new UserInfo(u.userId(), u.username(), u.fullName(), u.email(), u.role(),
                u.avatarUrl(), u.mustChangePassword());
    }

    private static ApiException locked(java.time.Instant until) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many wrong passwords. Sign-in is locked until " + CLOCK.format(until)
                        + ". Wait, reset your password, or ask the club manager for a temporary password");
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }
}