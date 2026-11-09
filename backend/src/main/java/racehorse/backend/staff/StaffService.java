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
 *  - V37: 1 Head Trainer quản lý tối đa 3 Groom đang hoạt động tại 1 thời điểm
 *    và được Club Manager tự phê duyệt, đăng ký Grooms cũng như thiết lập hạn mức
 *    ngựa (max_horses) cho từng Head Trainer.
 */
@Service
public class StaffService {

    /** Vai trò manager được cấp. CLUB_MANAGER và HORSE_OWNER cố ý không có ở đây. */
    static final Set<String> STAFF_ROLES = Set.of("GROOM", "HEAD_TRAINER", "VETERINARIAN");

    /**
     * Vai trò manager được TẠO tài khoản. Thêm HORSE_OWNER cho chủ ngựa đến làm
     * thủ tục tại câu lạc bộ mà không tự đăng ký online. Vẫn không có
     * CLUB_MANAGER, và đổi vai trò (changeRole) vẫn chỉ trong STAFF_ROLES: chủ
     * ngựa là khách hàng, không biến thành nhân viên.
     */
    static final Set<String> CREATE_ROLES = Set.of("GROOM", "HEAD_TRAINER", "VETERINARIAN", "HORSE_OWNER");

    /** V37: 1 Head Trainer chỉ quản lý tối đa 3 Groom tại 1 thời điểm. */
    public static final int MAX_GROOMS_PER_TRAINER = 3;
    public static final int DEFAULT_MAX_HORSES_PER_TRAINER = 10;

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
    public record StaffUpdate(StaffRow member, int sessionsToReassign, String notice) {
        StaffUpdate(StaffRow member, int sessionsToReassign) {
            this(member, sessionsToReassign, null);
        }
    }

    /** @Transactional: insert user + ghi audit hoặc cùng thành công, hoặc cùng rollback. */
    @Transactional
    public StaffRow create(StaffController.CreateRequest req, AuthenticatedUser actor, String ip) {
        if (actor != null && !"CLUB_MANAGER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can create staff accounts");
        }
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

        // V37: Nếu tạo GROOM có gán cho Head Trainer thì kiểm tra Head Trainer hợp lệ và chưa quá 3 Groom
        StaffRow trainer = null;
        if ("GROOM".equals(roleName) && req.trainerId() != null) {
            trainer = validateTrainerForGroom(req.trainerId(), null);
        }

