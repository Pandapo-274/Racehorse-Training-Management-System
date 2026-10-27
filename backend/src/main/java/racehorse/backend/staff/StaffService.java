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
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * UC19 (write half) - the manager creates staff accounts, changes their role,
 * and locks / unlocks accounts.
 *
 * Ba luật nghiệp vụ nằm ở đây:
 *  - Tài khoản mới dùng mật khẩu TẠM: manager biết mật khẩu đó, nên người dùng
 *    phải đổi ở lần đăng nhập đầu (V14, AuthInterceptor chặn mọi việc khác).
 *  - Đổi vai trò hoặc khoá thì người đó bị đăng xuất ở mọi thiết bị: vai trò
 *    nằm sẵn trong JWT, không thu hồi thì token cũ vẫn mang quyền cũ.
 *  - Groom còn buổi tập chưa xong mà bị khoá hoặc chuyển vai trò thì các buổi
 *    đó không còn ai làm: báo cho mọi Head Trainer để giao lại, và trả số buổi
 *    về cho màn hình Staff cảnh báo manager.
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
    private final NotificationService notifications;

    public StaffService(UserRepository users, StaffRepository staff,
                        PasswordEncoder passwordEncoder, TokenBlacklist blacklist,
                        NotificationService notifications) {
        this.users = users;
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.blacklist = blacklist;
        this.notifications = notifications;
    }

    /**
     * Kết quả khoá / mở khoá / đổi vai trò. sessionsToReassign > 0 nghĩa là
     * người này còn buổi tập chưa xong cần Head Trainer giao lại.
     */
    public record StaffUpdate(StaffRow member, int sessionsToReassign) {}

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

        // Mật khẩu này manager đặt và biết, nên chỉ là mật khẩu tạm (V14).
        users.setMustChangePassword(userId, true);
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
    public StaffUpdate setStatus(int userId, String status, AuthenticatedUser actor, String ip) {
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (target.userId() == actor.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot change your own account status");
        }
        if ("CLUB_MANAGER".equals(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Club manager accounts cannot be suspended here");
        }
        if (status.equals(target.status())) {
            return new StaffUpdate(target, 0);
        }

        staff.updateStatus(userId, status);
        int toReassign = 0;
        if ("SUSPENDED".equals(status)) {
            blacklist.revokeAllSessions(userId);
            toReassign = warnIfGroomLeaves(target, actor, "was suspended");
        }
        users.writeAudit(actor.userId(),
                "SUSPENDED".equals(status) ? "STAFF_SUSPEND" : "STAFF_ACTIVATE",
                "APP_USER", userId, "SUCCESS", ip);
        return new StaffUpdate(staff.findRow(userId).orElse(target), toReassign);
    }

    /**
     * Đổi vai trò giữa Head Trainer, Vet và Groom - phần "phân quyền cho từng
     * vai trò" của UC19: quyền gắn với vai trò, nên đổi vai trò là đổi quyền.
     *
     * Không đổi được: chính mình, tài khoản Club Manager, và chủ ngựa (chủ ngựa
     * là khách hàng chứ không phải nhân viên; họ không được biến thành nhân viên
     * và nhân viên cũng không bị biến thành chủ ngựa ở đây).
     */
    @Transactional
    public StaffUpdate changeRole(int userId, String requestedRole, AuthenticatedUser actor, String ip) {
        String roleName = requestedRole.trim().toUpperCase(Locale.ROOT);
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (target.userId() == actor.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot change your own role");
        }
        if (!STAFF_ROLES.contains(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only Head Trainer, Veterinarian and Groom accounts can change role here");
        }
        if (!STAFF_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Role must be Head Trainer, Veterinarian or Groom",
                    Map.of("roleName", "Choose Head Trainer, Veterinarian or Groom"));
        }
        if (roleName.equals(target.roleName())) {
            return new StaffUpdate(target, 0);
        }
        int roleId = users.findRoleId(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Role does not exist"));

        staff.updateRole(userId, roleId);
        // Token đang dùng còn mang vai trò cũ: đăng xuất ở mọi nơi để lần đăng
        // nhập sau nhận đúng quyền mới.
        blacklist.revokeAllSessions(userId);
        int toReassign = warnIfGroomLeaves(target, actor, "is no longer a groom");
        users.writeAudit(actor.userId(), "STAFF_ROLE_CHANGE:" + target.roleName() + "->" + roleName,
                "APP_USER", userId, "SUCCESS", ip);

        StaffRow updated = staff.findRow(userId).orElse(target);
        notifications.notifyUser(userId, new Notice(
                "STAFF_ROLE_CHANGED",
                "Your role has changed",
                "The club manager changed your role to " + updated.roleLabel() + ".",
                null, null));
        return new StaffUpdate(updated, toReassign);
    }

    /**
     * Groom rời vị trí (bị khoá hoặc đổi vai trò) mà còn buổi tập chưa xong:
     * báo mọi Head Trainer để giao lại. Không chặn thao tác của manager - khoá
     * khẩn cấp vẫn phải làm được - chỉ đảm bảo không buổi nào bị bỏ quên.
     *
     * @return số buổi cần giao lại (0 nếu người này không phải Groom).
     */
    private int warnIfGroomLeaves(StaffRow target, AuthenticatedUser actor, String reason) {
        if (!"GROOM".equals(target.roleName())) return 0;
        int open = staff.countOpenAssignments(target.userId());
        if (open > 0) {
            notifications.notifyRole("HEAD_TRAINER", actor, new Notice(
                    "SESSIONS_NEED_GROOM",
                    "Training sessions need a new groom",
                    target.fullName() + " " + reason + " and still has " + open
                            + (open == 1 ? " session" : " sessions") + " to reassign.",
                    null, null));
        }
        return open;
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }
}