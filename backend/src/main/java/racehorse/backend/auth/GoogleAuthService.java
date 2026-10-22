package racehorse.backend.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Đăng nhập bằng Google. Có 3 kết quả (outcome):
 *  LOGIN      : đã từng dùng Google, vào thẳng.
 *  REGISTERED : lần đầu, chưa có tài khoản nào dùng email này -> tạo mới.
 *  LINKED     : đã có tài khoản đăng ký thường cùng email -> gắn Google vào tài khoản đó.
 *
 * Không gắn @Transactional có chủ đích: khi tài khoản bị khoá ta ghi audit DENIED rồi ném lỗi,
 * có transaction thì dòng audit bị rollback mất (giống login thường).
 */
@Service
public class GoogleAuthService {

    private final GoogleTokenVerifier verifier;
    private final GoogleAuthRepository googleRepo;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final TokenBlacklist blacklist;
    private final AuthService authService;
    private final SecureRandom random = new SecureRandom();

    public GoogleAuthService(GoogleTokenVerifier verifier, GoogleAuthRepository googleRepo,
                             UserRepository users, PasswordEncoder passwordEncoder,
                             JwtService jwt, TokenBlacklist blacklist, AuthService authService) {
        this.verifier = verifier;
        this.googleRepo = googleRepo;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.authService = authService;
    }

    public GoogleLoginResponse login(GoogleLoginRequest req, String ip) {
        GoogleIdentity g = verifier.verify(req.idToken().trim());
        String email = g.email().trim().toLowerCase(Locale.ROOT);

        UserRecord user = null;
        boolean needLink = false;

        Optional<UserRecord> bySub = googleRepo.findUserIdByGoogleSub(g.sub()).flatMap(users::findById);
        if (bySub.isPresent()) {
            user = bySub.get();
        } else {
            Optional<UserRecord> byEmail = users.findByLogin(email);
            if (byEmail.isPresent()) {
                user = byEmail.get();
                if (googleRepo.findGoogleSub(user.userId()).isPresent()) {
                    // Email này đã gắn với MỘT tài khoản Google khác (sub khác) -> không tự ý ghi đè.
                    throw new ApiException(HttpStatus.CONFLICT,
                            "This account is already linked to a different Google account");
                }
                needLink = true;
            }
        }

        // Tài khoản bị khoá thì không cho vào, và cũng không liên kết thêm gì.
        if (user != null && !"ACTIVE".equals(user.status())) {
            users.writeAudit(user.userId(), "LOGIN_GOOGLE", "APP_USER", user.userId(), "DENIED", ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is suspended. Please contact the club manager");
        }

        String outcome = "LOGIN";
        if (user == null) {
            user = createAccount(g, email, req.roleName());
            outcome = "REGISTERED";
            users.writeAudit(user.userId(), "REGISTER_GOOGLE", "APP_USER", user.userId(), "SUCCESS", ip);
        } else if (needLink) {
            /*
             * Vì sao phải vô hiệu mật khẩu cũ khi liên kết?
             * Lúc đăng ký thường (UC1) hệ thống KHÔNG xác minh email. Kẻ xấu có thể đăng ký trước một tài khoản
             * bằng email của nạn nhân (kèm mật khẩu của hắn), chờ nạn nhân bấm "Đăng nhập Google" rồi vào chung.
             * Google đã chứng minh người đang bấm mới là chủ email, nên ta đổi mật khẩu cũ thành chuỗi ngẫu nhiên
             * không ai biết và đá hết phiên đang đăng nhập. Người dùng thật muốn có mật khẩu lại thì dùng "Forgot password".
             */
            googleRepo.linkGoogle(user.userId(), g.sub(), passwordEncoder.encode(randomSecret()));
            blacklist.revokeAllSessions(user.userId());
            outcome = "LINKED";
            users.writeAudit(user.userId(), "LINK_GOOGLE", "APP_USER", user.userId(), "SUCCESS", ip);
        }

        users.writeAudit(user.userId(), "LOGIN_GOOGLE", "APP_USER", user.userId(), "SUCCESS", ip);
        String token = jwt.generate(user.userId(), user.username(), user.role());
        UserInfo info = authService.me(user.userId());
        return new GoogleLoginResponse(token, "Bearer", jwt.expiresInSeconds(), info, outcome);
    }

    private UserRecord createAccount(GoogleIdentity g, String email, String requestedRole) {
        String roleName = (requestedRole == null || requestedRole.isBlank())
                ? "HORSE_OWNER" : requestedRole.trim().toUpperCase(Locale.ROOT);
        // Cùng luật với đăng ký thường: không ai được tự phong làm CLUB_MANAGER.
        if (!AuthService.SELF_REGISTER_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This role cannot be self-registered");
        }
        int roleId = users.findRoleId(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Role does not exist"));

        String username = uniqueUsername(email);
        String fullName = (g.name() == null || g.name().isBlank()) ? username : g.name().trim();
        if (fullName.length() > 100) fullName = fullName.substring(0, 100);

        try {
            // Tài khoản Google không có mật khẩu: lưu hash của một chuỗi ngẫu nhiên không ai biết,
            // nên đăng nhập bằng mật khẩu với tài khoản này là bất khả thi (cột password_hash vẫn NOT NULL).
            int id = googleRepo.insertGoogleUser(roleId, username, passwordEncoder.encode(randomSecret()),
                    fullName, email, g.sub());
            return users.findById(id).orElseThrow(
                    () -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not load the new account"));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Could not create the account, please try again");
        }
    }

    /** Lấy phần trước @ của email làm username, lọc ký tự lạ, trùng thì thêm 4 số ngẫu nhiên. */
    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9._]", "");
        if (base.length() < 3) base = "user" + base;
        if (base.length() > 40) base = base.substring(0, 40);
        String candidate = base;
        for (int i = 0; i < 10 && users.existsByUsername(candidate); i++) {
            candidate = base + (1000 + random.nextInt(9000));
        }
        return candidate;
    }

    private String randomSecret() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
