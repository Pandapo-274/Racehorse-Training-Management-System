package racehorse.backend.staff;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.TokenBlacklist;
import racehorse.backend.auth.UserRepository;

/**
 * UC19 (write half) - the manager creates staff accounts and locks / unlocks accounts.
 *
 * Why it exists: AuthService.SELF_REGISTER_ROLES now holds only HORSE_OWNER, so
 * nobody can sign themselves up as a trainer, vet or groom. This is the only
 * way those accounts come into being.
 */
@Service
public class StaffService {

    /** Vai trò manager được cấp. CLUB_MANAGER và HORSE_OWNER cố ý không có ở đây. */
    static final Set<String> STAFF_ROLES = Set.of("GROOM", "HEAD_TRAINER", "VETERINARIAN");

    private final UserRepository users;
    private final StaffRepository staff;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklist blacklist;

    public StaffService(UserRepository users, StaffRepository staff,
                        PasswordEncoder passwordEncoder, TokenBlacklist blacklist) {
        this.users = users;
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.blacklist = blacklist;
    }

    /** @Transactional: insert user + ghi audit hoặc cùng thành công, hoặc cùng rollback. */
    @Transactional
    public StaffRow create(StaffController.CreateRequest req, AuthenticatedUser actor, String ip) {
        String username = req.username().trim();
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String fullName = req.fullName().trim();
        String phone = (req.phone() == null || req.phone().isBlank()) ? null : req.phone().trim();
        String roleName = req.roleName().trim().toUpperCase(Locale.ROOT);

        if (!STAFF_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Role must be Head Trainer, Veterinarian or Groom",
                    Map.of("roleName", "Choose Head Trainer, Veterinarian or Groom"));
        }
        int roleId = users.findRoleId(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Role does not exist"));

        if (users.existsByUsername(username)) {
            throw conflict(Map.of("username", "Username already exists"));
        }
        if (users.existsByEmail(email)) {
            throw conflict(Map.of("email", "Email is already registered"));
        }

        int userId;
        try {
            userId = users.insert(roleId, username, passwordEncoder.encode(req.password()),
                    fullName, email, phone);
        } catch (DuplicateKeyException e) {
            // UNIQUE của DB là chốt chặn cuối khi hai manager tạo cùng lúc.
            throw conflict(Map.of("username", "Username or email already exists"));
        }

        users.writeAudit(actor.userId(), "STAFF_CREATE", "APP_USER", userId, "SUCCESS", ip);
        return staff.findRow(userId).orElseThrow(
                () -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not load the new account"));
    }

    /**
     * Khoá (SUSPENDED) hoặc mở khoá (ACTIVE). Không đụng tài khoản CLUB_MANAGER
     * (kể cả của chính mình) để không ai tự khoá mình ra khỏi hệ thống.
     * Khoá xong thì đá hết phiên đang đăng nhập: không đá thì token cũ vẫn dùng được.
     */
    @Transactional
    public StaffRow setStatus(int userId, String status, AuthenticatedUser actor, String ip) {
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (target.userId() == actor.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot change your own account status");
        }
        if ("CLUB_MANAGER".equals(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Club manager accounts cannot be suspended here");
        }
        if (status.equals(target.status())) {
            return target;
        }

        staff.updateStatus(userId, status);
        if ("SUSPENDED".equals(status)) {
            blacklist.revokeAllSessions(userId);
        }
        users.writeAudit(actor.userId(),
                "SUSPENDED".equals(status) ? "STAFF_SUSPEND" : "STAFF_ACTIVATE",
                "APP_USER", userId, "SUCCESS", ip);
        return staff.findRow(userId).orElse(target);
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }
}