        // V37: Nếu tạo HEAD_TRAINER thì kiểm tra maxHorses (1..100, mặc định 10)
        int maxHorses = DEFAULT_MAX_HORSES_PER_TRAINER;
        if ("HEAD_TRAINER".equals(roleName) && req.maxHorses() != null) {
            if (req.maxHorses() < 1 || req.maxHorses() > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Max horses must be between 1 and 100",
                        Map.of("maxHorses", "Must be between 1 and 100"));
            }
            maxHorses = req.maxHorses();
        }

        int userId;
        try {
            userId = users.insert(roleId, username, passwordEncoder.encode(req.password()),
                    fullName, email, phone);
        } catch (DuplicateKeyException e) {
            // UNIQUE của DB là chốt chặn cuối khi hai manager tạo cùng lúc.
            throw conflict(Map.of("username", "Username or email already exists"));
        }

        if ("GROOM".equals(roleName) && trainer != null) {
            staff.updateTrainer(userId, trainer.userId());
            notifications.notifyUser(trainer.userId(), new Notice(
                    "GROOM_ASSIGNED",
                    "New groom assigned to your team",
                    "Club manager assigned groom " + fullName + " to your team.",
                    null, null));
            notifications.notifyUser(userId, new Notice(
                    "TRAINER_ASSIGNED",
                    "Assigned to Head Trainer",
                    "Club manager assigned you to Head Trainer " + trainer.fullName() + ".",
                    null, null));
        } else if ("HEAD_TRAINER".equals(roleName)) {
            staff.updateMaxHorses(userId, maxHorses);
        }

        // Mật khẩu này manager đặt và biết, nên chỉ là mật khẩu tạm (V14).
        users.setMustChangePassword(userId, true);
        users.writeAudit(actor == null ? null : actor.userId(), "STAFF_CREATE", "APP_USER", userId, "SUCCESS", ip);
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
        if (actor != null && !"CLUB_MANAGER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can change staff account status");
        }
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (actor != null && target.userId() == actor.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot change your own account status");
        }
        if ("CLUB_MANAGER".equals(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Club manager accounts cannot be suspended here");
        }
        if (status.equals(target.status())) {
            return new StaffUpdate(target, 0);
        }

        // V37: Khi mở khoá lại 1 GROOM đang gắn với 1 Head Trainer, kiểm tra Head Trainer đó đã đủ 3 Groom chưa
        if ("ACTIVE".equals(status) && "GROOM".equals(target.roleName()) && target.trainerId() != null) {
            validateTrainerForGroom(target.trainerId(), target.userId());
        }

        staff.updateStatus(userId, status);
        int toReassign = 0;
        String notice = null;
        if ("SUSPENDED".equals(status)) {
            blacklist.revokeAllSessions(userId);
            toReassign = warnIfGroomLeaves(target, actor, "was suspended");
            if ("HORSE_OWNER".equals(target.roleName())) {
                notice = ownerSuspended(target, actor);
            }
        } else {
            // Mở khoá tài khoản cũng gỡ khoá đăng nhập do sai mật khẩu (V39).
            users.clearLoginLock(userId);
        }
        users.writeAudit(actor == null ? null : actor.userId(),
                "SUSPENDED".equals(status) ? "STAFF_SUSPEND" : "STAFF_ACTIVATE",
                "APP_USER", userId, "SUCCESS", ip);
        return new StaffUpdate(staff.findRow(userId).orElse(target), toReassign, notice);
    }

    /**
     * Manager sửa họ tên, email, số điện thoại của một tài khoản (nhân viên hoặc
     * chủ ngựa). Không sửa tài khoản Club Manager ở đây - kể cả của mình: mình
     * sửa ở trang Profile, nơi đổi email phải nhập mật khẩu.
     */
    @Transactional
    public StaffRow updateContact(int userId, StaffController.ContactRequest req, AuthenticatedUser actor, String ip) {
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        if ("CLUB_MANAGER".equals(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Club manager details are changed on the Profile page by their owner");
        }
        String fullName = req.fullName().trim();
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String phone = req.phone() == null || req.phone().isBlank() ? null : req.phone().trim();
        if (staff.emailTakenByOther(email, userId)) {
            throw conflict(Map.of("email", "Email is already registered"));
        }
        try {
            staff.updateContact(userId, fullName, email, phone);
        } catch (DuplicateKeyException e) {
            throw conflict(Map.of("email", "Email is already registered"));
        }
        StringBuilder detail = new StringBuilder();
        if (!target.fullName().equals(fullName)) detail.append("name: ").append(target.fullName()).append(" -> ").append(fullName);
        if (!target.email().equalsIgnoreCase(email)) {
            detail.append(detail.length() > 0 ? "; " : "").append("email: ").append(target.email()).append(" -> ").append(email);
        }
        if (!java.util.Objects.equals(target.phone(), phone)) {
            detail.append(detail.length() > 0 ? "; " : "").append("phone: ").append(target.phone()).append(" -> ").append(phone);
        }
        users.writeAudit(actor.userId(), "STAFF_UPDATE", "APP_USER", userId, "SUCCESS", ip,
                detail.length() == 0 ? null : detail.toString());
        return staff.findRow(userId).orElse(target);
    }

    /**
     * Manager đặt mật khẩu TẠM mới cho một tài khoản (quên mật khẩu mà mail tắt,
     * hoặc bị khoá vì sai 5 lần). Như lúc tạo tài khoản: người dùng phải đổi ở
     * lần đăng nhập kế (V14), mọi phiên cũ bị đá, khoá đăng nhập được gỡ (V39).
     */
    @Transactional
    public StaffRow setTemporaryPassword(int userId, String password, AuthenticatedUser actor, String ip) {
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        if (target.userId() == actor.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Change your own password on the Profile page");
        }
        if ("CLUB_MANAGER".equals(target.roleName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Club manager passwords cannot be reset here");
        }
        staff.setTemporaryPassword(userId, passwordEncoder.encode(password));
        blacklist.revokeAllSessions(userId);
        users.writeAudit(actor.userId(), "STAFF_TEMP_PASSWORD", "APP_USER", userId, "SUCCESS", ip);
        notifications.notifyUser(userId, new Notice("TEMP_PASSWORD_SET", "Your password was reset",
                "The club manager set a temporary password for you. Change it when you next sign in.",
                null, null));
        return staff.findRow(userId).orElse(target);
    }

    /**
     * Chủ ngựa bị khoá thì những gì đang chờ HỌ quyết sẽ treo mãi: yêu cầu đăng
     * ký ngựa (huỷ luôn - chưa có hồ sơ nào), giáo án và đăng ký giải chờ họ
     * duyệt (không tự huỷ - đó là việc của Head Trainer, chỉ báo). Ngựa vẫn ở
     * câu lạc bộ và vẫn được chăm sóc: khoá tài khoản không phải rút ngựa.
     *
     * @return câu tóm tắt cho màn hình Staff, hoặc null nếu không có gì treo.
     */
    private String ownerSuspended(StaffRow owner, AuthenticatedUser actor) {
        int requests = staff.cancelOpenHorseRequests(owner.userId(), actor.userId());
        int plans = staff.countPlansAwaitingOwner(owner.userId());
        int entries = staff.countRaceEntriesAwaitingOwner(owner.userId());
        int horses = staff.countActiveHorses(owner.userId());
        if (plans + entries > 0) {
            notifications.notifyRole("HEAD_TRAINER", actor, new Notice(
                    "OWNER_SUSPENDED",
                    "Owner approvals on hold",
                    owner.fullName() + " was suspended. " + plans + " training plan(s) and " + entries
                            + " race entr" + (entries == 1 ? "y" : "ies")
                            + " wait for their approval and cannot be approved until the account is active again.",
                    null, null));
        }
        if (requests + plans + entries + horses == 0) {
            return null;
        }
        return owner.fullName() + " still has " + horses + " horse(s) at the club. "
                + (requests > 0 ? requests + " registration request(s) were cancelled. " : "")
                + (plans + entries > 0 ? (plans + entries) + " approval(s) are on hold; head trainers were told." : "");
    }

    /**
     * Đổi vai trò giữa Head Trainer, Vet và Groom - phần "phân quyền cho từng
     * vai trò" của UC19: quyền gắn với vai trò, nên đổi vai trò là đổi quyền.
     */
    @Transactional
    public StaffUpdate changeRole(int userId, String requestedRole, AuthenticatedUser actor, String ip) {
        if (actor != null && !"CLUB_MANAGER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can change staff roles");
        }
        String roleName = requestedRole.trim().toUpperCase(Locale.ROOT);
        StaffRow target = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (actor != null && target.userId() == actor.userId()) {
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
        if ("GROOM".equals(target.roleName())) {
            staff.updateTrainer(userId, null);
        }
        if ("HEAD_TRAINER".equals(target.roleName())) {
            staff.detachGroomsFromTrainer(userId);
        }
        if ("HEAD_TRAINER".equals(roleName) && target.maxHorses() == null) {
            staff.updateMaxHorses(userId, DEFAULT_MAX_HORSES_PER_TRAINER);
        }

        blacklist.revokeAllSessions(userId);
        int toReassign = warnIfGroomLeaves(target, actor, "is no longer a groom");
        users.writeAudit(actor == null ? null : actor.userId(), "STAFF_ROLE_CHANGE:" + target.roleName() + "->" + roleName,
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
     * V37: Club Manager phê duyệt và gán (hoặc gỡ) Groom cho 1 Head Trainer.
     * Mỗi Head Trainer chỉ được quản lý tối đa 3 Groom đang hoạt động tại 1 thời điểm.
     */
    @Transactional
    public StaffRow assignTrainer(int groomId, Integer trainerId, AuthenticatedUser actor, String ip) {
        if (actor != null && !"CLUB_MANAGER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can assign Grooms to Head Trainers");
        }
        StaffRow groom = staff.findRow(groomId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Groom account not found"));
        if (!"GROOM".equals(groom.roleName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only Groom accounts can be assigned to a Head Trainer");
        }

        if (trainerId != null) {
            StaffRow trainer = validateTrainerForGroom(trainerId, groomId);
            staff.updateTrainer(groomId, trainer.userId());
            notifications.notifyUser(trainer.userId(), new Notice(
                    "GROOM_ASSIGNED",
                    "New groom assigned to your team",
                    "Club manager assigned groom " + groom.fullName() + " to your team.",
                    null, null));
            notifications.notifyUser(groomId, new Notice(
                    "TRAINER_ASSIGNED",
                    "Assigned to Head Trainer",
                    "Club manager assigned you to Head Trainer " + trainer.fullName() + ".",
                    null, null));
        } else {
            staff.updateTrainer(groomId, null);
        }

        users.writeAudit(actor == null ? null : actor.userId(),
                "STAFF_ASSIGN_TRAINER:" + (trainerId == null ? "NONE" : trainerId),
                "APP_USER", groomId, "SUCCESS", ip);
        return staff.findRow(groomId).orElse(groom);
    }

    /**
     * V37: Club Manager cập nhật hạn mức số ngựa tối đa (max_horses) cho một Head Trainer.
     */
    @Transactional
    public StaffRow updateMaxHorses(int trainerId, int maxHorses, AuthenticatedUser actor, String ip) {
        if (actor != null && !"CLUB_MANAGER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can update horse quota");
        }
        StaffRow trainer = staff.findRow(trainerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Head Trainer account not found"));
        if (!"HEAD_TRAINER".equals(trainer.roleName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only Head Trainer accounts have a horse limit");
        }
        if (maxHorses < 1 || maxHorses > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Max horses must be between 1 and 100");
        }
        if (maxHorses < trainer.managedHorseCount()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    trainer.fullName() + " is currently managing " + trainer.managedHorseCount()
                            + " horses. Please reassign some horses before lowering the limit to " + maxHorses + ".");
        }

        staff.updateMaxHorses(trainerId, maxHorses);
        users.writeAudit(actor == null ? null : actor.userId(), "STAFF_MAX_HORSES:" + maxHorses, "APP_USER", trainerId, "SUCCESS", ip);
        return staff.findRow(trainerId).orElse(trainer);
    }

    private StaffRow validateTrainerForGroom(int trainerId, Integer excludeGroomId) {
        StaffRow trainer = staff.findRow(trainerId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Selected Head Trainer does not exist"));
        if (!"HEAD_TRAINER".equals(trainer.roleName()) || !"ACTIVE".equals(trainer.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Selected user is not an active Head Trainer");
        }
        int currentGrooms = staff.countActiveGroomsOfTrainer(trainerId, excludeGroomId);
        if (currentGrooms >= MAX_GROOMS_PER_TRAINER) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Head Trainer " + trainer.fullName() + " is already managing the maximum of "
                            + MAX_GROOMS_PER_TRAINER + " active grooms");
        }
        return trainer;
    }

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
        int tasks = staff.countOpenCareTasks(target.userId());
        if (tasks > 0) {
            notifications.notifyRole("HEAD_TRAINER", actor, new Notice(
                    "CARE_TASKS_NEED_GROOM",
                    "Care tasks need a new groom",
                    target.fullName() + " " + reason + " and still has " + tasks
                            + (tasks == 1 ? " care task" : " care tasks")
                            + " (feeding, stall cleaning...) to hand over in Stable care.",
                    null, null));
        }
        return open;
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }
}
