package racehorse.backend.staff;

import java.util.ArrayList;
import java.util.List;
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

        // CREATE_ROLES = the staff roles + HORSE_OWNER (an owner registered at the club desk).
        if (!CREATE_ROLES.contains(roleName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Role must be Head Trainer, Veterinarian, Groom or Horse Owner",
                    Map.of("roleName", "Choose Head Trainer, Veterinarian, Groom or Horse Owner"));
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
        return setStatus(userId, status, null, actor, ip);
    }

    /** handoverTo: when suspending staff, who takes over their open work (same role; optional). */
    @Transactional
    public StaffUpdate setStatus(int userId, String status, Integer handoverTo, AuthenticatedUser actor, String ip) {
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

        StaffRow successor = "SUSPENDED".equals(status) ? checkSuccessor(target, handoverTo) : null;
        staff.updateStatus(userId, status);
        int toReassign = 0;
        String notice = null;
        if ("SUSPENDED".equals(status)) {
            blacklist.revokeAllSessions(userId);
            notice = "HORSE_OWNER".equals(target.roleName())
                    ? ownerSuspended(target, actor)
                    : staffLeaves(target, successor, actor, "was suspended");
        } else {
            // Mở khoá tài khoản cũng gỡ khoá đăng nhập do sai mật khẩu (V39).
            users.clearLoginLock(userId);
        }
        users.writeAudit(actor == null ? null : actor.userId(),
                "SUSPENDED".equals(status) ? "STAFF_SUSPEND" : "STAFF_ACTIVATE",
                "APP_USER", userId, "SUCCESS", ip, notice);
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
        if (!target.fullName().equals(fullName)) detail.append("Name: ").append(target.fullName()).append(" → ").append(fullName);
        if (!target.email().equalsIgnoreCase(email)) {
            detail.append(detail.length() > 0 ? "; " : "").append("Email: ").append(target.email()).append(" → ").append(email);
        }
        if (!java.util.Objects.equals(target.phone(), phone)) {
            detail.append(detail.length() > 0 ? "; " : "").append("Phone: ").append(target.phone() == null ? "none" : target.phone()).append(" → ").append(phone);
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
        return changeRole(userId, requestedRole, null, actor, ip);
    }

    /** handoverTo: who takes over the leaving person's open work (same role; optional). */
    @Transactional
    public StaffUpdate changeRole(int userId, String requestedRole, Integer handoverTo, AuthenticatedUser actor, String ip) {
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
        // A role change is planned, so the work must go to someone: no "Nobody" here
        // (a suspension can still leave it to the Head Trainer / the queue).
        OpenWork work = openWork(userId);
        if (!work.items().isEmpty()) {
            if (work.noCandidate() != null) {
                throw new ApiException(HttpStatus.CONFLICT, work.noCandidate(),
                        Map.of("handoverTo", "Nobody can take over the work yet"));
            }
            if (handoverTo == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Choose who takes over " + target.fullName() + "'s work before changing the role",
                        Map.of("handoverTo", "Choose who takes over the work"));
            }
        }
        StaffRow successor = checkSuccessor(target, handoverTo);

        staff.updateRole(userId, roleId);
        if ("GROOM".equals(target.roleName())) {
            staff.updateTrainer(userId, null);
        }
        String notice = staffLeaves(target, successor, actor, "is no longer a " + roleWord(target.roleName()));
        if ("HEAD_TRAINER".equals(roleName) && target.maxHorses() == null) {
            staff.updateMaxHorses(userId, DEFAULT_MAX_HORSES_PER_TRAINER);
        }

        blacklist.revokeAllSessions(userId);
        int toReassign = 0; // the notice already says what is left and who was told
        users.writeAudit(actor == null ? null : actor.userId(), "STAFF_ROLE_CHANGE:" + target.roleName() + "->" + roleName,
                "APP_USER", userId, "SUCCESS", ip, notice);

        StaffRow updated = staff.findRow(userId).orElse(target);
        notifications.notifyUser(userId, new Notice(
                "STAFF_ROLE_CHANGED",
                "Your role has changed",
                "The club manager changed your role to " + updated.roleLabel() + ".",
                null, null));
        return new StaffUpdate(updated, toReassign, notice);
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

        StaffRow after = staff.findRow(groomId).orElse(groom);
        users.writeAudit(actor == null ? null : actor.userId(),
                "STAFF_ASSIGN_TRAINER:" + (trainerId == null ? "NONE" : trainerId),
                "APP_USER", groomId, "SUCCESS", ip,
                "Head Trainer: " + orNone(groom.trainerName()) + " → " + orNone(after.trainerName()));
        return after;
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
        users.writeAudit(actor == null ? null : actor.userId(), "STAFF_MAX_HORSES:" + maxHorses, "APP_USER", trainerId,
                "SUCCESS", ip, "Max horses: " + (trainer.maxHorses() == null ? 10 : trainer.maxHorses()) + " → " + maxHorses);
        return staff.findRow(trainerId).orElse(trainer);
    }

    /** A Head Trainer's grooms and the club horses in their charge (read only). */
    public record TrainerTeam(StaffRow trainer,
                              List<StaffRepository.TeamGroom> grooms,
                              List<StaffRepository.TeamHorse> horses) {}

    public TrainerTeam trainerTeam(int trainerId) {
        StaffRow trainer = staff.findRow(trainerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Head Trainer account not found"));
        if (!"HEAD_TRAINER".equals(trainer.roleName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only Head Trainer accounts have a team");
        }
        return new TrainerTeam(trainer, staff.groomsOfTrainer(trainerId), staff.activeHorsesOfTrainer(trainerId));
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

    /**
     * A groom leaves (role change or suspension): their sessions and care tasks stay
     * in their name until a Head Trainer hands them over. The notices go to the
     * groom's OWN Head Trainer (the one who plans for them); only a groom with no
     * trainer falls back to telling every Head Trainer. A running session is named
     * separately: nobody but a Head Trainer can close it now.
     */
    /**
     * Open work of a staff member, shown in the manager's confirm dialog before
     * suspending them or changing their role.
     *  items      what is unfinished, one readable line each
     *  candidates who may take it over (same role, room for it)
     *  fallback   what happens if nobody is chosen
     *  note       a warning (e.g. no other vet left), or null
     *  noCandidate when there is work but nobody can take it over: why, and what
     *              to do first. A role change is refused then (a suspension is not).
     */
    public record OpenWork(String role, List<String> items, List<StaffRow> candidates, String fallback, String note,
                           String noCandidate) {
        OpenWork(String role, List<String> items, List<StaffRow> candidates, String fallback, String note) {
            this(role, items, candidates, fallback, note, null);
        }

        OpenWork withNoCandidate(String why) {
            return items.isEmpty() || !candidates.isEmpty() ? this
                    : new OpenWork(role, items, candidates, fallback, note, why);
        }
    }

    public OpenWork openWork(int userId) {
        StaffRow p = staff.findRow(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        List<String> items = new ArrayList<>();
        switch (p.roleName()) {
            case "GROOM" -> {
                int running = staff.countRunningSessions(userId);
                int scheduled = staff.countOpenAssignments(userId) - running;
                int tasks = staff.countOpenCareTasks(userId);
                if (running > 0) items.add(plural(running, "running session") + ": stopped now and sent to the Head Trainer's review");
                if (scheduled > 0) items.add(plural(scheduled, "scheduled session") + " from today on");
                if (tasks > 0) items.add(plural(tasks, "open care task") + " (feeding, stall cleaning...)");
                return new OpenWork(p.roleName(), items,
                        staff.handoverCandidates("GROOM", userId, p.trainerId()),
                        "Nobody: the work stays in their name and their Head Trainer reassigns it", null)
                        .withNoCandidate(p.trainerId() == null
                                ? "No other active groom can take over " + p.fullName() + "'s work. Create or activate a groom first, or suspend " + p.fullName() + " instead."
                                : "No other active groom on " + p.trainerName() + "'s team can take over " + p.fullName()
                                        + "'s work. Assign another groom to that team first, or suspend " + p.fullName() + " instead.");
            }
            case "HEAD_TRAINER" -> {
                int horses = staff.horsesOfTrainer(userId).size();
                int grooms = staff.groomIdsOfTrainer(userId).size();
                int running = staff.countRunningSessionsOfTrainer(userId);
                int reviews = staff.countSessionsToReviewOfTrainer(userId);
                int plans = staff.countOpenPlansOfTrainer(userId);
                if (horses > 0) items.add(plural(horses, "horse") + " in their charge");
                if (grooms > 0) items.add(plural(grooms, "groom") + " on their team");
                if (running > 0) items.add(plural(running, "session") + " running now on their horses"
                        + " (if nobody takes over, their grooms are told to stop them)");
                if (reviews > 0) items.add(plural(reviews, "session") + " waiting for their review");
                if (plans > 0) items.add(plural(plans, "open training plan"));
                List<StaffRow> room = staff.handoverCandidates("HEAD_TRAINER", userId, null).stream()
                        .filter(t -> roomForTeam(p, t) == null)
                        .toList();
                return new OpenWork(p.roleName(), items, room,
                        "Nobody: the horses and grooms wait until you give them to a Head Trainer",
                        items.isEmpty() ? null : "Only Head Trainers with room for all of it are listed.")
                        .withNoCandidate("No other Head Trainer has room for " + plural(p.managedHorseCount(), "horse")
                                + " and " + plural(p.groomCount(), "groom") + ". Raise a Head Trainer's limit or move some horses first, or suspend "
                                + p.fullName() + " instead.");
            }
            case "VETERINARIAN" -> {
                int cases = staff.countCasesOfVet(userId);
                if (cases > 0) items.add(plural(cases, "incident case") + " they have taken and not examined yet");
                int otherVets = staff.countActiveOfRole("VETERINARIAN", userId);
                return new OpenWork(p.roleName(), items,
                        staff.handoverCandidates("VETERINARIAN", userId, null),
                        "Nobody: the cases go back to the open queue for every vet",
                        otherVets == 0 ? "There is no other active Veterinarian: incident cases and vet calls will have nobody to answer them." : null)
                        .withNoCandidate("No other active Veterinarian can take over " + p.fullName()
                                + "'s cases. Create or activate a Veterinarian first, or suspend " + p.fullName() + " instead.");
            }
            default -> {
                return new OpenWork(p.roleName(), items, List.of(), null, null);
            }
        }
    }

    /**
     * The person chosen to take over must be an active account of the same role.
     * A groom's successor must be on the same Head Trainer's team (sessions may
     * only go to a groom of the horse's trainer); a Head Trainer's successor must
     * have room for all the horses and grooms. Null = no hand-over.
     */
    private StaffRow checkSuccessor(StaffRow leaving, Integer handoverTo) {
        if (handoverTo == null || !STAFF_ROLES.contains(leaving.roleName())) return null;
        if (handoverTo == leaving.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose someone else to take over the work",
                    Map.of("handoverTo", "Choose someone else"));
        }
        String word = roleWord(leaving.roleName());
        StaffRow next = staff.findRow(handoverTo)
                .filter(r -> leaving.roleName().equals(r.roleName()) && "ACTIVE".equals(r.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "The person taking over must be an active " + word + " account",
                        Map.of("handoverTo", "Choose an active " + word)));
        if ("GROOM".equals(leaving.roleName()) && leaving.trainerId() != null
                && !leaving.trainerId().equals(next.trainerId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    next.fullName() + " is not on " + leaving.trainerName() + "'s team. Choose a groom of the same team",
                    Map.of("handoverTo", "Choose a groom of the same Head Trainer"));
        }
        if ("HEAD_TRAINER".equals(leaving.roleName())) {
            String full = roomForTeam(leaving, next);
            if (full != null) {
                throw new ApiException(HttpStatus.CONFLICT, full, Map.of("handoverTo", "Not enough room"));
            }
        }
        return next;
    }

    /** Null if trainer {@code to} can take all of {@code from}'s horses and grooms, else why not. */
    private String roomForTeam(StaffRow from, StaffRow to) {
        int max = to.maxHorses() == null ? DEFAULT_MAX_HORSES_PER_TRAINER : to.maxHorses();
        if (to.managedHorseCount() + from.managedHorseCount() > max) {
            return to.fullName() + " has " + to.managedHorseCount() + " of " + max + " horses and cannot take "
                    + plural(from.managedHorseCount(), "more horse") + ". Choose another Head Trainer or nobody";
        }
        if (to.groomCount() + from.groomCount() > MAX_GROOMS_PER_TRAINER) {
            return to.fullName() + " has " + to.groomCount() + " of " + MAX_GROOMS_PER_TRAINER
                    + " grooms and cannot take " + plural(from.groomCount(), "more groom")
                    + ". Choose another Head Trainer or nobody";
        }
        return null;
    }

    /** A staff member leaves (role change or suspension): deal with what they left unfinished. */
    private String staffLeaves(StaffRow target, StaffRow successor, AuthenticatedUser actor, String reason) {
        return switch (target.roleName()) {
            case "GROOM" -> groomLeaves(target, successor, actor, reason);
            case "HEAD_TRAINER" -> headTrainerLeaves(target, successor, actor, reason);
            case "VETERINARIAN" -> vetLeaves(target, successor, actor, reason);
            default -> null;
        };
    }

    private static String roleWord(String roleName) {
        return switch (roleName) {
            case "GROOM" -> "Groom";
            case "HEAD_TRAINER" -> "Head Trainer";
            case "VETERINARIAN" -> "Veterinarian";
            case "HORSE_OWNER" -> "Horse Owner";
            default -> "Club Manager";
        };
    }

    /**
     * A groom leaves. Their work cannot wait for them:
     *  - a RUNNING session is stopped now (STOPPED_EARLY, reason recorded) and goes
     *    to the Head Trainer's review - the groom can no longer finish it;
     *  - with a successor, scheduled sessions and open care tasks from today on
     *    move to that groom, who is told;
     *  - whatever is left stays in the old name and their OWN Head Trainer is asked
     *    to reassign it (every Head Trainer only if the groom had no team).
     */
    private String groomLeaves(StaffRow target, StaffRow successor, AuthenticatedUser actor, String reason) {
        int stopped = staff.stopRunningSessions(target.userId(),
                reason.startsWith("is ") ? "Stopped: the groom changed role" : "Stopped: the groom was suspended");
        int movedSessions = 0;
        int movedTasks = 0;
        if (successor != null) {
            movedSessions = staff.handOverSessions(target.userId(), successor.userId());
            movedTasks = staff.handOverCareTasks(target.userId(), successor.userId());
            if (movedSessions + movedTasks > 0) {
                notifications.notifyUser(successor.userId(), new Notice("WORK_HANDED_OVER", "Work handed over to you",
                        target.fullName() + " " + reason + ". You now have " + plural(movedSessions, "training session")
                                + " and " + plural(movedTasks, "care task") + " of theirs.",
                        null, null));
            }
        }
        int leftSessions = staff.countOpenAssignments(target.userId());
        int leftTasks = staff.countOpenCareTasks(target.userId());

        List<String> forTrainer = new ArrayList<>();
        if (stopped > 0) forTrainer.add(plural(stopped, "running session") + " stopped and waiting for your review");
        if (movedSessions + movedTasks > 0) {
            forTrainer.add(plural(movedSessions, "session") + " and " + plural(movedTasks, "care task")
                    + " handed over to " + successor.fullName());
        }
        if (leftSessions > 0) forTrainer.add(plural(leftSessions, "session") + " still to reassign (Reschedule, then choose another groom)");
        if (leftTasks > 0) forTrainer.add(plural(leftTasks, "care task") + " still to hand over in Stable care");
        if (!forTrainer.isEmpty()) {
            notifyGroomsTrainer(target, actor, new Notice("SESSIONS_NEED_GROOM",
                    leftSessions + leftTasks > 0 ? "Training work needs a new groom" : "Groom's work handed over",
                    target.fullName() + " " + reason + ": " + String.join("; ", forTrainer) + ".", null, null));
        }

        List<String> forManager = new ArrayList<>();
        if (stopped > 0) forManager.add("Stopped " + plural(stopped, "running session") + "; it goes to the Head Trainer's review.");
        if (movedSessions + movedTasks > 0) {
            forManager.add("Handed over " + plural(movedSessions, "session") + " and " + plural(movedTasks, "care task")
                    + " to " + successor.fullName() + ".");
        }
        if (leftSessions + leftTasks > 0) {
            forManager.add(plural(leftSessions, "session") + " and " + plural(leftTasks, "care task")
                    + " are still in " + target.fullName() + "'s name; their Head Trainer has been asked to reassign them.");
        }
        return forManager.isEmpty() ? null : String.join(" ", forManager);
    }

    /**
     * A vet leaves. Incident cases they took (RECEIVED) and did not examine go to
     * the successor, or back to the open queue every vet sees - otherwise they
     * would sit under the name of someone who will never examine them. Vet calls
     * are a shared queue and need nothing; if no vet is left, the manager is warned.
     */
    private String vetLeaves(StaffRow target, StaffRow successor, AuthenticatedUser actor, String reason) {
        List<String> forManager = new ArrayList<>();
        if (successor != null) {
            int moved = staff.handOverCases(target.userId(), successor.userId());
            if (moved > 0) {
                notifications.notifyUser(successor.userId(), new Notice("WORK_HANDED_OVER", "Incident cases handed over to you",
                        target.fullName() + " " + reason + ". " + plural(moved, "incident case")
                                + " they had taken " + (moved == 1 ? "is" : "are") + " now yours to examine.",
                        null, null));
                forManager.add("Handed " + plural(moved, "incident case") + " to " + successor.fullName() + ".");
            }
        } else {
            int back = staff.returnCasesToQueue(target.userId());
            if (back > 0) {
                notifications.notifyRole("VETERINARIAN", actor, new Notice("CASES_BACK_IN_QUEUE", "Incident cases need a vet",
                        target.fullName() + " " + reason + ". " + plural(back, "incident case")
                                + " they had taken " + (back == 1 ? "is" : "are") + " back in the open queue.",
                        null, null));
                forManager.add(plural(back, "incident case") + " went back to the open queue; the other vets were told.");
            }
        }
        if (staff.countActiveOfRole("VETERINARIAN", target.userId()) == 0) {
            int calls = staff.countPendingVetCalls();
            forManager.add("There is no other active Veterinarian now"
                    + (calls > 0 ? " and " + plural(calls, "vet call") + " " + (calls == 1 ? "is" : "are") + " waiting" : "")
                    + ": activate or create a Veterinarian account.");
        }
        return forManager.isEmpty() ? null : String.join(" ", forManager);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }


    private void notifyGroomsTrainer(StaffRow groom, AuthenticatedUser actor, Notice notice) {
        Integer trainerId = groom.trainerId();
        boolean trainerActive = trainerId != null && staff.findRow(trainerId)
                .map(t -> "HEAD_TRAINER".equals(t.roleName()) && "ACTIVE".equals(t.status()))
                .orElse(false);
        if (trainerActive) {
            notifications.notifyUser(trainerId, notice);
        } else {
            notifications.notifyRole("HEAD_TRAINER", actor, notice);
        }
    }

    /**
     * A Head Trainer leaves (role change or suspension). Their horses would stay
     * locked to them: every other Head Trainer is refused "This horse is assigned to
     * Head Trainer X" and nobody can plan for the horse. So the horses are released
     * (no Head Trainer) and the grooms leave the team; the manager gets the list to
     * assign new Head Trainers in each horse's Club records, and the grooms are told.
     * Returns the notice for the manager, or null when there was nothing to release.
     */
    /**
     * A Head Trainer leaves. Plans, sessions and reviews hang on HORSE.trainer_id,
     * so they follow the horses:
     *  - with a successor (who has room), all horses and grooms move to them and
     *    the work carries on - running sessions, reviews and plans included;
     *  - without one, the horses are left with no trainer and the grooms with no
     *    team; reviews and plans wait until the manager assigns a new trainer.
     */
    private String headTrainerLeaves(StaffRow trainer, StaffRow successor, AuthenticatedUser actor, String reason) {
        List<StaffRepository.TrainerHorse> theirHorses = staff.horsesOfTrainer(trainer.userId());
        List<String> horses = theirHorses.stream().map(StaffRepository.TrainerHorse::horseName).toList();
        List<Integer> grooms = staff.groomIdsOfTrainer(trainer.userId());
        int reviews = staff.countSessionsToReviewOfTrainer(trainer.userId());
        int plans = staff.countOpenPlansOfTrainer(trainer.userId());
        if (horses.isEmpty() && grooms.isEmpty()) return null;

        if (successor != null) {
            staff.moveHorsesToTrainer(trainer.userId(), successor.userId());
            staff.moveGroomsToTrainer(trainer.userId(), successor.userId());
            for (Integer groomId : grooms) {
                notifications.notifyUser(groomId, new Notice("TRAINER_LEFT", "Your Head Trainer changed",
                        trainer.fullName() + " " + reason + ". Your Head Trainer is now " + successor.fullName() + ".",
                        null, null));
            }
            StringBuilder mine = new StringBuilder(trainer.fullName() + " " + reason + ". You now look after ")
                    .append(plural(horses.size(), "horse"));
            if (!horses.isEmpty()) mine.append(" (").append(String.join(", ", horses)).append(")");
            mine.append(" and ").append(plural(grooms.size(), "groom")).append(" of theirs.");
            if (reviews > 0) mine.append(" ").append(plural(reviews, "session")).append(" wait for your review.");
            if (plans > 0) mine.append(" ").append(plural(plans, "training plan")).append(" are now yours.");
            notifications.notifyUser(successor.userId(), new Notice("WORK_HANDED_OVER",
                    "Horses and grooms handed over to you", mine.toString(), null, null));
            tellOwners(theirHorses, "Head trainer assigned to your horse",
                    names -> names + " " + (names.contains(",") ? "are" : "is") + " now managed by Head Trainer "
                            + successor.fullName() + " (" + trainer.fullName() + " " + reason + ").");
            return "Handed " + plural(horses.size(), "horse") + " and " + plural(grooms.size(), "groom")
                    + " to " + successor.fullName()
                    + (reviews + plans > 0 ? ", with " + plural(reviews, "session") + " to review and "
                            + plural(plans, "open plan") : "")
                    + ".";
        }

        // Nobody takes over: a session running now has no Head Trainer to watch its
        // alerts or review it, so its groom is asked to end it, with the reason
        // NO_HEAD_TRAINER (no readings needed first). Read before the horses are released.
        List<StaffRepository.RunningSession> running = staff.runningSessionsOfTrainer(trainer.userId());
        for (StaffRepository.RunningSession s : running) {
            notifications.notifyUser(s.groomId(), new Notice("SESSION_STOP_REQUESTED", "Stop this training session now",
                    s.horseName() + ": please end this session now (End session early, reason \"No Head Trainer to supervise\"). Head Trainer "
                            + trainer.fullName() + " " + reason + ", so nobody can watch this session's alerts or review it"
                            + " until the club assigns a new Head Trainer.",
                    Notice.TRAINING_SESSION, s.sessionId()));
        }
        if (!horses.isEmpty()) staff.releaseHorsesOfTrainer(trainer.userId());
        tellOwners(theirHorses, "Head trainer removed from your horse",
                names -> names + " " + (names.contains(",") ? "no longer have" : "no longer has") + " a Head Trainer: "
                        + trainer.fullName() + " " + reason + ". The club will assign a new one.");
        if (!grooms.isEmpty()) staff.detachGroomsFromTrainer(trainer.userId());
        for (Integer groomId : grooms) {
            notifications.notifyUser(groomId, new Notice("TRAINER_LEFT", "Your Head Trainer changed",
                    trainer.fullName() + " " + reason + ". The club manager will assign you to another Head Trainer.",
                    null, null));
        }
        StringBuilder sb = new StringBuilder();
        if (!horses.isEmpty()) {
            sb.append(horses.size()).append(horses.size() == 1 ? " horse has" : " horses have")
              .append(" no Head Trainer now: ").append(String.join(", ", horses))
              .append(". Assign a new one in each horse's Club records.");
        }
        if (!grooms.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(grooms.size()).append(grooms.size() == 1 ? " groom is" : " grooms are")
              .append(" unassigned; give them to another Head Trainer on this page.");
        }
        if (reviews + plans > 0) {
            sb.append(' ').append(plural(reviews, "session")).append(" to review and ")
              .append(plural(plans, "open plan")).append(" wait for the new Head Trainer.");
        }
        if (!running.isEmpty()) {
            sb.append(" Asked the grooms to stop ").append(plural(running.size(), "running session")).append(" (")
              .append(running.stream().map(StaffRepository.RunningSession::horseName)
                      .collect(java.util.stream.Collectors.joining(", ")))
              .append(").");
        }
        return sb.toString();
    }

    /**
     * Each owner hears once about their own horses, the same notice as when the
     * manager changes a horse's trainer by hand (HORSE_TRAINER_CHANGED).
     */
    private void tellOwners(List<StaffRepository.TrainerHorse> horses, String title,
                            java.util.function.Function<String, String> text) {
        java.util.Map<Integer, List<StaffRepository.TrainerHorse>> byOwner = new java.util.LinkedHashMap<>();
        for (StaffRepository.TrainerHorse h : horses) {
            byOwner.computeIfAbsent(h.ownerId(), k -> new ArrayList<>()).add(h);
        }
        byOwner.forEach((ownerId, own) -> {
            String names = own.stream().map(StaffRepository.TrainerHorse::horseName)
                    .collect(java.util.stream.Collectors.joining(", "));
            notifications.notifyUser(ownerId, new Notice("HORSE_TRAINER_CHANGED", title, text.apply(names),
                    own.size() == 1 ? Notice.HORSE : null, own.size() == 1 ? own.get(0).horseId() : null));
        });
    }

    private ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, "Account information already exists", errors);
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
